package skillbill.infrastructure.launcher.process.cursor

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
  output: AgentRunOutputSink,
  diagnostics: RuntimeDiagnostics,
) : AgentRunSessionChannel {
  private var writer: SerializedStdinWriter? = null
  private val buffer = StringBuilder()
  private var deliveryFailed = false
  private val protocol =
    CursorAcpSession(
      launch,
      clock,
      writeLine = { writer?.write((it + "\n").encodeToByteArray()) == true },
      saveSession = { true },
      onEvent = { event ->
        when (event) {
          is CursorAcpEvent.Text ->
            catchingNonCancellation { output.write(AgentRunOutputStream.STDOUT, event.text) }
              .onFailure { deliveryFailed = true }
          is CursorAcpEvent.Tool ->
            diagnostics.warning(
              "seam=cursor_acp outcome=tool_update status=${event.update.text(CursorAcpKeys.STATUS)}",
            )
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
      if (deliveryFailed) protocol.stop("Cursor assistant output could not reach the phase output sink.")
    }
  }

  override fun pendingWait(): ApprovalPendingWait? = protocol.pendingWait()

  override fun enforceOpening() = protocol.enforceOpening()

  override fun finish() {
    protocol.close()
    writer?.close()
  }

  override fun outcome(): AgentRunSessionOutcome? =
    protocol.outcome()?.let {
      AgentRunSessionOutcome(it.text, it.model, it.sessionId, it.failure == null, it.failure)
    }

  override fun capture(): AgentRunSessionCapture =
    AgentRunSessionCapture(
      text = protocol.textSnapshot(),
      complete = !deliveryFailed,
      truncated = false,
      deliveryFailed = deliveryFailed,
    )

  private companion object {
    const val MAX_FRAME = 4_000_000
  }
}
