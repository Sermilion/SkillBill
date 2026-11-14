package dev.skillbill.desktop.core.designsystem.component

import kotlin.test.Test
import kotlin.test.assertEquals
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

class AgentInitialsTest {
  @Test
  fun `supported agents keep their labels and unknown ids use the neutral fallback`() {
    assertEquals("Cl", agentInitials("claude"))
    assertEquals("Cx", agentInitials("codex"))
    assertEquals("Cu", agentInitials("cursor"))
    assertEquals("Claude", agentDisplayName("claude"))
    assertEquals("Codex", agentDisplayName("codex"))
    assertEquals("Cursor", agentDisplayName("cursor"))
    listOf("unavailable", "retired-agent", "").forEach { agentId ->
      assertEquals("?", agentInitials(agentId))
      assertEquals("Unavailable agent", agentDisplayName(agentId))
      assertEquals(1, agentDataSlot(agentId))
    }
    assertEquals("Unavailable agent", agentDisplayName(null))
    assertEquals(4, agentDataSlot("claude"))
    assertEquals(2, agentDataSlot("codex"))
    assertEquals(6, agentDataSlot("cursor"))
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `avatar exposes the neutral accessible name for unsupported ids`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Avatar("retired-agent")
      }
    }

    onNodeWithContentDescription("Unavailable agent").assertExists()
  }
}
