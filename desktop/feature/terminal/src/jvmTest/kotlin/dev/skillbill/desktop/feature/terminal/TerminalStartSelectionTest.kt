package dev.skillbill.desktop.feature.terminal

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalStartSelectionTest {
  private val work = SelectedRoot.Work(Path.of("/work/api"))
  private val chatRepo = Path.of("/work/skill-bill")
  private val otherChatRepo = Path.of("/work/web")

  @Test
  fun `a chat repository wins over the earlier work item while the New task screen shows`() {
    val selection = TerminalStartSelection()
    selection.itemSelected(work)

    selection.chatRepositorySelected(chatRepo)

    assertEquals(SelectedRoot.Chat(chatRepo), selection.current)
  }

  @Test
  fun `switching chats moves the start to the newly selected chat's repository`() {
    val selection = TerminalStartSelection()
    selection.chatRepositorySelected(chatRepo)

    selection.chatRepositorySelected(otherChatRepo)

    assertEquals(SelectedRoot.Chat(otherChatRepo), selection.current)
  }

  @Test
  fun `leaving the New task screen gives the start back to the selection beneath it`() {
    val selection = TerminalStartSelection()
    selection.specProjectSelected(Path.of("/work/specs"))
    selection.chatRepositorySelected(chatRepo)

    selection.chatRepositorySelected(null)

    assertEquals(SelectedRoot.Spec(Path.of("/work/specs")), selection.current)
  }

  @Test
  fun `a spec project picked while a chat is selected is the latest selection`() {
    val selection = TerminalStartSelection()
    selection.chatRepositorySelected(chatRepo)

    selection.specProjectSelected(Path.of("/work/specs"))

    assertEquals(SelectedRoot.Spec(Path.of("/work/specs")), selection.current)
  }

  @Test
  fun `clearing the work item keeps the chat repository`() {
    val selection = TerminalStartSelection()
    selection.itemSelected(work)
    selection.chatRepositorySelected(chatRepo)

    selection.itemSelected(null)
    assertEquals(SelectedRoot.Chat(chatRepo), selection.current)

    selection.chatRepositorySelected(null)
    assertEquals(SelectedRoot.None, selection.current)
  }

  @Test
  fun `clearing the work item keeps a spec project selection`() {
    val selection = TerminalStartSelection()
    selection.specProjectSelected(Path.of("/work/specs"))

    selection.itemSelected(null)

    assertEquals(SelectedRoot.Spec(Path.of("/work/specs")), selection.current)
  }
}
