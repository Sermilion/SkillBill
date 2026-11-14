package skillbill.infrastructure.launcher.process.cursor

import kotlinx.serialization.json.JsonObject
import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionCapture
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionChannel
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionOutcome
import skillbill.infrastructure.launcher.process.interaction.SerializedStdinWriter
import skillbill.ports.agentrun.model.AgentRunModelObserver
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.approval.ApprovalOwnerEffects
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalPendingWait
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.skeleton.LAUNCH_MODEL_TOKEN_PATTERN
import java.time.Clock

internal class CursorSessionChannel(
  launch: CursorAcpLaunch,
  clock: Clock,
  private val output: AgentRunOutputSink,
  private val diagnostics: RuntimeDiagnostics,
  modelObserver: AgentRunModelObserver? = null,
) : AgentRunSessionChannel {
  private var writer: SerializedStdinWriter? = null
  private val providerName = launch.provider.name
  private val seam = launch.provider.seam
  private val buffer = StringBuilder()
  private val captureLock = Any()
  private val toolOutput = StringBuilder()
  private var deliveryFailed = false
  private var captureIncomplete = false
  private var captureFrozen = false
  private var frozenCapture: AgentRunSessionCapture? = null
  private val protocol =
    CursorAcpSession(
      launch,
      clock,
      writeLine = { writer?.write((it + "\n").encodeToByteArray()) == true },
      saveSession = { true },
      onEvent = { event ->
        when (event) {
          is CursorAcpEvent.Text ->
            deliver(AgentRunOutputStream.STDOUT, event.text, "assistant_output")
          is CursorAcpEvent.Tool -> toolUpdate(event.update)
          is CursorAcpEvent.Unsupported ->
            output.write(
              AgentRunOutputStream.STDERR,
              "Unsupported $providerName request: ${event.method}\n${event.payload}\n",
            )
        }
      },
      onCard = {},
      record = diagnostics::warning,
      onModel = { model -> model.takeIf(LAUNCH_MODEL_TOKEN_PATTERN::matches)?.let { modelObserver?.reported(it) } },
    )
  val effects: ApprovalOwnerEffects get() = protocol

  fun bind(owner: ApprovalOwnerSession) = protocol.bind(owner)

  override fun attach(process: Process): Boolean {
    writer = SerializedStdinWriter(process.outputStream)
    protocol.start()
    return protocol.outcome() == null
  }

  override fun observe(chunk: String) {
    for (part in chunk.splitToSequence('\n').withIndex()) {
      if (part.index > 0) {
        val frame = buffer.toString()
        buffer.setLength(0)
        if (frame.isNotEmpty()) protocol.observe(frame)
      }
      if (protocol.outcome() != null) return
      buffer.append(part.value)
    }
  }

  override fun pendingWait(): ApprovalPendingWait? = protocol.pendingWait()

  override fun enforceOpening() = protocol.enforceOpening()

  override fun finish() {
    noteIncompleteFrame()
    protocol.close()
    writer?.close()
    freezeCapture()
  }

  override fun outcome(): AgentRunSessionOutcome? =
    protocol.outcome()?.let {
      AgentRunSessionOutcome(it.text, it.model, it.sessionId, it.failure == null, it.failure)
    }

  override fun capture(): AgentRunSessionCapture {
    noteIncompleteFrame()
    return freezeCapture()
  }

  private fun noteIncompleteFrame() {
    if (buffer.isNotEmpty() && !captureFrozen) {
      captureIncomplete = true
      diagnostics.warning("seam=$seam outcome=incomplete_frame")
    }
  }

  private fun toolUpdate(update: JsonObject) {
    val record = "$providerName tool update: $update\n"
    val retained =
      synchronized(captureLock) {
        if (captureFrozen || deliveryFailed) {
          null
        } else {
          toolOutput.append(record)
          record
        }
      }
    if (retained != null) {
      deliver(AgentRunOutputStream.STDERR, retained, "tool_output")
    }
  }

  private fun deliver(
    stream: AgentRunOutputStream,
    value: String,
    seam: String,
  ) {
    catchingNonCancellation { output.write(stream, value) }
      .onFailure {
        synchronized(captureLock) { deliveryFailed = true }
        diagnostics.warning("seam=$seam outcome=${seam}_delivery_failed")
        protocol.stop("$providerName $seam could not reach the phase output sink.")
      }
  }

  private fun freezeCapture(): AgentRunSessionCapture =
    synchronized(captureLock) {
      frozenCapture ?: AgentRunSessionCapture(
        text = protocol.textSnapshot(),
        complete = !deliveryFailed && !captureIncomplete,
        truncated = false,
        deliveryFailed = deliveryFailed,
        providerDiagnostics = toolOutput.toString().takeIf(String::isNotBlank),
      ).also {
        captureFrozen = true
        frozenCapture = it
      }
    }
}
