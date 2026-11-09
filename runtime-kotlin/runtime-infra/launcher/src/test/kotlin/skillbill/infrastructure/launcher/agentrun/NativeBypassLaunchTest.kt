package skillbill.infrastructure.launcher.agentrun

import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunApprovalRequirement
import skillbill.ports.approval.ApprovalOwnerAuthority
import skillbill.ports.approval.ApprovalOwnerEffects
import skillbill.ports.approval.model.ApprovalOwnerOpenRequest
import skillbill.ports.approval.model.ApprovalOwnerOpenResult
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeBypassLaunchTest {
  @Test
  fun `runtime bypass launches cannot intercept provider requests or consult the approval broker`() {
    val authority =
      object : ApprovalOwnerAuthority {
        override fun open(
          request: ApprovalOwnerOpenRequest,
          effects: ApprovalOwnerEffects,
        ): ApprovalOwnerOpenResult = error("A bypass launch must not consult operator approvals")
      }
    for (agent in listOf(SupportedAgent.CODEX, SupportedAgent.CLAUDE, SupportedAgent.CURSOR, SupportedAgent.JUNIE)) {
      val runner = RecordingAgentRunProcessRunner()
      val adapters =
        headlessAgentRunAdapters(
          runner,
          ALL_EXECUTABLES_AVAILABLE,
          workerBinding = AgentRunWorkerBinding(approvals = AgentRunApprovalOwners(authority, LAUNCH_TEST_CLOCK)),
          diagnostics = NoopRuntimeDiagnostics,
          clock = LAUNCH_TEST_CLOCK,
        )
      adapters.getValue(agent).launch(request().copy(approval = AgentRunApprovalRequirement.OutsideApproval))
      val process = runner.requests.single()
      assertNull(process.launch.session, agent.id)
      assertNull(process.approval.channel, agent.id)
      assertFalse("acp" in process.launch.command || "--acp" in process.launch.command, agent.id)
      assertFalse("app-server" in process.launch.command, agent.id)
      when (agent) {
        SupportedAgent.CODEX -> assertTrue("--dangerously-bypass-approvals-and-sandbox" in process.launch.command)
        SupportedAgent.CLAUDE -> assertTrue("--dangerously-skip-permissions" in process.launch.command)
        SupportedAgent.CURSOR -> assertTrue("--force" in process.launch.command && "--print" in process.launch.command)
        SupportedAgent.JUNIE ->
          assertTrue(
            "--output-format" in process.launch.command && "text" in process.launch.command,
          )
      }
    }
  }

  @Test
  fun `card-owned chats retain their provider approval transports`() {
    for (builder in listOf(
      ClaudeAgentRunCommandBuilder(),
      CodexAgentRunCommandBuilder(),
      CursorAgentRunCommandBuilder(),
      JunieAgentRunCommandBuilder(),
    )) {
      val command = builder.build(request().copy(approval = AgentRunApprovalRequirement.Owned("chat-run")))
      assertEquals(builder.approvalProtocol, command.approvalProtocol)
    }
  }
}
