package skillbill.infrastructure.launcher.process.interaction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import skillbill.approval.model.ApprovalActionClass
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.approval.model.DENY_OPTION_KIND
import skillbill.approval.model.ONE_TIME_ALLOW_OPTION_KIND
import skillbill.contracts.CanonicalJson
import skillbill.contracts.approval.ApprovalAuthorityScope
import skillbill.contracts.approval.ApprovalOption
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalState
import skillbill.contracts.approval.OPERATOR_APPROVAL_CONTRACT_VERSION
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.approval.ApprovalOwnerAuthority
import skillbill.ports.approval.ApprovalOwnerEffects
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalOwnerOpenRequest
import skillbill.ports.approval.model.ApprovalOwnerOpenResult
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalPendingWait
import skillbill.ports.approval.model.ApprovalRegistrationCommand
import skillbill.ports.approval.model.ApprovalRegistrationResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal const val CLAUDE_APPROVAL_PROVIDER = "claude"

internal data class ClaudeApprovalLaunch(
  val openingPrompt: String,
  val workingDirectory: String,
  val outputSink: AgentRunOutputSink,
)

internal class ClaudeApprovalChannelOpening(
  private val authority: ApprovalOwnerAuthority,
  private val openRequest: ApprovalOwnerOpenRequest,
  private val launch: ClaudeApprovalLaunch,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) : AgentRunApprovalChannelOpening {
  override fun open(): AgentRunApprovalOpenResult {
    val channel = ClaudeApprovalChannel(launch, clock, diagnostics)
    return when (val opened = authority.open(openRequest, channel)) {
      is ApprovalOwnerOpenResult.Opened -> {
        channel.bind(opened.session)
        AgentRunApprovalOpenResult.Opened(channel)
      }
      is ApprovalOwnerOpenResult.Refused -> {
        channel.finish()
        AgentRunApprovalOpenResult.Refused(opened.reason)
      }
    }
  }
}

internal class ClaudeApprovalChannel(
  private val launch: ClaudeApprovalLaunch,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) : AgentRunApprovalChannel, ApprovalOwnerEffects {
  private val session = AtomicReference<ApprovalOwnerSession?>()
  private val process = AtomicReference<Process?>()
  private val writer = AtomicReference<SerializedStdinWriter?>()
  private val splitter = JsonLineSplitter()
  private val worker =
    Executors.newSingleThreadExecutor { task ->
      Thread(task, "skillbill-agent-run-approval").apply { isDaemon = true }
    }
  private val finished = AtomicBoolean(false)
  private val initializeSentAt = AtomicReference<Instant?>()
  private val initialized = AtomicBoolean(false)
  private val openingStopped = AtomicBoolean(false)

  private val interactionRequestIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

  fun bind(owner: ApprovalOwnerSession) {
    session.set(owner)
  }

  override fun attach(process: Process): Boolean {
    this.process.set(process)
    val stdin = SerializedStdinWriter(process.outputStream)
    writer.set(stdin)
    initializeSentAt.set(clock.instant())
    return stdin.write(ClaudeHostFrames.initialize(INITIALIZE_REQUEST_ID))
  }

  override fun enforceOpening() {
    val sentAt = initializeSentAt.get() ?: return
    if (finished.get() || initialized.get()) return
    if (Duration.between(sentAt, clock.instant()) >= INITIALIZE_TIMEOUT) {
      stopOpening("The installed Claude CLI did not answer the host permission initialize request in time.")
    }
  }

  override fun observe(chunk: String) {
    if (finished.get()) return
    for (line in splitter.feed(chunk)) {
      val relevant = CONTROL_MARKERS.any(line::contains) || line.contains(RESULT_MARKER)
      if (!relevant) continue
      try {
        worker.execute { handle(line) }
      } catch (_: RejectedExecutionException) {
        return
      }
    }
  }

  override fun pendingWait(): ApprovalPendingWait? = session.get()?.pendingWait()

  override fun finish() {
    if (!finished.compareAndSet(false, true)) return
    worker.shutdown()
    catchingNonCancellation { session.get()?.release() }
      .onFailure { failure -> diagnostics.warning("Approval generation release failed: ${failure.message.orEmpty()}") }
    writer.get()?.close()
  }

  override fun deliver(settled: ApprovalRequestRecord): ApprovalDeliveryState {
    val requestId = settled.providerRequestId.toJsonElement()
    val frame =
      if (settled.state == ApprovalState.ALLOWED) {
        val original = originalInput(settled.providerPayload)
        if (original != null) {
          ClaudeHostFrames.allow(requestId, original)
        } else {
          ClaudeHostFrames.deny(requestId, "The approved input could not be restored, so the action was not run.")
        }
      } else if ((requestId as? JsonPrimitive)?.content in interactionRequestIds) {
        ClaudeHostFrames.deny(requestId, INTERACTION_MESSAGE)
      } else {
        ClaudeHostFrames.deny(requestId, denialMessage(settled.state))
      }
    announce("operator approval ${settled.state.wireValue}: ${settled.title}")
    return if (writer.get()?.write(frame) == true) ApprovalDeliveryState.WRITTEN else ApprovalDeliveryState.UNCERTAIN
  }

  override fun stop(reason: String) {
    diagnostics.warning("Operator approval channel stopped the agent run: $reason")
    announce("operator approval channel stopped the agent run: $reason")
    process.get()?.destroyForcibly()
    writer.get()?.close()
  }

  private fun handle(line: String) {
    if (finished.get()) return
    val root = catchingNonCancellation { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return
    when (root.text("type")) {
      "result" -> writer.get()?.close()
      "control_response" -> handleControlResponse(root)
      "control_request" -> handleControlRequest(root, line)
    }
  }

  private fun handleControlResponse(root: JsonObject) {
    val body = root["response"] as? JsonObject ?: return
    if (body.text("request_id") != INITIALIZE_REQUEST_ID) return
    if (body.text("subtype") != "success") {
      val detail = body.text("error").orEmpty()
      stopOpening("The installed Claude CLI refused host permission control during initialize: $detail")
      return
    }
    if (!initialized.compareAndSet(false, true)) return
    if (writer.get()?.write(ClaudeHostFrames.userMessage(launch.openingPrompt)) != true) {
      stop("The opening prompt could not be delivered to the provider.")
    }
  }

  private fun stopOpening(reason: String) {
    if (!openingStopped.compareAndSet(false, true)) return
    stop("$reason Update Claude Code; the run was not started.")
  }

  private fun handleControlRequest(
    root: JsonObject,
    line: String,
  ) {
    val requestId = root["request_id"] as? JsonPrimitive
    if (requestId == null) {
      stop("A provider control request carried no request id.")
      return
    }
    val body = root["request"] as? JsonObject
    if (body == null || body.text("subtype") != PERMISSION_SUBTYPE) {
      if (writer.get()?.write(ClaudeHostFrames.unsupported(requestId, "Unsupported control request.")) != true) {
        stop("An unsupported control request could not be answered.")
      }
      return
    }
    val toolName = body.text("tool_name")
    val input = body["input"] as? JsonObject
    if (toolName.isNullOrBlank() || input == null) {
      writeDenial(requestId, "The permission request was malformed, so the action was not run.")
      return
    }
    register(requestId, PermissionFrame(toolName, input, body, line))
  }

  private fun register(
    requestId: JsonPrimitive,
    frame: PermissionFrame,
  ) {
    val owner = session.get()
    if (owner == null) {
      writeDenial(requestId, UNAVAILABLE_MESSAGE)
      return
    }
    val interaction = classify(frame.toolName).actionClass == ApprovalActionClass.PROVIDER_INTERACTION
    if (interaction) interactionRequestIds.add(requestId.content)
    catchingNonCancellation {
      val command = permissionCommand(owner.key, requestId.content, frame)
      val registered = owner.register(command)
      if (interaction && registered is ApprovalRegistrationResult.Registered) {
        owner.resolveByProvider(
          registered.request.approvalId,
          registered.request.providerRequestId,
          ApprovalProviderDisposition.INTERACTION_REQUIRED,
        )
      }
    }.onFailure { failure ->
      diagnostics.warning("Approval registration failed: ${failure.message.orEmpty()}")
      writeDenial(requestId, UNAVAILABLE_MESSAGE)
    }
  }

  private fun writeDenial(
    requestId: JsonElement,
    message: String,
  ) {
    announce("operator approval unavailable: $message")
    if (writer.get()?.write(ClaudeHostFrames.deny(requestId, message)) != true) {
      stop("A denial could not be delivered to the provider.")
    }
  }

  private fun permissionCommand(
    key: ApprovalOwnerKey,
    requestId: String,
    frame: PermissionFrame,
  ): ApprovalRegistrationCommand {
    val toolName = frame.toolName
    val input = frame.input
    val tool = classify(toolName)
    val request =
      ApprovalRequestRecord(
        contractVersion = OPERATOR_APPROVAL_CONTRACT_VERSION,
        approvalId = "$PROVIDER-$requestId",
        repositoryIdentity = key.repositoryIdentity,
        checkoutRoot = launch.workingDirectory,
        runId = key.runId,
        processGeneration = key.processGeneration,
        provider = PROVIDER,
        providerRequestId = ApprovalProviderRequestId.Text(requestId),
        title = frame.body.text("title")?.takeIf(String::isNotBlank) ?: "Allow $toolName?",
        reason = frame.reason(),
        subject = toolName,
        inputText = PRETTY_JSON.encodeToString(JsonElement.serializer(), input),
        options =
          listOf(
            ApprovalOption(ALLOW_OPTION_ID, "Allow once", ONE_TIME_ALLOW_OPTION_KIND),
            ApprovalOption(DENY_OPTION_ID, "Deny", DENY_OPTION_KIND),
          ) + frame.rememberOptions(),
        authorityScope =
          ApprovalAuthorityScope(
            profileIdentity = "",
            protocolRevision = PROTOCOL_REVISION,
            actionKind = toolName,
            workingDirectory = "",
            actionFingerprint = SCOPE_FINGERPRINT_PLACEHOLDER,
          ),
        createdAt = clock.instant().toString(),
        deadlineAt = "",
        state = ApprovalState.PENDING,
        providerPayload = frame.line,
      )
    return ApprovalRegistrationCommand(
      request = request,
      canonicalInput = CanonicalJson.encode(input),
      actionClass = tool.actionClass,
      writeCapable = tool.writeCapable,
      providerDisposition =
        if (tool.actionClass == ApprovalActionClass.PROVIDER_INTERACTION) {
          ApprovalProviderDisposition.INTERACTION_REQUIRED
        } else {
          ApprovalProviderDisposition.NONE
        },
      enclosingDeadline = null,
    )
  }

  private fun announce(line: String) {
    catchingNonCancellation { launch.outputSink.write(AgentRunOutputStream.STDERR, "skill-bill: $line\n") }
  }

  private data class ToolClass(val actionClass: ApprovalActionClass, val writeCapable: Boolean)

  private fun classify(toolName: String): ToolClass =
    when {
      toolName in INTERACTION_TOOLS -> ToolClass(ApprovalActionClass.PROVIDER_INTERACTION, writeCapable = false)
      toolName == BASH_TOOL -> ToolClass(ApprovalActionClass.ORDINARY_COMMAND, writeCapable = true)
      toolName in NETWORK_TOOLS -> ToolClass(ApprovalActionClass.NETWORK_OR_CREDENTIAL, writeCapable = false)
      toolName in READ_ONLY_TOOLS -> ToolClass(ApprovalActionClass.ORDINARY_TOOL, writeCapable = false)
      else -> ToolClass(ApprovalActionClass.ORDINARY_TOOL, writeCapable = true)
    }

  private fun denialMessage(state: ApprovalState): String =
    when (state) {
      ApprovalState.DENIED -> "The operator denied this action."
      ApprovalState.CANCELLED -> "The approval request was cancelled, so the action was not run."
      ApprovalState.EXPIRED -> "The approval request expired before the operator decided."
      ApprovalState.UNSUPPORTED -> UNAVAILABLE_MESSAGE
      ApprovalState.PROCESS_ENDED -> "The agent run ended before the request was decided."
      ApprovalState.PENDING, ApprovalState.ALLOWED -> "The request was not approved."
    }

  private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

  private fun ApprovalProviderRequestId.toJsonElement(): JsonElement =
    when (this) {
      is ApprovalProviderRequestId.Text -> JsonPrimitive(value)
      is ApprovalProviderRequestId.Integer -> JsonPrimitive(value)
    }

  private companion object {
    const val PROVIDER = CLAUDE_APPROVAL_PROVIDER
    const val PROTOCOL_REVISION = "stream-json-1"
    const val INITIALIZE_REQUEST_ID = "skill-bill-init"
    const val PERMISSION_SUBTYPE = "can_use_tool"
    val CONTROL_MARKERS = listOf("control_request", "control_response")
    const val RESULT_MARKER = "\"result\""
    val INITIALIZE_TIMEOUT: Duration = Duration.ofSeconds(60)
    val PRETTY_JSON = Json { prettyPrint = true }
    val INTERACTION_TOOLS = setOf("AskUserQuestion", "ExitPlanMode")
    const val INTERACTION_MESSAGE =
      "This tool needs interactive input that the approval card cannot supply; ask the operator in your reply " +
        "instead. In read-only chat, switch to Act to leave plan mode."
    const val ALLOW_OPTION_ID = "allow_once"
    const val DENY_OPTION_ID = "deny"
    const val SCOPE_FINGERPRINT_PLACEHOLDER = "pending"
    const val BASH_TOOL = "Bash"
    const val UNAVAILABLE_MESSAGE =
      "Operator approval is unavailable for this request: no operator surface can answer it, so the action was not run."
    val NETWORK_TOOLS = setOf("WebFetch", "WebSearch")
    val READ_ONLY_TOOLS = setOf("Read", "Glob", "Grep", "LS", "TodoWrite")
  }
}
