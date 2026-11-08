package skillbill.infrastructure.launcher.agentrun

import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.install.model.SupportedAgent
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClaudeIncompleteCaptureTest {
  @Test
  fun `an incomplete capture that lost the result event reports truncated output instead of no output`() {
    val events =
      """
      {"type":"system","subtype":"init"}
      {"type":"assistant","message":{"content":[{"type":"text","text":"Plan written."}]}}
      """.trimIndent()

    val facts =
      ProcessAgentRunAdapter(
        agent = SupportedAgent.CLAUDE,
        commandBuilder = ClaudeAgentRunCommandBuilder(),
        processRunner =
          RecordingAgentRunProcessRunner(
            AgentRunProcessResult(
              exitStatus = 0,
              stdout = events,
              stderr = "",
              timedOut = false,
              interrupted = false,
              spawnFailed = false,
              outputCaptureIncomplete = true,
            ),
          ),
        executableLookup = ALL_EXECUTABLES_AVAILABLE,
        diagnostics = NoopRuntimeDiagnostics,
        clock = LAUNCH_TEST_CLOCK,
      ).launchFacts(request().copy(streamProviderOutput = true))

    assertEquals("", facts.stdout)
    assertTrue(facts.stdoutTruncated)
  }
}
