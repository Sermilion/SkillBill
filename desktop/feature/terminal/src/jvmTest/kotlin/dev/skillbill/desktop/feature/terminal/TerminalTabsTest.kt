package dev.skillbill.desktop.feature.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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
  fun `opening the pane for a run shows the tab that started in its checkout`() {
    val hidden = TerminalTabsState()
      .withTab(TerminalTab(1, "/work/api", "api"))
      .withTab(TerminalTab(2, "/home/me", "~"))
      .copy(paneOpen = false)

    val shown = hidden.shownIn("/work/api")

    assertEquals(1L, shown?.activeId)
    assertEquals(true, shown?.paneOpen)
  }

  @Test
  fun `opening the pane keeps the active tab when it started in the selected checkout`() {
    val hidden = TerminalTabsState()
      .withTab(TerminalTab(1, "/work/api", "api"))
      .withTab(TerminalTab(2, "/work/api", "api"))
      .withTab(TerminalTab(3, "/home/me", "~"))
      .activated(1)
      .copy(paneOpen = false)

    assertEquals(1L, hidden.shownIn("/work/api")?.activeId)
    assertEquals(2L, hidden.activated(3).shownIn("/work/api")?.activeId)
  }

  @Test
  fun `opening the pane for a run without a matching tab asks for a new tab`() {
    val hidden = TerminalTabsState().withTab(TerminalTab(1, "/home/me", "~")).copy(paneOpen = false)

    assertNull(hidden.shownIn("/work/goal-worktree"))
    assertNull(TerminalTabsState().shownIn("/work/api"))
    assertNull(TerminalTabsState().shownIn(null))
  }

  @Test
  fun `opening the pane with nothing selected shows the tabs as they were`() {
    val hidden = TerminalTabsState()
      .withTab(TerminalTab(1, "/work/api", "api"))
      .withTab(TerminalTab(2, "/home/me", "~"))
      .activated(1)
      .copy(paneOpen = false)

    assertEquals(hidden.copy(paneOpen = true), hidden.shownIn(null))
  }

  @Test
  fun `a linked worktree is named after its main checkout`() {
    val name = terminalProjectName("/work/wt/feature/src", "/home/me", { false }) { git ->
      if (git == "/work/wt/feature/.git") "gitdir: /work/skill-bill/.git/worktrees/feature\n" else null
    }
    assertEquals("skill-bill", name)
  }

  @Test
  fun `a Windows checkout path uses its final directory name`() {
    val name = terminalProjectName("C:\\work\\project\\src", "C:\\Users\\me", { it == "C:\\work\\project\\.git" }) { null }

    assertEquals("project", name)
  }

  private fun labels(state: TerminalTabsState): List<String> = terminalTabLabels(state.tabs).let { map -> state.tabs.map { map.getValue(it.id) } }
}
