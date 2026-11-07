package skillbill.infrastructure.launcher.process.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionCapture
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionChannel
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionOutcome
import skillbill.infrastructure.launcher.process.interaction.SerializedStdinWriter
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.approval.ApprovalOwnerEffects
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalPendingWait
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import skillbill.infrastructure.launcher.process.codex.CodexRpcKeys as K

internal data class CodexSessionLaunch(
  val prompt: String,
  val cwd: String,
  val model: String?,
  val effort: String?,
  val savedThread: String?,
  val outputSink: AgentRunOutputSink,
  val workspaceRoots: List<String> = emptyList(),
  val readOnly: Boolean = false,
)

internal class CodexSessionChannel(
  private val launch: CodexSessionLaunch,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) : AgentRunSessionChannel, ApprovalOwnerEffects {
  private val settled = AtomicReference<AgentRunSessionOutcome?>()
  private val stopHandled = AtomicBoolean(false)
  private val approvals = CodexApprovalRequests(launch.cwd, ::write, {}, ::stop, diagnostics::warning)
  private var writer: SerializedStdinWriter? = null
  private var openingAt: Instant? = null
  private var expectedResponse = INITIALIZE_ID

  @Volatile private var threadId: String? = null

  @Volatile private var turnId: String? = null
  private var effectiveModel: String? = null
  private var providerFailure: String? = null
  private val providerDiagnostics = StringBuilder()
  private val buffer = StringBuilder()
  private val captureLock = Any()
  private val early = ArrayDeque<JsonObject>()
  private val deltas = mutableMapOf<String, StringBuilder>()
  private val prose = StringBuilder()
  private var finalText = ""
  private var captureTruncated = false
  private var proseDeliveryFailed = false
  private var captureFrozen = false
  private var frozenCapture: AgentRunSessionCapture? = null

  fun bind(session: ApprovalOwnerSession) {
    approvals.bind(session)
  }

  override fun attach(process: Process): Boolean {
    writer = SerializedStdinWriter(process.outputStream)
    openingAt = clock.instant()
    return sendRequest(
      INITIALIZE_ID,
      K.INITIALIZE_METHOD,
      buildJsonObject {
        put(K.CAPABILITIES, buildJsonObject { put(K.EXPERIMENTAL_API, true) })
        put(
          K.CLIENT_INFO,
          buildJsonObject {
            put(K.NAME, "skill-bill")
            put(K.TITLE, "Skill Bill")
            put(K.VERSION, "1.0.0")
          },
        )
      },
    )
  }

  override fun observe(chunk: String) {
    if (settled.get() != null) return
    for (part in chunk.splitToSequence('\n').withIndex()) {
      if (part.index > 0) {
        if (buffer.isNotEmpty()) handleLine(buffer.toString())
        buffer.setLength(0)
      }
      if (settled.get() != null) return
      if (buffer.length + part.value.length > MAX_TEXT_CHARS) {
        stop("Codex app-server sent an oversized protocol frame.")
        return
      }
      buffer.append(part.value)
    }
  }

  private fun handleLine(line: String) {
    val message = catchingNonCancellation { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject
    if (message == null) {
      stop("Codex app-server sent a malformed protocol frame.")
      return
    }
    if (message.text(K.METHOD) == K.ERROR && message[K.ID] == null) {
      providerErrorNotification(message)
      return
    }
    when {
      message[K.ID] != null && message[K.METHOD] != null -> {
        if (turnId == null && early.size < MAX_EARLY_MESSAGES) {
          early.addLast(message)
        } else {
          serverRequest(message)
        }
      }
      message[K.ID] == JsonPrimitive(expectedResponse) && expectedResponse != READY -> response(message)
      message[K.ID] != null -> stop("Codex returned an unexpected response identity.")
      turnId != null -> notification(message)
      early.size < MAX_EARLY_MESSAGES -> early.addLast(message)
      else -> stop("Codex sent too many notifications before its opening response.")
    }
  }

  private fun response(message: JsonObject) {
    val result = message[K.RESULT] as? JsonObject
    if (result == null) {
      val detail = providerError(message[K.ERROR] as? JsonObject, message[K.ERROR])
      recordProviderDiagnostic(detail)
      val recovery =
        if (launch.savedThread != null && expectedResponse == THREAD_ID) {
          " The saved provider thread could not be resumed; start an explicit new " +
            "session."
        } else {
          ""
        }
      stop("Codex app-server rejected its opening request: $detail$recovery")
      return
    }
    when (expectedResponse) {
      INITIALIZE_ID -> initialize(result)
      THREAD_ID -> openThread(result)
      TURN_ID -> openTurn(result)
    }
  }

  private fun serverRequest(message: JsonObject) = approvals.request(message)

  private fun initialize(result: JsonObject) {
    val identity = result.text(K.USER_AGENT).orEmpty()
    if (!Regex("(?:^|[/ ])${Regex.escape(CODEX_PROTOCOL_VERSION)}(?:$|[ )])").containsMatchIn(identity)) {
      stop(
        "Codex app-server identity is incompatible with the supported " +
          "$CODEX_PROTOCOL_VERSION protocol.",
      )
      return
    }
    if (!write(buildJsonObject { put(K.METHOD, K.INITIALIZED) })) return
    expectedResponse = THREAD_ID
    val method = if (launch.savedThread == null) K.THREAD_START_METHOD else K.THREAD_RESUME_METHOD
    sendRequest(
      THREAD_ID,
      method,
      buildJsonObject {
        launch.savedThread?.let { put(K.THREAD_ID, it) }
        put(K.CWD, launch.cwd)
        if (launch.workspaceRoots.isNotEmpty()) {
          val roots = (listOf(launch.cwd) + launch.workspaceRoots).distinct().map(::JsonPrimitive)
          if (!launch.readOnly) put(K.WORKSPACE_ROOTS, JsonArray(roots))
        }
        launch.model?.let { put(K.MODEL, it) }
        if (launch.readOnly) put(K.SANDBOX, K.READ_ONLY_SANDBOX)
      },
    )
  }

  private fun openThread(result: JsonObject) {
    val returned = (result[K.THREAD] as? JsonObject)?.text(K.ID)
    val returnedModel = result.text(K.MODEL)
    synchronized(captureLock) { effectiveModel = returnedModel }
    val validIdentity = !returned.isNullOrBlank() && (launch.savedThread == null || returned == launch.savedThread)
    val validSettings = preservesThreadSettings(result, returnedModel)
    if (!validIdentity && launch.savedThread != null) {
      stop(
        "The saved Codex provider thread could not be resumed unchanged; start an " +
          "explicit new session.",
      )
      return
    }
    if (!validIdentity || !validSettings) {
      stop(
        "Codex did not preserve the provider thread, selected model or checkout; " +
          "the turn was not started.",
      )
      return
    }
    synchronized(captureLock) { threadId = returned }
    expectedResponse = TURN_ID
    sendRequest(
      TURN_ID,
      K.TURN_START_METHOD,
      buildJsonObject {
        put(K.THREAD_ID, returned)
        put(K.CWD, launch.cwd)
        launch.model?.let { put(K.MODEL, it) }
        launch.effort?.let { put(K.EFFORT, it) }
        put(
          K.SANDBOX_POLICY,
          buildJsonObject {
            put(K.TYPE, if (launch.readOnly) K.READ_ONLY_POLICY else K.WORKSPACE_WRITE_POLICY)
            if (!launch.readOnly) {
              val roots = (listOf(launch.cwd) + launch.workspaceRoots).distinct().map(::JsonPrimitive)
              put(K.WRITABLE_ROOTS, JsonArray(roots))
            }
          },
        )
        put(
          K.INPUT,
          JsonArray(
            listOf(
              buildJsonObject {
                put(K.TYPE, "text")
                put(K.TEXT, launch.prompt)
                put(K.TEXT_ELEMENTS, JsonArray(emptyList()))
              },
            ),
          ),
        )
      },
    )
  }

  private fun preservesThreadSettings(
    result: JsonObject,
    returnedModel: String?,
  ): Boolean =
    result.text(K.CWD) == launch.cwd && !returnedModel.isNullOrBlank() &&
      (launch.model == null || returnedModel == launch.model) &&
      (!launch.readOnly || result.obj(K.SANDBOX)?.text(K.TYPE) == K.READ_ONLY_POLICY)

  private fun openTurn(result: JsonObject) {
    val turn = result[K.TURN] as? JsonObject
    val returned = turn?.text(K.ID)
    if (returned.isNullOrBlank() || turn.text(K.STATUS).isNullOrBlank()) {
      stop(
        "Codex returned a malformed turn/start response; " +
          "delivery is uncertain and will not be replayed.",
      )
      return
    }
    synchronized(captureLock) { turnId = returned }
    expectedResponse = READY
    approvals.bindTurn(threadId.orEmpty(), returned)
    while (early.isNotEmpty() && settled.get() == null) {
      val message = early.removeFirst()
      if (message[K.ID] != null && message[K.METHOD] != null) serverRequest(message) else notification(message)
    }
  }

  private fun notification(message: JsonObject) {
    val params = message[K.PARAMS] as? JsonObject ?: return
    val method = message.text(K.METHOD)
    if (method == K.ERROR) {
      providerErrorNotification(message)
      return
    }
    if (params.text(K.THREAD_ID) != threadId) return
    val eventTurn = params.text(K.TURN_ID) ?: (params[K.TURN] as? JsonObject)?.text(K.ID)
    if (method != K.SERVER_REQUEST_RESOLVED && eventTurn != turnId) return
    dispatchNotification(method, params)
  }

  private fun dispatchNotification(
    method: String?,
    params: JsonObject,
  ) {
    when (method) {
      K.ITEM_STARTED_METHOD -> params.obj(K.ITEM)?.let { approvals.itemStarted(params, it) }
      K.SERVER_REQUEST_RESOLVED -> approvals.providerResolved(params)
      K.ITEM_COMPLETED_METHOD -> itemCompleted(params)
      K.AGENT_MESSAGE_DELTA_METHOD -> delta(params)
      K.TURN_INTERRUPTED_METHOD, K.TURN_FAILED_METHOD -> failedTurn(params)
      K.TURN_COMPLETED_METHOD -> {
        approvals.turnEnded(threadId.orEmpty(), turnId.orEmpty())
        complete(params.obj(K.TURN))
      }
      else -> Unit
    }
  }

  private fun itemCompleted(params: JsonObject) {
    val completed = params.obj(K.ITEM) ?: return
    val permissionControlled = approvals.itemCompleted(params, completed)
    if (permissionControlled == true && failedItemDiagnostic(completed) != null) {
      providerFailure = providerFailure
        ?: "Codex permission-controlled action failed: ${completed.text(K.ID).orEmpty()}"
    }
    if (completed.text(K.STATUS) == K.DECLINED) {
      providerFailure = "Codex declined the requested action: $completed"
    }
    if (permissionControlled != null) completedItem(completed)
  }

  private fun failedTurn(params: JsonObject) {
    approvals.turnEnded(threadId.orEmpty(), turnId.orEmpty())
    val error = params.obj(K.TURN)?.obj(K.ERROR)
    val detail =
      providerError(error, error?.get(K.DATA))
        .ifBlank { "Codex turn was interrupted or failed." }
    recordProviderDiagnostic(detail)
    providerFailure = providerFailure ?: detail
    stop(detail)
  }

  private fun providerErrorNotification(message: JsonObject) {
    val params = message[K.PARAMS] as? JsonObject ?: message
    val detail = providerError(params[K.ERROR] as? JsonObject, params[K.ERROR])
    if (detail.isBlank()) return
    recordProviderDiagnostic(detail)
    if (params[K.WILL_RETRY]?.let { (it as? JsonPrimitive)?.content == "true" } == true) return
    if (detail.contains(CODEX_OUTPUT_EXHAUSTED)) {
      approvals.release()
      return
    }
    providerFailure = providerFailure ?: detail
    stop(detail)
  }

  private fun failedItemDiagnostic(item: JsonObject): String? {
    val status = item.text(K.STATUS)
    val error = item[K.ERROR] as? JsonObject
    val exitCode = (item[K.EXIT_CODE] as? JsonPrimitive)?.intOrNull ?: 0
    if (status !in setOf("failed", K.DECLINED) && error == null && exitCode == 0) return null
    val output = item[K.AGGREGATED_OUTPUT]?.takeUnless { it == JsonPrimitive("") }
    val actual =
      output?.renderDiagnostic()
        ?: item[K.ERROR]?.let { providerError(error, it) }
        ?: item[K.RESULT]?.toString()
    return buildString {
      append("Codex item ").append(item.text(K.ID).orEmpty())
      append(" (status=").append(status.orEmpty()).append(")")
      actual?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
      if (isBlank()) append(item.toString())
    }
  }

  private fun providerError(
    error: JsonObject?,
    raw: JsonElement?,
  ): String =
    buildList {
      error?.text(K.MESSAGE)?.takeIf(String::isNotBlank)?.let(::add)
      error?.get(K.CODE)?.renderDiagnostic()?.takeIf(String::isNotBlank)?.let(::add)
      error?.get(K.ADDITIONAL_DETAILS)?.renderDiagnostic()?.takeIf(String::isNotBlank)?.let(::add)
      error?.get(K.CODEX_ERROR_INFO)?.renderDiagnostic()?.takeIf(String::isNotBlank)?.let(::add)
      error?.get(K.DATA)?.renderDiagnostic()?.takeIf(String::isNotBlank)?.let(::add)
      raw?.takeIf { error == null }?.renderDiagnostic()?.takeIf(String::isNotBlank)?.let(::add)
    }.distinct().joinToString("\n")

  private fun JsonElement.renderDiagnostic(): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: toString()

  private fun recordProviderDiagnostic(detail: String) {
    if (detail.isBlank()) return
    val retained = detail.take(MAX_TEXT_CHARS)
    synchronized(captureLock) {
      if (providerDiagnostics.isNotEmpty()) providerDiagnostics.append('\n')
      providerDiagnostics.append(retained.take((MAX_TEXT_CHARS - providerDiagnostics.length).coerceAtLeast(0)))
    }
    catchingNonCancellation { launch.outputSink.write(AgentRunOutputStream.STDERR, "$retained\n") }
      .onFailure {
        synchronized(captureLock) { proseDeliveryFailed = true }
        diagnostics.warning("seam=codex_session outcome=provider_diagnostic_delivery_failed")
      }
  }

  private fun delta(params: JsonObject) {
    val id = params.text(K.ITEM_ID) ?: return
    val text = params.text(K.DELTA) ?: return
    val accepted =
      synchronized(captureLock) {
        if (captureFrozen) return@synchronized null
        val accumulated = deltas.getOrPut(id) { StringBuilder() }
        val remaining = MAX_TEXT_CHARS - prose.length
        val acceptedText = text.take(remaining.coerceAtLeast(0))
        accumulated.append(acceptedText)
        prose.append(acceptedText)
        val truncated = acceptedText.length != text.length
        if (truncated) captureTruncated = true
        val deliveryFailed = acceptedText.isNotEmpty() && !writeProseLocked(acceptedText)
        Triple(acceptedText, truncated, deliveryFailed)
      }
    if (accepted == null) return
    if (accepted.third) stop("Codex assistant output could not be delivered to the phase output sink.")
    if (accepted.second) stop("Codex assistant output exceeded the capture limit.")
  }

  private fun completedItem(item: JsonObject) {
    if (item.text(K.TYPE) != K.AGENT_MESSAGE_TYPE) {
      recordCompletedTool(item)
      return
    }
    val id = item.text(K.ID) ?: return
    val text = item.text(K.TEXT) ?: return
    val completion = captureCompletedItem(id, text)
    if (completion == null) {
      stop("Codex returned inconsistent assistant completion text.")
    } else if (completion.truncated) {
      stop("Codex returned inconsistent or oversized assistant output.")
    } else if (completion.deliveryFailed) {
      stop("Codex assistant output could not be delivered to the phase output sink.")
    }
  }

  private fun recordCompletedTool(item: JsonObject) {
    failedItemDiagnostic(item)?.let(::recordProviderDiagnostic)
    if (item.text(K.STATUS) == K.DECLINED) {
      providerFailure = providerFailure ?: "Codex declined the requested action: $item"
    }
  }

  private fun captureCompletedItem(
    id: String,
    text: String,
  ): CodexCompletedProse? =
    synchronized(captureLock) {
      if (captureFrozen) return@synchronized null
      val streamedText = deltas.remove(id)?.toString().orEmpty()
      if (!text.startsWith(streamedText)) return@synchronized null
      val unseen = text.drop(streamedText.length)
      val remaining = (MAX_TEXT_CHARS - prose.length).coerceAtLeast(0)
      val acceptedSuffix = unseen.take(remaining)
      prose.append(acceptedSuffix)
      val didTruncate = acceptedSuffix.length != unseen.length || text.length > MAX_TEXT_CHARS
      if (didTruncate) captureTruncated = true
      finalText = text
      val deliveryFailed = !writeProseLocked(acceptedSuffix + "\n")
      CodexCompletedProse(streamedText, didTruncate, deliveryFailed)
    }

  private fun writeProseLocked(text: String): Boolean =
    catchingNonCancellation { launch.outputSink.write(AgentRunOutputStream.STDOUT, text) }
      .onFailure { proseDeliveryFailed = true }
      .isSuccess

  private fun complete(turn: JsonObject?) {
    val status = turn?.text(K.STATUS)
    val turnError = turn?.get(K.ERROR) as? JsonObject
    turnError?.let {
      providerError(it, it[K.DATA]).takeIf(String::isNotBlank)?.let { detail ->
        recordProviderDiagnostic(detail)
        if (status == "completed") providerFailure = providerFailure ?: detail
      }
    }
    val success = status == "completed" && providerFailure == null
    val failure =
      if (success) {
        null
      } else {
        providerFailure ?: terminalFailure(turnError, status)
      }
    val (text, model, thread) =
      synchronized(captureLock) {
        captureFrozen = true
        frozenCapture = captureSnapshot()
        Triple(finalText, effectiveModel, threadId)
      }
    settle(AgentRunSessionOutcome(text, model, thread, success, failure, status == "failed" && providerFailure == null))
  }

  private fun terminalFailure(
    error: JsonObject?,
    status: String?,
  ): String {
    val detail = error?.let { providerError(it, it[K.DATA]) }.orEmpty()
    return when {
      status == "failed" && CODEX_OUTPUT_EXHAUSTED in detail -> CODEX_OUTPUT_EXHAUSTED
      detail.isNotBlank() -> detail
      else -> "Codex turn ended with status $status."
    }
  }

  override fun outcome(): AgentRunSessionOutcome? = settled.get()

  override fun capture(): AgentRunSessionCapture =
    synchronized(captureLock) {
      frozenCapture ?: captureSnapshot()
    }

  override fun pendingWait(): ApprovalPendingWait? = approvals.pendingWait()

  override fun enforceOpening() {
    val start = openingAt ?: return
    val turnStarted = synchronized(captureLock) { turnId != null }
    if (!turnStarted && Duration.between(start, clock.instant()) >= OPENING_TIMEOUT) {
      stop("Codex app-server did not complete its opening handshake in time.")
    }
  }

  override fun stop(reason: String) {
    if (!stopHandled.compareAndSet(false, true)) return
    writer?.close()
    diagnostics.warning("seam=codex_session outcome=stopped detail=$reason")
    val (text, model, thread) =
      synchronized(captureLock) {
        captureFrozen = true
        frozenCapture = captureSnapshot()
        Triple(finalText, effectiveModel, threadId)
      }
    settle(AgentRunSessionOutcome(text, model, thread, false, reason))
    approvals.release()
  }

  override fun deliver(settled: ApprovalRequestRecord): ApprovalDeliveryState = approvals.deliver(settled)

  override fun finish() {
    if (settled.get() == null) {
      stop(
        "Codex app-server process ended before the active turn reached a terminal " +
          "result.",
      )
    }
    approvals.release()
    writer?.close()
  }

  private fun settle(outcome: AgentRunSessionOutcome) {
    if (settled.compareAndSet(null, outcome)) writer?.close()
  }

  private fun captureSnapshot(): AgentRunSessionCapture =
    AgentRunSessionCapture(
      text = prose.toString(),
      complete = !captureTruncated && !proseDeliveryFailed,
      truncated = captureTruncated,
      deliveryFailed = proseDeliveryFailed,
      providerDiagnostics = providerDiagnostics.toString().takeIf(String::isNotBlank),
      failureDiagnostics = providerDiagnostics.toString().takeIf(String::isNotBlank),
    )

  private fun sendRequest(
    id: Int,
    method: String,
    params: JsonObject,
  ): Boolean =
    write(
      buildJsonObject {
        put(K.ID, id)
        put(K.METHOD, method)
        put(K.PARAMS, params)
      },
    )

  private fun write(message: JsonObject): Boolean {
    val written = writer?.write((message.toString() + "\n").encodeToByteArray()) == true
    if (!written) {
      stop(
        "Codex app-server input delivery is uncertain; the turn will not be " +
          "replayed.",
      )
    }
    return written
  }

  private companion object {
    const val INITIALIZE_ID = 1
    const val THREAD_ID = 2
    const val TURN_ID = 3
    const val READY = 0
    const val MAX_TEXT_CHARS = 4_000_000
    const val MAX_EARLY_MESSAGES = 256
    const val MAX_PENDING_REQUESTS = 128
    const val MAX_ITEM_FACTS = 512
    const val MAX_TOMBSTONES = 1024
    val OPENING_TIMEOUT: Duration = Duration.ofSeconds(30)
  }
}

private data class CodexCompletedProse(
  val streamed: String,
  val truncated: Boolean,
  val deliveryFailed: Boolean,
)
