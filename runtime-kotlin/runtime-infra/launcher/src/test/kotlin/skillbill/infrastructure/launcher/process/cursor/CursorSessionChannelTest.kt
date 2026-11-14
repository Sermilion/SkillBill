package skillbill.infrastructure.launcher.process.cursor

import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionCapture
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.approval.model.ApprovalDecisionResult
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalPendingWait
import skillbill.ports.approval.model.ApprovalProviderClearance
import skillbill.ports.approval.model.ApprovalRegistrationCommand
import skillbill.ports.approval.model.ApprovalRegistrationResult
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CursorSessionChannelTest {
  @Test
  fun `terminal frame is consumed before approval cleanup observes capture`() {
    val channel =
      CursorSessionChannel(
        launch = CursorAcpLaunch("/checkout", "prompt", null, null, provider = AcpProvider.OpenCode),
        clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        output = AgentRunOutputSink { _, _ -> },
        diagnostics = NoopRuntimeDiagnostics,
      )
    var captureAtRelease: AgentRunSessionCapture? = null
    channel.bind(CaptureOnReleaseOwner { captureAtRelease = channel.capture() })
    try {
      assertTrue(channel.attach(SessionProcess()))
      channel.observe("""{"jsonrpc":"2.0","id":2,"result":{"protocolVersion":1,"agentCapabilities":{}}}""" + "\n")
      channel.observe(
        """{"jsonrpc":"2.0","id":3,"result":{"sessionId":"s","configOptions":[{"id":"mode",""" +
          """"category":"mode","currentValue":"primary","options":[{"value":"primary"}]}]}}""" + "\n",
      )
      channel.observe(
        """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s",""" +
          """"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text",""" +
          """"text":"answer"}}}}""" + "\n",
      )
      channel.observe("""{"jsonrpc":"2.0","id":4,"result":{"stopReason":"end_turn"}}""" + "\n")

      val captured = assertNotNull(captureAtRelease)
      assertTrue(captured.complete)
      assertEquals("answer", captured.text, "session outcome: ${channel.outcome()}")
      assertTrue(assertNotNull(channel.outcome()).completed)
    } finally {
      channel.finish()
    }
  }

  @Test
  fun `tool output beyond the former capture limit keeps the session alive`() {
    val channel =
      CursorSessionChannel(
        launch = CursorAcpLaunch("/checkout", "prompt", null, null, provider = AcpProvider.OpenCode),
        clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        output = AgentRunOutputSink { _, _ -> },
        diagnostics = NoopRuntimeDiagnostics,
      )
    try {
      assertTrue(channel.attach(SessionProcess()))
      channel.observe("""{"jsonrpc":"2.0","id":2,"result":{"protocolVersion":1,"agentCapabilities":{}}}""" + "\n")
      channel.observe(
        """{"jsonrpc":"2.0","id":3,"result":{"sessionId":"s","configOptions":[{"id":"mode",""" +
          """"category":"mode","currentValue":"primary","options":[{"value":"primary"}]}]}}""" + "\n",
      )
      val payload = "x".repeat(5_000_000)
      channel.observe(
        """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s",""" +
          """"update":{"sessionUpdate":"tool_call","toolCallId":"t","title":"Read",""" +
          """"status":"completed","rawOutput":"$payload"}}}""" + "\n",
      )
      channel.observe(
        """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s",""" +
          """"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text",""" +
          """"text":"answer"}}}}""" + "\n",
      )
      channel.observe("""{"jsonrpc":"2.0","id":4,"result":{"stopReason":"end_turn"}}""" + "\n")

      val outcome = assertNotNull(channel.outcome())
      assertTrue(outcome.completed, outcome.failure)
      assertEquals("answer", outcome.text)
    } finally {
      channel.finish()
    }
  }

  @Test
  fun `partial ACP frame at process end marks session capture incomplete`() {
    val channel =
      CursorSessionChannel(
        launch = CursorAcpLaunch("/checkout", "prompt", null, null, provider = AcpProvider.OpenCode),
        clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        output = AgentRunOutputSink { _, _ -> },
        diagnostics = NoopRuntimeDiagnostics,
      )
    try {
      channel.attach(SessionProcess())
      channel.observe("{\"jsonrpc\":\"2.0\"")

      assertFalse(channel.capture().complete)
    } finally {
      channel.finish()
    }
  }
}

private class CaptureOnReleaseOwner(private val onRelease: () -> Unit) : ApprovalOwnerSession {
  override val key = ApprovalOwnerKey("repo", "run", "generation")

  override fun register(command: ApprovalRegistrationCommand): ApprovalRegistrationResult =
    error(
      "No permission expected",
    )

  override fun resolveByProvider(
    approvalId: String,
    providerRequestId: ApprovalProviderRequestId,
    disposition: ApprovalProviderDisposition,
  ): ApprovalDecisionResult = error("No permission expected")

  override fun clearByProvider(
    approvalId: String,
    providerRequestId: ApprovalProviderRequestId,
    clearance: ApprovalProviderClearance,
  ): ApprovalDecisionResult = error("No permission expected")

  override fun pendingWait(): ApprovalPendingWait? = null

  override fun release() = onRelease()
}

private class SessionProcess : Process() {
  override fun getOutputStream() = ByteArrayOutputStream()

  override fun getInputStream(): InputStream = InputStream.nullInputStream()

  override fun getErrorStream(): InputStream = InputStream.nullInputStream()

  override fun waitFor(): Int = 0

  override fun exitValue(): Int = 0

  override fun destroy() = Unit
}
