package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class ChatStoreTest {
  private val host = HostEnvironment(emptyMap(), createTempDirectory("chat-home").toString(), "")

  private fun entry(session: String, at: Long, execution: String = "wf-1", operator: Boolean = true) = ChatHistoryEntry(
    session, execution, ChatAgent("codex", "gpt-5", "high", "/home/operator/.codex-api"), Instant.fromEpochMilliseconds(at), "why blocked?",
    listOfNotNull(ChatHistoryLine(ChatHistoryRole.Operator, "why blocked?").takeIf { operator }, ChatHistoryLine(ChatHistoryRole.Agent, "CI failed.")),
  )

  // A restart reads the files back, so a write the reader cannot decode, or a resume handle that clobbers the lines, loses the chat.
  @Test
  fun `a fresh store lists one execution's operator chats newest first with their resume handle kept apart`() {
    val writer = ChatStore(host, RecordingDiagnostics(), Clock.System)
    writer.saveChat(entry("old", 1))
    writer.saveAgentSession("new", "thread-9")
    writer.saveChat(entry("new", 2))
    writer.saveChat(entry("opening-only", 3, operator = false))
    writer.saveChat(entry("other", 4, execution = "wf-2"))

    val reader = ChatStore(host, RecordingDiagnostics(), Clock.System)

    assertEquals(listOf(entry("new", 2), entry("old", 1)), reader.savedChats("wf-1"))
    assertEquals("thread-9", reader.agentSession("new"))
  }

  @Test
  fun `cursor resume binding survives restart and remains paired with its provider session`() {
    val writer = ChatStore(host, RecordingDiagnostics(), Clock.System)
    val binding = CursorSessionBinding("/profiles/cursor-work", "/work/project")
    assertTrue(writer.saveCursorSession("cursor-chat", "cursor-session", binding))
    assertTrue(writer.saveAgentSession("legacy-chat", "legacy-session"))

    val reader = ChatStore(host, RecordingDiagnostics(), Clock.System)

    assertEquals("cursor-session", reader.agentSession("cursor-chat"))
    assertEquals(binding, reader.cursorSessionBinding("cursor-chat"))
    assertEquals("legacy-session", reader.agentSession("legacy-chat"))
    assertEquals(null, reader.cursorSessionBinding("legacy-chat"))
  }

  // Every chat open saves a file, so without a cap the directory grows for the life of the machine.
  @Test
  fun `saving keeps only the newest twenty chats of an execution`() {
    val store = ChatStore(host, RecordingDiagnostics(), Clock.System)

    (1L..22L).forEach { store.saveChat(entry("s$it", it)) }

    assertEquals((22L downTo 3L).map { "s$it" }, store.savedChats("wf-1").map { it.sessionId })
  }

  // Sweeping a draft as an orphan silently loses the operator's conversation after a week away.
  @Test
  fun `a new-task draft outlives the orphan sweep, is listed and goes when deleted`() {
    val store = ChatStore(host, RecordingDiagnostics(), Clock.System)
    val draft = entry("draft", 1, execution = "unused").copy(executionId = null, newTask = ChatNewTaskContext("/work/app", "main"))
    store.saveChat(draft)
    File(host.userHome, "$CHAT_STORE_PATH/draft.json").setLastModified(1)

    store.savedChats("wf-1")

    assertEquals(listOf(draft), store.newTaskDrafts())
    store.delete("draft")
    assertEquals(emptyList(), store.newTaskDrafts())
  }

  // Capping before dropping unreadable drafts would let an operator-less draft take a slot and hide a real one.
  @Test
  fun `new-task drafts list every repository newest first and the cap skips drafts without an operator line`() {
    val store = ChatStore(host, RecordingDiagnostics(), Clock.System)
    fun draft(session: String, at: Long, root: String, operator: Boolean = true) =
      entry(session, at, operator = operator).copy(executionId = null, newTask = ChatNewTaskContext(root, "main"))
    val app = draft("app", 1, "/work/app")
    val other = draft("other", 2, "/work/other")
    store.saveChat(app)
    store.saveChat(other)
    store.saveChat(draft("silent", 3, "/work/app", operator = false))
    store.saveChat(entry("execution", 4))

    assertEquals(listOf(other, app), store.newTaskDrafts())
    assertEquals(listOf(other), store.newTaskDrafts(limit = 1))
  }

  @Test
  fun `saved chat lines retain suggested actions and selected operator actions`() {
    val diagnostics = RecordingDiagnostics()
    val store = ChatStore(host, diagnostics, Clock.System)
    val actions = NewTaskActionKind.entries.mapIndexed { index, kind ->
      ChatSuggestedAction(
        "Start ${kind.wire}",
        "Run ${kind.wire}",
        if (index % 2 == 0) ChatPermissionMode.ReadOnly else ChatPermissionMode.Act,
        kind,
        title = "Add dark mode setting".takeIf { kind == NewTaskActionKind.Goal },
      )
    }
    val selected = ChatSuggestedAction("Inspect", "Check the plan", ChatPermissionMode.Act)
    val saved = entry("actions", 9).copy(lines = listOf(
      ChatHistoryLine(ChatHistoryRole.Operator, selected.instruction, selectedAction = selected),
      ChatHistoryLine(ChatHistoryRole.Agent, "", actions = actions),
      ChatHistoryLine(ChatHistoryRole.Agent, "Finished reply", actions = actions.reversed()),
    ))

    assertTrue(store.saveChat(saved))

    val reopened = ChatStore(host, diagnostics, Clock.System).savedChats("wf-1").single()
    assertEquals(saved.lines, reopened.lines)
    assertTrue(diagnostics.categories.isEmpty())
  }

  @Test
  fun `suggested action target and branch round-trip through the store`() {
    val diagnostics = RecordingDiagnostics()
    val store = ChatStore(host, diagnostics, Clock.System)
    val action = ChatSuggestedAction(
      "Run operation:verify",
      "Verify the branch.",
      ChatPermissionMode.ReadOnly,
      NewTaskActionKind.Verify,
      target = "SKILL-9",
      branch = "feat/fp-5175",
    )
    val saved = entry("target", 11).copy(
      lines = listOf(
        ChatHistoryLine(ChatHistoryRole.Operator, "watch this PR"),
        ChatHistoryLine(ChatHistoryRole.Agent, "", actions = listOf(action)),
      ),
    )
    assertTrue(store.saveChat(saved))
    val reopened = ChatStore(host, diagnostics, Clock.System).savedChats("wf-1").single()
    assertEquals(listOf(action), reopened.lines.single { it.role == ChatHistoryRole.Agent }.actions)
    assertTrue(diagnostics.categories.isEmpty())
  }

  @Test
  fun `legacy action lines without target load with null target`() {
    val diagnostics = RecordingDiagnostics()
    val file = File(host.userHome, "$CHAT_STORE_PATH/legacy-no-target.json")
    file.parentFile.mkdirs()
    file.writeText(
      """{"session_id":"legacy-no-target","execution_id":"wf-1","agent_id":"codex","updated_at_ms":1,"lines":[{"role":"Operator","text":"review this branch"},{"role":"Agent","text":"done","actions":[{"label":"Run phase:review","instruction":"Review branch.","mode":"ReadOnly","start":"review"}]}]}""",
    )
    val entry = ChatStore(host, diagnostics, Clock.System).savedChats("wf-1").single()
    assertEquals(
      ChatSuggestedAction("Run phase:review", "Review branch.", ChatPermissionMode.ReadOnly, NewTaskActionKind.Review),
      entry.lines.single { it.role == ChatHistoryRole.Agent }.actions.single(),
    )
    assertTrue(diagnostics.categories.isEmpty())
  }

  @Test
  fun `old chat lines without actions restore empty action metadata and malformed actions are recorded`() {
    val diagnostics = RecordingDiagnostics()
    val file = File(host.userHome, "$CHAT_STORE_PATH/legacy.json")
    file.parentFile.mkdirs()
    file.writeText(
      """{"session_id":"legacy","execution_id":"wf-1","agent_id":"codex","updated_at_ms":1,"lines":[{"role":"Operator","text":"continue"},{"role":"Agent","text":"done","actions":[{"label":"unsafe","instruction":"run","mode":"unknown","start":"made-up"}]}]}""",
    )

    val entry = ChatStore(host, diagnostics, Clock.System).savedChats("wf-1").single()

    assertEquals(ChatHistoryLine(ChatHistoryRole.Operator, "continue"), entry.lines[0])
    assertEquals(ChatHistoryLine(ChatHistoryRole.Agent, "done"), entry.lines[1])
    assertEquals(listOf("chat.store_malformed_action"), diagnostics.categories)
  }

  @Test
  fun `pending new-task turn index counts action-only replies`() {
    val store = ChatStore(host, RecordingDiagnostics(), Clock.System)
    val draft = entry("pending", 10).copy(
      executionId = null,
      newTask = ChatNewTaskContext("/work/app", "main", pendingTurnLineIndex = 2),
      lines = listOf(
        ChatHistoryLine(ChatHistoryRole.Operator, "make a plan"),
        ChatHistoryLine(ChatHistoryRole.Agent, "", actions = listOf(ChatSuggestedAction("Review", "Review", ChatPermissionMode.ReadOnly))),
        ChatHistoryLine(ChatHistoryRole.Operator, "continue"),
      ),
    )
    store.saveChat(draft)

    assertEquals(listOf(ChatExchange("make a plan", "")), store.newTaskHistory("pending"))
  }
}
