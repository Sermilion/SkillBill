package dev.skillbill.desktop.feature.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TerminalTabsTest {
  @Test
  fun `default names are numbered only while duplicated and custom names never are`() {
    var state = TerminalTabsState()
      .withTab(TerminalTab(1, "/work/skill-bill", "skill-bill"))
      .withTab(TerminalTab(2, "/work/skill-bill", "skill-bill"))
      .withTab(TerminalTab(3, "/home/me", "~"))
    assertEquals(listOf("skill-bill 1", "skill-bill 2", "~"), labels(state))

    state = state.renamed(2, "api")
    assertEquals(listOf("skill-bill", "api", "~"), labels(state))

    state = state.renamed(2, "   ")
    assertEquals(listOf("skill-bill 1", "skill-bill 2", "~"), labels(state))

    state = state.without(setOf(1))
    assertEquals(listOf("skill-bill", "~"), labels(state))

    state = state.without(setOf(2, 3))
    assertFalse(state.paneOpen)
  }

  @Test
  fun `closing the active tab activates the tab before it`() {
    val state = TerminalTabsState()
      .withTab(TerminalTab(1, "/a", "a"))
      .withTab(TerminalTab(2, "/b", "b"))
      .withTab(TerminalTab(3, "/c", "c"))
      .activated(2)
    assertEquals(1, state.without(setOf(2)).activeId)
    assertEquals(2, state.activated(3).without(setOf(3)).activeId)
    assertEquals(2, state.activated(1).without(setOf(1)).activeId)
  }

  @Test
  fun `a linked worktree is named after its main checkout`() {
    val name = terminalProjectName("/work/wt/feature/src", "/home/me", { false }) { git ->
      if (git == "/work/wt/feature/.git") "gitdir: /work/skill-bill/.git/worktrees/feature\n" else null
    }
    assertEquals("skill-bill", name)
  }

  private fun labels(state: TerminalTabsState): List<String> = terminalTabLabels(state.tabs).let { map -> state.tabs.map { map.getValue(it.id) } }
}
