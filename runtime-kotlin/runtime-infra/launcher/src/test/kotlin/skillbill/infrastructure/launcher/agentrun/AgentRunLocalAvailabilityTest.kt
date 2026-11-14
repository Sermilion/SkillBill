package skillbill.infrastructure.launcher.agentrun

import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.agentrun.model.AgentRunLocalAvailability
import skillbill.ports.agentrun.model.AgentRunLocalAvailabilityRequest
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentRunLocalAvailabilityTest {
  private fun launcher(lookup: ExecutableLookup) =
    FileSystemAgentRunLauncher(
      RecordingAgentRunProcessRunner(),
      lookup,
      diagnostics = NoopRuntimeDiagnostics,
      clock = LAUNCH_TEST_CLOCK,
    )

  @Test
  fun `registered adapter with an executable on PATH reports the found executable`() {
    val read =
      launcher(ExecutableLookup { it == "claude" })
        .queryLocalAvailability(AgentRunLocalAvailabilityRequest(SupportedAgent.CLAUDE))

    val registered = assertIs<AgentRunLocalAvailability.Registered>(read)
    assertEquals("claude", registered.executable)
  }

  @Test
  fun `cursor availability selects any declared executable alternative`() {
    val read =
      launcher(ExecutableLookup { it == "cursor-agent" })
        .queryLocalAvailability(AgentRunLocalAvailabilityRequest(SupportedAgent.CURSOR))

    val registered = assertIs<AgentRunLocalAvailability.Registered>(read)
    assertEquals("cursor-agent", registered.executable)
  }

  @Test
  fun `registered adapter with no executable reports missing with an install reason`() {
    val read =
      launcher(ExecutableLookup { false })
        .queryLocalAvailability(AgentRunLocalAvailabilityRequest(SupportedAgent.CODEX))

    val missing = assertIs<AgentRunLocalAvailability.ExecutableMissing>(read)
    assertTrue(missing.reason.contains("codex"), missing.reason)
  }

  @Test
  fun `a failing executable read reports unavailable without claiming missing`() {
    val read =
      launcher(ExecutableLookup { throw IOException("PATH enumeration refused") })
        .queryLocalAvailability(AgentRunLocalAvailabilityRequest(SupportedAgent.JUNIE))

    val unavailable = assertIs<AgentRunLocalAvailability.Unavailable>(read)
    assertTrue(unavailable.reason.isNotBlank())
  }
}
