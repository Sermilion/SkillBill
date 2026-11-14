package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ApprovalCard
import dev.skillbill.desktop.core.domain.model.ApprovalCardKey
import dev.skillbill.desktop.core.domain.model.ApprovalCardOption
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatRecoveryReason
import dev.skillbill.desktop.core.domain.model.ChatStoreRead
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class ChatStoreTest {
  private val host = HostEnvironment(emptyMap(), createTempDirectory("chat-home").toString(), "")
  private val fixedClock = object : Clock {
    override fun now(): Instant = Instant.parse("2026-11-13T12:00:00Z")
  }

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

    assertEquals(listOf(entry("new", 2), entry("old", 1)), reader.savedChats("wf-1").entries)
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

  @Test
  fun `resume-only records survive restart and listing without false recovery warnings or lost handles`() {
    val writer = ChatStore(host, RecordingDiagnostics(), fixedClock)
    assertTrue(writer.saveAgentSession("pending", "provider-thread"))
    val binding = CursorSessionBinding("/profiles/cursor", "/work/project")
    assertTrue(writer.saveCursorSession("cursor-pending", "cursor-thread", binding))
    val files = listOf("pending", "cursor-pending").map { File(host.userHome, "$CHAT_STORE_PATH/$it.json") }
    val originals = files.map { it.readText() }
    files.forEach { assertTrue(it.setLastModified(1)) }

    val reader = ChatStore(host, RecordingDiagnostics(), fixedClock)
    assertTrue(reader.newTaskDrafts().recoveries.isEmpty())
    val execution = reader.savedChats("wf-1")
    assertTrue(execution.entries.isEmpty())
    assertTrue(execution.recoveries.isEmpty())
    assertEquals(originals, files.map { it.readText() })
    assertEquals("provider-thread", reader.agentSession("pending"))
    assertEquals("cursor-thread", reader.agentSession("cursor-pending"))
    assertEquals(binding, reader.cursorSessionBinding("cursor-pending"))

    assertTrue(reader.saveChat(entry("pending", 2)))
    assertEquals(listOf(entry("pending", 2)), reader.savedChats("wf-1").entries)
    assertEquals("provider-thread", reader.agentSession("pending"))
  }

  @Test
  fun `unusable conversations and unbound OpenCode handles remain visible beside resume-only records`() {
    val store = ChatStore(host, RecordingDiagnostics(), fixedClock)
    assertTrue(store.saveAgentSession("pending", "provider-thread"))
    val originals = mapOf(
      "missing-agent" to """{"session_id":"missing-agent","agent_session":"thread","lines":[{"role":"Operator","text":"keep my question"}]}""",
      "unbound" to """{"session_id":"unbound","agent_id":"opencode","agent_session":"oc-thread","lines":[]}""",
      "unreadable" to "{",
    )
    originals.forEach { (id, raw) -> File(host.userHome, "$CHAT_STORE_PATH/$id.json").writeText(raw) }
    val draft = entry("empty-draft", 3).copy(executionId = null, lines = emptyList(), newTask = ChatNewTaskContext("/work/app", "main"))
    val execution = entry("empty-execution", 4).copy(lines = emptyList())
    assertTrue(store.saveChat(draft))
    assertTrue(store.saveChat(execution))
    assertTrue(store.saveAgentSession(draft.sessionId, "draft-thread"))
    assertTrue(store.saveAgentSession(execution.sessionId, "execution-thread"))

    val reopened = ChatStore(host, RecordingDiagnostics(), fixedClock)
    val common = mapOf(
      "missing-agent" to ChatRecoveryReason.MissingResumeMetadata,
      "unbound" to ChatRecoveryReason.OpenCodeHandleUnbound,
      "unreadable" to ChatRecoveryReason.UnreadableFile,
    )
    assertEquals(common + (draft.sessionId to ChatRecoveryReason.MissingResumeMetadata),
      reopened.newTaskDrafts().recoveries.associate { it.sessionId to it.reason })
    assertEquals(common + (execution.sessionId to ChatRecoveryReason.MissingResumeMetadata),
      reopened.savedChats("wf-1").recoveries.associate { it.sessionId to it.reason })
    originals.forEach { (id, raw) -> assertEquals(raw, File(host.userHome, "$CHAT_STORE_PATH/$id.json").readText()) }
  }

  // A provider handle without its profile and cwd could silently resume a different OpenCode conversation after a restart.
  @Test
  fun `OpenCode acquisition saves its handle and binding without replacing conversation metadata`() {
    val store = ChatStore(host, RecordingDiagnostics(), Clock.System)
    val saved = entry("opencode-chat", 8).copy(
      agent = ChatAgent("opencode", "anthropic/claude-sonnet-4", "high", "/profiles/oc"),
      lines = listOf(
        ChatHistoryLine(ChatHistoryRole.Operator, "keep this"),
        ChatHistoryLine(ChatHistoryRole.Agent, "saved answer"),
      ),
    )
    store.saveChat(saved)
    val binding = OpenCodeSessionBinding("/profiles/oc", "identity-fingerprint", "/data/oc", "/work/repo")

    assertTrue(store.saveOpenCodeSession("opencode-chat", "provider-handle", binding))

    val reopened = ChatStore(host, RecordingDiagnostics(), Clock.System)
    assertEquals("provider-handle", reopened.agentSession("opencode-chat"))
    assertEquals(binding, reopened.openCodeBinding("opencode-chat"))
    assertEquals(saved.lines, reopened.savedChats("wf-1").entries.single().lines)
    assertEquals("opencode", reopened.savedChats("wf-1").entries.single().agent.agentId)
    assertEquals("/work/repo", reopened.savedChats("wf-1").entries.single().executionCheckoutRoot)
  }

  @Test
  fun `Devin acquisition binds its handle to the saved identity and preserves unknown fields`() {
    val store = ChatStore(host, RecordingDiagnostics(), Clock.System)
    val saved = entry("devin-chat", 8).copy(
      agent = ChatAgent("devin", "devin/swe-1", null, null),
      executionCheckoutRoot = "/work/repo",
      lines = listOf(ChatHistoryLine(ChatHistoryRole.Operator, "continue")),
    )
    assertTrue(store.saveChat(saved))
    val file = File(host.userHome, "$CHAT_STORE_PATH/devin-chat.json")
    file.writeText(file.readText().dropLast(1) + ",\"custom_source\":\"keep\"}")

    assertTrue(store.saveDevinSession("devin-chat", "native-42", saved))
    assertTrue(store.saveDevinSession("devin-chat", "native-42", saved))
    val acquired = file.readText()
    assertFalse(store.saveDevinSession("devin-chat", "different-native", saved))

    val reopened = ChatStore(host, RecordingDiagnostics(), Clock.System)
    assertEquals("native-42", reopened.agentSession("devin-chat"))
    assertEquals("keep", Json.parseToJsonElement(file.readText()).jsonObject.getValue("custom_source").jsonPrimitive.content)
    assertEquals("/work/repo", reopened.savedChats("wf-1").entries.single().executionCheckoutRoot)
    assertEquals(acquired, file.readText())
  }

  // A partial binding is recovery evidence; an incidental chat save must not normalize it away or replace the handle.
  @Test
  fun `a malformed OpenCode binding remains visible and blocks replacement writes`() {
    val diagnostics = RecordingDiagnostics()
    val store = ChatStore(host, diagnostics, Clock.System)
    val file = File(host.userHome, "$CHAT_STORE_PATH/broken-opencode.json")
    file.parentFile.mkdirs()
    val original = """{"session_id":"broken-opencode","execution_id":"wf-1","agent_id":"opencode","updated_at_ms":1,"agent_session":"keep-handle","opencode_profile_identity":"/profile","lines":[{"role":"Operator","text":"saved question"}]}"""
    file.writeText(original)

    val projection = store.savedChats("wf-1")
    assertTrue(projection.entries.isEmpty())
    assertEquals("broken-opencode", projection.recoveries.single().sessionId)
    assertEquals(ChatRecoveryReason.InvalidOpenCodeBinding, projection.recoveries.single().reason)
    assertFalse(store.saveChat(entry("broken-opencode", 2)))
    assertEquals(original, file.readText())
    assertTrue(diagnostics.categories.contains("chat.opencode_binding_unreadable"))
  }

  // Every chat open saves a file, so without a cap the directory grows for the life of the machine.
  @Test
  fun `saving keeps only the newest twenty chats of an execution`() {
    val store = ChatStore(host, RecordingDiagnostics(), Clock.System)

    (1L..22L).forEach { store.saveChat(entry("s$it", it)) }

    assertEquals((22L downTo 3L).map { "s$it" }, store.savedChats("wf-1").entries.map { it.sessionId })
  }

  // Sweeping a draft as an orphan silently loses the operator's conversation after a week away.
  @Test
  fun `a new-task draft outlives the orphan sweep, is listed and goes when deleted`() {
    val store = ChatStore(host, RecordingDiagnostics(), Clock.System)
    val draft = entry("draft", 1, execution = "unused").copy(executionId = null, newTask = ChatNewTaskContext("/work/app", "main"))
    store.saveChat(draft)
    File(host.userHome, "$CHAT_STORE_PATH/draft.json").setLastModified(1)

    store.savedChats("wf-1")

    assertEquals(listOf(draft), store.newTaskDrafts().entries)
    store.delete("draft")
    assertEquals(emptyList(), store.newTaskDrafts().entries)
  }

  @Test
  fun `repository-free drafts persist explicit absence and their effective directory`() {
    val writer = ChatStore(host, RecordingDiagnostics(), Clock.System)
    val draft = entry("home-chat", 4).copy(
      executionId = null,
      newTask = ChatNewTaskContext("/home/operator", null, selectedRepositoryRoot = null),
    )
    assertTrue(writer.saveChat(draft))

    val restored = ChatStore(host, RecordingDiagnostics(), Clock.System).newTaskDrafts().entries.single()

    assertEquals("/home/operator", restored.newTask?.workingDirectory)
    assertEquals(null, restored.newTask?.selectedRepositoryRoot)
    val stored = Json.parseToJsonElement(File(host.userHome, "$CHAT_STORE_PATH/home-chat.json").readText()).jsonObject
    assertEquals("/home/operator", stored.getValue("new_task_working_directory").jsonPrimitive.content)
    assertEquals("null", stored.getValue("new_task_selected_repository_root").toString())
    assertTrue("new_task_repository_root" !in stored)
  }

  @Test
  fun `completing a pending turn clears its stored boundary without dropping unknown fields`() {
    val writer = ChatStore(host, RecordingDiagnostics(), Clock.System)
    val lines = listOf(
      ChatHistoryLine(ChatHistoryRole.Operator, "first"),
      ChatHistoryLine(ChatHistoryRole.Agent, "first reply"),
      ChatHistoryLine(ChatHistoryRole.Operator, "second"),
      ChatHistoryLine(ChatHistoryRole.Agent, "second reply"),
    )
    val pending = entry("pending-chat", 4).copy(
      executionId = null,
      lines = lines,
      newTask = ChatNewTaskContext("/work/app", "main", pendingTurnLineIndex = 2),
    )
    assertTrue(writer.saveChat(pending))
    val file = File(host.userHome, "$CHAT_STORE_PATH/pending-chat.json")
    file.writeText(file.readText().dropLast(1) + ",\"custom_source\":\"kept\"}")
    val completed = pending.copy(newTask = checkNotNull(pending.newTask).copy(baseBranch = null, pendingTurnLineIndex = null))

    assertTrue(writer.saveChat(completed))

    val reader = ChatStore(host, RecordingDiagnostics(), Clock.System)
    assertEquals(completed.newTask, reader.newTaskDrafts().entries.single().newTask)
    assertEquals(lines, reader.newTaskHistoryRead("pending-chat").let { (it as ChatStoreRead.Value).value })
    assertEquals("kept", Json.parseToJsonElement(file.readText()).jsonObject.getValue("custom_source").jsonPrimitive.content)
  }

  @Test
  fun `legacy repository drafts migrate before listing and retain unknown fields`() {
    val file = File(host.userHome, "$CHAT_STORE_PATH/legacy-draft.json").apply {
      parentFile.mkdirs()
      writeText("""{"session_id":"legacy-draft","agent_id":"codex","new_task_repository_root":"/work/old","lines":[{"role":"Operator","text":"continue"}],"custom_source":"kept"}""")
    }

    val restored = ChatStore(host, RecordingDiagnostics(), Clock.System).newTaskDrafts().entries.single()
    val stored = Json.parseToJsonElement(file.readText()).jsonObject

    assertEquals("legacy-draft", restored.sessionId)
    assertEquals("/work/old", restored.newTask?.workingDirectory)
    assertEquals("/work/old", restored.newTask?.selectedRepositoryRoot)
    assertEquals("kept", stored.getValue("custom_source").jsonPrimitive.content)
    assertEquals("1", stored.getValue("new_task_context_version").jsonPrimitive.content)
  }

  @Test
  fun `unsupported draft context is retained and cannot be overwritten or swept`() {
    val diagnostics = RecordingDiagnostics()
    val file = File(host.userHome, "$CHAT_STORE_PATH/unsupported.json").apply {
      parentFile.mkdirs()
      writeText("""{"session_id":"unsupported","agent_id":"codex","new_task_context_version":9,"new_task_working_directory":"/home/operator","new_task_selected_repository_root":null,"lines":[{"role":"Operator","text":"keep me"}]}""")
      setLastModified(1)
    }
    val store = ChatStore(host, diagnostics, Clock.System)

    store.savedChats("wf-1")

    assertFalse(store.saveAgentSession("unsupported", "replacement"))
    assertTrue(file.isFile)
    assertTrue("keep me" in file.readText())
    assertTrue("chat.store_context_invalid" in diagnostics.categories)
  }

  @Test
  fun `malformed chat diagnostics exclude conversation text and preserve the source`() {
    val original = """{"lines":[{"role":"Operator","text":"private account details"}]"""
    val file = File(host.userHome, "$CHAT_STORE_PATH/private-draft.json").apply {
      parentFile.mkdirs()
      writeText(original)
    }

    val result = ChatStore(host, RecordingDiagnostics(), Clock.System).newTaskDraftsRead()

    assertTrue(result.entries.isEmpty())
    val failure = result.failures.single()
    assertEquals(file.absolutePath, failure.sourcePath)
    assertTrue(failure.reason.startsWith("unreadable JSON:"))
    assertFalse("private account details" in failure.reason)
    assertEquals(original, file.readText())
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

    assertEquals(listOf(other, app), store.newTaskDrafts().entries)
    assertEquals(listOf(other), store.newTaskDrafts(limit = 1).entries)
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

    val reopened = ChatStore(host, diagnostics, Clock.System).savedChats("wf-1").entries.single()
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
    val reopened = ChatStore(host, diagnostics, Clock.System).savedChats("wf-1").entries.single()
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
    val entry = ChatStore(host, diagnostics, Clock.System).savedChats("wf-1").entries.single()
    assertEquals(
      ChatSuggestedAction("Run phase:review", "Review branch.", ChatPermissionMode.ReadOnly, NewTaskActionKind.Review),
      entry.lines.single { it.role == ChatHistoryRole.Agent }.actions.single(),
    )
    assertTrue(diagnostics.categories.isEmpty())
  }

  @Test
  fun `unsupported saved agent is normalized without losing its chat or metadata`() {
    val diagnostics = RecordingDiagnostics()
    val clock = object : Clock {
      override fun now() = Instant.fromEpochMilliseconds(10_000)
    }
    val file = File(host.userHome, "$CHAT_STORE_PATH/retired.json")
    file.parentFile.mkdirs()
    file.writeText(
      """{"session_id":"retired","execution_id":"wf-1","agent_id":"retired-agent","model":"kept-model","effort":"high","profile":"/profiles/kept","updated_at_ms":1,"summary":"kept summary","agent_session":"kept-session","lines":[{"role":"Operator","text":"keep these lines"},{"role":"Agent","text":"reply"}]}""",
    )
    assertTrue(file.setLastModified(clock.now().toEpochMilliseconds()))

    val store = ChatStore(host, diagnostics, clock)
    val restored = store.savedChats("wf-1").entries.single()

    assertEquals("unavailable", restored.agent.agentId)
    assertEquals("kept-model", restored.agent.model)
    assertEquals("high", restored.agent.effort)
    assertEquals("/profiles/kept", restored.agent.profile)
    assertEquals("kept summary", restored.summary)
    assertEquals(listOf("keep these lines", "reply"), restored.lines.map { it.text })
    assertEquals(listOf("chat.store_unsupported_agent_normalized"), diagnostics.categories)
    assertTrue(store.saveSummary("retired", "updated summary", Instant.fromEpochMilliseconds(2)))
    assertEquals("unavailable", Json.parseToJsonElement(file.readText()).jsonObject.getValue("agent_id").jsonPrimitive.content)
    assertEquals("unavailable", store.savedChats("wf-1").entries.single().agent.agentId)
    assertEquals(listOf("chat.store_unsupported_agent_normalized", "chat.store_unsupported_agent_normalized"), diagnostics.categories)
  }

  @Test
  fun `old chat lines without actions restore empty action metadata and malformed actions are recorded`() {
    val diagnostics = RecordingDiagnostics()
    val file = File(host.userHome, "$CHAT_STORE_PATH/legacy.json")
    file.parentFile.mkdirs()
    file.writeText(
      """{"session_id":"legacy","execution_id":"wf-1","agent_id":"codex","updated_at_ms":1,"lines":[{"role":"Operator","text":"continue"},{"role":"Agent","text":"done","actions":[{"label":"unsafe","instruction":"run","mode":"unknown","start":"made-up"}]}]}""",
    )

    val entry = ChatStore(host, diagnostics, Clock.System).savedChats("wf-1").entries.single()

    assertEquals(ChatHistoryLine(ChatHistoryRole.Operator, "continue"), entry.lines[0])
    assertEquals(ChatHistoryLine(ChatHistoryRole.Agent, "done"), entry.lines[1])
    assertEquals(listOf("chat.store_malformed_action"), diagnostics.categories)
  }

  @Test
  fun `attributed paths survive a restart and a chat saved without them reads as unattributed`() {
    val diagnostics = RecordingDiagnostics()
    val store = ChatStore(host, diagnostics, Clock.System)
    val baseline = ChatChangesBaseline("head", emptyList(), "stash", Instant.fromEpochMilliseconds(5))
    store.saveChat(entry("attributed", 10).copy(executionId = null, newTask = ChatNewTaskContext("/work/app", "main", changesBaseline = baseline, changesAttributedPaths = setOf("b.kt", "a.kt"))))
    store.saveChat(entry("legacy", 11).copy(executionId = null, newTask = ChatNewTaskContext("/work/app", "main", changesBaseline = baseline)))

    val restored = ChatStore(host, diagnostics, Clock.System).newTaskDrafts().entries.associate { it.sessionId to it.newTask?.changesAttributedPaths }

    assertEquals(mapOf("legacy" to null, "attributed" to setOf("a.kt", "b.kt")), restored)
    assertEquals(emptyList(), diagnostics.categories)
  }

  @Test
  fun `project worktree snapshots survive repeated saves alongside a previous version chat`() {
    val diagnostics = RecordingDiagnostics()
    val store = ChatStore(host, diagnostics, Clock.System)
    val baseline = ChatChangesBaseline("head", listOf("scratch.txt"), "stash", Instant.fromEpochMilliseconds(5))
    val file = File(host.userHome, "$CHAT_STORE_PATH/previous.json")
    file.parentFile.mkdirs()
    file.writeText("""{"session_id":"previous","agent_id":"codex","updated_at_ms":10,"new_task_repository_root":"/work/app","new_task_session_established":true,"new_task_changes_capture_attempted":true,"new_task_changes_baseline":{"head_sha":"head","untracked_paths":["scratch.txt"],"stash_sha":"stash","taken_at":5},"new_task_changes_attributed_paths":["original.kt"],"lines":[{"role":"Operator","text":"keep this history"}]}""")
    val previous = store.newTaskDrafts().entries.single()
    assertEquals(baseline, previous.newTask?.changesBaseline)
    assertEquals(setOf("original.kt"), previous.newTask?.changesAttributedPaths)
    val project = baseline.copy(projectId = "/work/app/.git", worktrees = mapOf("/work/app" to baseline, "/work/main" to baseline.copy(headSha = "main-head", stashSha = null)))
    val updated = previous.copy(newTask = previous.newTask!!.copy(changesBaseline = project, changesAttributedPaths = setOf("original.kt", "/work/main/changed.kt")))
    repeat(2) {
      assertTrue(store.saveChat(updated))
      assertEquals(updated, ChatStore(host, diagnostics, Clock.System).newTaskDrafts().entries.single())
    }
    assertEquals(emptyList(), diagnostics.categories)
  }

  @Test
  fun `new-task replay history stops before the pending turn`() {
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

    assertEquals(listOf(
      ChatHistoryLine(ChatHistoryRole.Operator, "make a plan"),
      ChatHistoryLine(ChatHistoryRole.Agent, "", actions = listOf(ChatSuggestedAction("Review", "Review", ChatPermissionMode.ReadOnly))),
    ), (store.newTaskHistoryRead("pending") as ChatStoreRead.Value).value)
  }

  @Test
  fun `legacy replay array is ignored and removed on save while chat metadata survives`() {
    val file = File(host.userHome, "$CHAT_STORE_PATH/legacy-exchanges.json")
    file.parentFile.mkdirs()
    file.writeText(
      """{"session_id":"legacy-exchanges","execution_id":"wf-1","agent_id":"codex","model":"kept-model","effort":"high","profile":"/profiles/kept","updated_at_ms":1,"agent_session":"kept-session","summary":"kept summary","summary_source_updated_at_ms":1,"lines":[{"role":"Operator","text":"keep this line"}],"exchanges":[{"operator":"old prompt","agent":"old reply"}]}""",
    )
    val store = ChatStore(host, RecordingDiagnostics(), Clock.System)
    val restored = store.savedChats("wf-1").entries.single()

    assertEquals("kept-model", restored.agent.model)
    assertEquals("high", restored.agent.effort)
    assertEquals("/profiles/kept", restored.agent.profile)
    assertEquals("kept summary", restored.summary)
    assertEquals(listOf("keep this line"), restored.lines.map { it.text })
    assertTrue(store.saveChat(restored))
    assertTrue(store.saveChat(restored))

    val saved = Json.parseToJsonElement(file.readText()).jsonObject
    assertEquals(false, "exchanges" in saved)
    assertEquals("kept-session", saved.getValue("agent_session").jsonPrimitive.content)
    assertEquals("kept summary", saved.getValue("summary").jsonPrimitive.content)
    assertEquals("kept-model", saved.getValue("model").jsonPrimitive.content)
    assertEquals("high", saved.getValue("effort").jsonPrimitive.content)
    assertEquals("/profiles/kept", saved.getValue("profile").jsonPrimitive.content)
    assertEquals(listOf(ChatHistoryLine(ChatHistoryRole.Operator, "keep this line")), ChatStore(host, RecordingDiagnostics(), Clock.System).savedChats("wf-1").entries.single().lines)
  }

  // A conversation save that rebuilt the record would wipe the summary the background writer stored, and a malformed
  // summary that failed the decode would lose the chat itself.
  @Test
  fun `a stored summary and its source time survive a conversation save and a malformed or missing one reads as none`() {
    val directory = File(host.userHome, CHAT_STORE_PATH).apply { mkdirs() }
    fun seed(session: String, extra: String) = File(directory, "$session.json").writeText(
      """{"session_id":"$session","execution_id":"wf-1","agent_id":"codex","updated_at_ms":1,"lines":[{"role":"Operator","text":"why blocked?"}]$extra}""",
    )
    seed("kept", ""","summary":"Fix the flaky CI step","summary_source_updated_at_ms":1""")
    seed("blank", ""","summary":"  ","summary_source_updated_at_ms":"soon"""")
    seed("number", ""","summary":42""")
    seed("absent", "")
    ChatStore(host, RecordingDiagnostics(), Clock.System).saveChat(entry("kept", 5).copy(summary = "ignored"))

    val chats = ChatStore(host, RecordingDiagnostics(), Clock.System).savedChats("wf-1").entries.associateBy { it.sessionId }

    assertEquals("Fix the flaky CI step", chats.getValue("kept").summary)
    assertEquals(Instant.fromEpochMilliseconds(5), chats.getValue("kept").updatedAt)
    val kept = Json.parseToJsonElement(File(directory, "kept.json").readText()).jsonObject
    assertEquals(1L, kept.getValue("summary_source_updated_at_ms").jsonPrimitive.long)
    listOf("blank", "number", "absent").forEach { session ->
      assertEquals(null, chats.getValue(session).summary, session)
      assertEquals(listOf(ChatHistoryLine(ChatHistoryRole.Operator, "why blocked?")), chats.getValue(session).lines, session)
    }
  }

  // A summary call that outlives a discard must not bring the chat back through the update fallback.
  @Test
  fun `a summary for a chat with no file writes nothing`() {
    val store = ChatStore(host, RecordingDiagnostics(), Clock.System)

    assertEquals(false, store.saveSummary("gone", "About nothing.", Instant.fromEpochMilliseconds(1)))
    assertEquals(false, File(host.userHome, "$CHAT_STORE_PATH/gone.json").exists())
  }

  // A summary that bumps updated_at reorders the list, and one that is never stale again misses new turns.
  @Test
  fun `a summary keeps order and update time and a later turn makes the chat stale again`() {
    val store = ChatStore(host, RecordingDiagnostics(), Clock.System)
    store.saveChat(entry("old", 1))
    store.saveChat(entry("new", 2))

    assertTrue(store.saveSummary("old", "Why CI blocked.", Instant.fromEpochMilliseconds(1)))

    assertEquals(listOf("new" to 2L, "old" to 1L), store.savedChats("wf-1").entries.map { it.sessionId to it.updatedAt.toEpochMilliseconds() })
    assertEquals(null, store.staleForSummary("old"))
    assertEquals(listOf("new"), store.staleSummaries(5).map { it.sessionId })

    val nextTurn = entry("old", 3)
    store.saveChat(nextTurn.copy(lines = nextTurn.lines + listOf(
      ChatHistoryLine(ChatHistoryRole.Operator, "what changed?"),
      ChatHistoryLine(ChatHistoryRole.Agent, "The deployment is fixed."),
    )))

    assertEquals("old", store.staleForSummary("old")?.sessionId)
    assertEquals(listOf("old"), store.staleSummaries(1).map { it.sessionId })
  }

  @Test
  fun `approval card json omits rule_pattern when null and round-trips when set`() {
    val diagnostics = RecordingDiagnostics()
    val card = ApprovalCard(
      key = ApprovalCardKey("a", "repo", "run", "gen", "claude", "req", false),
      checkoutRoot = "/repo",
      providerSessionId = null,
      title = "Bash",
      reason = null,
      subject = "git status",
      inputText = "git status",
      options = listOf(ApprovalCardOption("allow", "Allow", "allow")),
      actionFingerprint = null,
      authorityScope = null,
      createdAt = "2026-11-07T00:00:00Z",
      deadlineAt = "2026-11-07T00:10:00Z",
      state = "allowed",
      decisionSource = "stored_rule",
      selectedOptionId = "allow",
      ruleId = "rule-1",
      providerPayload = "{}",
      rulePattern = "git status *",
    )
    val omitted = card.copy(rulePattern = null).toJson()
    assertEquals(false, "rule_pattern" in omitted)
    assertEquals(null, omitted.toApprovalCard(diagnostics)?.rulePattern)
    assertEquals("git status *", card.toJson().toApprovalCard(diagnostics)?.rulePattern)
    assertEquals("git status *", card.toJson().toApprovalCard(diagnostics)?.toJson()?.toApprovalCard(diagnostics)?.rulePattern)
  }
}
