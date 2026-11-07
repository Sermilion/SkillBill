package skillbill.infrastructure.launcher.process.cursor

import kotlinx.serialization.json.JsonObject
import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionCapture
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionChannel
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionOutcome
import skillbill.infrastructure.launcher.process.interaction.SerializedStdinWriter
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.approval.ApprovalOwnerEffects
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalPendingWait
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.time.Clock

internal class CursorSessionChannel(
  launch: CursorAcpLaunch,
  clock: Clock,
  private val output: AgentRunOutputSink,
  private val diagnostics: RuntimeDiagnostics,
) : AgentRunSessionChannel {
  private var writer: SerializedStdinWriter? = null
  private val buffer = StringBuilder()
  private val captureLock = Any()
  private val toolOutput = StringBuilder()
  private var deliveryFailed = false
  private var toolOutputTruncated = false
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
              "Unsupported Cursor request: ${event.method}\n${event.payload}\n",
            )
        }
      },
      onCard = {},
      record = diagnostics::warning,
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
        if (buffer.isNotEmpty()) protocol.observe(buffer.toString())
        buffer.setLength(0)
      }
      if (protocol.outcome() != null) return
      if (buffer.length + part.value.length > MAX_FRAME) {
        protocol.stop("Cursor sent an oversized ACP frame.")
        return
      }
      buffer.append(part.value)
    }
  }

  override fun pendingWait(): ApprovalPendingWait? = protocol.pendingWait()

  override fun enforceOpening() = protocol.enforceOpening()

  override fun finish() {
    protocol.close()
    writer?.close()
    freezeCapture()
  }

  override fun outcome(): AgentRunSessionOutcome? =
    protocol.outcome()?.let {
      AgentRunSessionOutcome(it.text, it.model, it.sessionId, it.failure == null, it.failure)
    }

  override fun capture(): AgentRunSessionCapture = freezeCapture()

  private fun toolUpdate(update: JsonObject) {
    val record = "Cursor tool update: $update\n"
    val retained =
      synchronized(captureLock) {
        when {
          captureFrozen -> null
          toolOutputTruncated || deliveryFailed -> null
          toolOutput.length + record.length > MAX_FRAME -> {
            toolOutputTruncated = true
            null
          }
          else -> {
            toolOutput.append(record)
            record
          }
        }
      }
    if (retained == null) {
      val truncated = synchronized(captureLock) { toolOutputTruncated }
      if (truncated) {
        diagnostics.warning("seam=cursor_acp outcome=tool_capture_truncated")
        protocol.stop("Cursor tool output exceeded the capture limit.")
      }
      return
    }
    deliver(AgentRunOutputStream.STDERR, retained, "tool_output")
  }

  private fun deliver(
    stream: AgentRunOutputStream,
    value: String,
    seam: String,
  ) {
    catchingNonCancellation { output.write(stream, value) }
      .onFailure {
        synchronized(captureLock) { deliveryFailed = true }
        diagnostics.warning("seam=cursor_acp outcome=${seam}_delivery_failed")
        protocol.stop("Cursor $seam could not reach the phase output sink.")
      }
  }

  private fun freezeCapture(): AgentRunSessionCapture =
    synchronized(captureLock) {
      frozenCapture ?: AgentRunSessionCapture(
        text = protocol.textSnapshot(),
        complete = !deliveryFailed && !toolOutputTruncated && !protocol.textCaptureTruncated(),
        truncated = toolOutputTruncated || protocol.textCaptureTruncated(),
        deliveryFailed = deliveryFailed,
        providerDiagnostics = toolOutput.toString().takeIf(String::isNotBlank),
      ).also {
        captureFrozen = true
        frozenCapture = it
      }
    }

  private companion object {
    const val MAX_FRAME = 4_000_000
  }
}
