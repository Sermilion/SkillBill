package skillbill.infrastructure.launcher.agentrun

import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunApprovalRequirement
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JunieApprovalRefusalTest {
  @Test
  fun `Junie refuses both card required command routes before executable lookup or spawn`() {
    for (continuation in listOf(null, goalContinuationContext())) {
      for (available in listOf(true, false)) {
        val runner = RecordingAgentRunProcessRunner()
        val adapter =
          ProcessAgentRunAdapter(
            SupportedAgent.JUNIE,
            JunieAgentRunCommandBuilder(),
            runner,
            if (available) ALL_EXECUTABLES_AVAILABLE else executablesAvailable(),
            NoopRuntimeDiagnostics,
            LAUNCH_TEST_CLOCK,
          )
        val outcome =
          assertIs<UnsupportedAgentRunLaunch>(
            adapter.launch(
              skillRunRequest(
                goalContinuation = continuation,
              ).copy(approval = AgentRunApprovalRequirement.Owned("run")),
            ),
          )
        assertContains(outcome.reason, "Junie ACP exists")
        assertContains(outcome.reason, "unverified")
        assertTrue(runner.requests.isEmpty())
      }
    }
  }
}
