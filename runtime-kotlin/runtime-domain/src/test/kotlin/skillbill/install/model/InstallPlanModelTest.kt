package skillbill.install.model

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InstallPlanModelTest {
  @Test
  fun `supported install agents are exactly the install contract set`() {
    assertEquals(
      listOf("claude", "codex", "junie", "cursor", "opencode"),
      SupportedAgent.supportedIds,
    )
  }

  @Test
  fun `cursor is a governed install agent and stays runtime eligible`() {
    assertEquals(SupportedAgent.CURSOR, SupportedAgent.fromId("cursor"))
    assertEquals(SupportedAgent.CURSOR, SupportedAgent.fromNormalizedId(" CURSOR "))
  }

  @Test
  fun `cursor invoking-agent markers are session identity not api credentials`() {
    val cursor =
      InvokingAgentContextResolver.INVOKING_AGENT_CONTEXT_SIGNALS.single { signal ->
        signal.agent == SupportedAgent.CURSOR
      }
    assertEquals(listOf("CURSOR_AGENT", "CURSOR_INVOKED_AS"), cursor.markerKeys)
  }

  @Test
  fun `native agent provider ids mirror the install agent order`() {
    assertEquals(
      listOf("claude", "codex", "junie", "cursor"),
      NativeAgentProviderId.entries.map(NativeAgentProviderId::id),
    )
  }

  @Test
  fun `agent ids parse only governed install targets`() {
    SupportedAgent.supportedIds.forEach { id ->
      assertEquals(id, SupportedAgent.fromId(id).id)
    }

    val error =
      assertFailsWith<IllegalArgumentException> {
        SupportedAgent.fromId("not-an-agent")
      }

    assertContains(error.message.orEmpty(), "Unknown agent 'not-an-agent'")
    SupportedAgent.supportedIds.forEach { id ->
      assertContains(error.message.orEmpty(), id)
    }
  }

  @Test
  fun `telemetry levels match cli values`() {
    assertEquals(
      listOf("anonymous", "full", "off"),
      InstallTelemetryLevel.entries.map(InstallTelemetryLevel::id),
    )
  }
}
