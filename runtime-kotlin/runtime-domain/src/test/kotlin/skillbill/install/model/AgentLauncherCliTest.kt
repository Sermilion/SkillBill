package skillbill.install.model

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentLauncherCliTest {
  private val nothingInstalled: (String) -> Boolean = { false }
  private val everythingInstalled: (String) -> Boolean = { true }

  @Test
  fun `every install agent declares a launcher CLI`() {
    assertEquals(SupportedAgent.entries.toSet(), AGENT_LAUNCHER_CLIS.keys)
  }

  @Test
  fun `an agent whose CLI is absent yields an actionable reason`() {
    val reason = assertNotNull(unavailableAgentLauncherReason("cursor", nothingInstalled))

    assertContains(reason, "'agent' is not on PATH")
    assertContains(reason, "curl https://cursor.com/install")
    assertContains(reason, "relaunch with a different --agent")
  }

  @Test
  fun `any declared executable satisfies availability`() {
    assertNull(unavailableAgentLauncherReason("cursor", { it == "cursor-agent" }))
    assertNull(unavailableAgentLauncherReason("cursor", { it == "agent" }))
  }

  @Test
  fun `agent ids are normalized and unknown or launcherless ids are ignored`() {
    assertNotNull(unavailableAgentLauncherReason("  CURSOR ", nothingInstalled))
    assertNull(unavailableAgentLauncherReason(null, nothingInstalled))
    assertNull(unavailableAgentLauncherReason("", nothingInstalled))
    assertNull(unavailableAgentLauncherReason("not-an-agent", nothingInstalled))
  }

  @Test
  fun `copilot is not a supported install agent`() {
    val error =
      assertFailsWith<IllegalArgumentException> {
        SupportedAgent.fromId("copilot")
      }
    assertContains(error.message.orEmpty(), "Unknown agent 'copilot'")
  }

  @Test
  fun `an installed CLI produces no reason`() {
    SupportedAgent.entries.forEach { agent ->
      assertNull(unavailableAgentLauncherReason(agent.id, everythingInstalled))
    }
  }
}
