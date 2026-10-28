package dev.skillbill.desktop.core.designsystem.component

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentInitialsTest {
  @Test
  fun `known agents retain their prototype initials`() {
    assertEquals("Cl", agentInitials("claude"))
    assertEquals("Cx", agentInitials("codex"))
    assertEquals("Cu", agentInitials("cursor"))
    assertEquals("Ju", agentInitials("junie"))
    assertEquals("Ge", agentInitials("gemini"))
  }
}
