package skillbill.infrastructure.launcher.process.interaction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.approval.ApprovalOwnerAuthority
import skillbill.ports.approval.ApprovalOwnerEffects
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerOpenRequest
import skillbill.ports.approval.model.ApprovalOwnerOpenResult
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalPendingWait
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
  private val requests =
    ClaudeApprovalRequests(
      checkout = launch.workingDirectory,
      write = ::write,
      onCard = {},
      stop = ::stop,
      record = { diagnostics.warning(it) },
      announce = ::announce,
      clock = clock,
    )

  fun bind(owner: ApprovalOwnerSession) = requests.bind(owner)

  override fun attach(process: Process): Boolean {
    this.process.set(process)
    val stdin = SerializedStdinWriter(process.outputStream)
    writer.set(stdin)
    initializeSentAt.set(clock.instant())
    return stdin.write(requests.initializeFrame().frameBytes())
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

  override fun pendingWait(): ApprovalPendingWait? = requests.pendingWait()

  override fun finish() {
    if (!finished.compareAndSet(false, true)) return
    worker.shutdown()
    requests.release()
    writer.get()?.close()
  }

  override fun deliver(settled: ApprovalRequestRecord): ApprovalDeliveryState = requests.deliver(settled)

  override fun stop(reason: String) {
    diagnostics.warning("Operator approval channel stopped the agent run: $reason")
    announce("operator approval channel stopped the agent run: $reason")
    process.get()?.destroyForcibly()
    writer.get()?.close()
  }

  private fun write(frame: JsonObject): Boolean = writer.get()?.write(frame.frameBytes()) == true

  private fun handle(line: String) {
    if (finished.get()) return
    val root = catchingNonCancellation { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return
    when ((root["type"] as? JsonPrimitive)?.contentOrNull) {
      "result" -> writer.get()?.close()
      "control_response" -> handleControlResponse(root)
      "control_request" -> requests.handle(root, line)
    }
  }

  private fun handleControlResponse(root: JsonObject) {
    when (val outcome = requests.initializeOutcome(root)) {
      ClaudeInitializeOutcome.Unrelated -> Unit
      is ClaudeInitializeOutcome.Refused ->
        stopOpening("The installed Claude CLI refused host permission control during initialize: ${outcome.detail}")
      ClaudeInitializeOutcome.Initialized -> {
        if (!initialized.compareAndSet(false, true)) return
        if (!write(ClaudeHostFrames.userMessage(launch.openingPrompt))) {
          stop("The opening prompt could not be delivered to the provider.")
        }
      }
    }
  }

  private fun stopOpening(reason: String) {
    if (!openingStopped.compareAndSet(false, true)) return
    stop("$reason Update Claude Code; the run was not started.")
  }

  private fun announce(line: String) {
    catchingNonCancellation { launch.outputSink.write(AgentRunOutputStream.STDERR, "skill-bill: $line\n") }
  }

  private companion object {
    val CONTROL_MARKERS = listOf("control_request", "control_response")
    const val RESULT_MARKER = "\"result\""
    val INITIALIZE_TIMEOUT: Duration = Duration.ofSeconds(60)
  }
}
