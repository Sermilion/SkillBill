package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.DetectedAgent
import dev.skillbill.desktop.core.domain.model.DetectedAgentsRead
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.toNewTaskKind
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.loadedBoard
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class NewTaskHomeTest {
  private val host = NewTaskScreenHost(now = { Instant.fromEpochMilliseconds(0) }, onOpenRun = {}, onShowAllWork = {})

  @Test
  fun `recent chats filter to the selected repository and route resume and discard`() = runComposeUiTest {
    val chats = FakeExecutionChatRepository()
    val app = savedDraft(chats, "app", "/work/app", Instant.fromEpochSeconds(0), "Build app\nsecond line")
    val appOther = savedDraft(chats, "app-other", "/work/app", Instant.fromEpochSeconds(1_800), "Review app")
    val lib = savedDraft(chats, "lib", "/work/lib", Instant.fromEpochSeconds(3_600), "Update library")
    chats.saved[app.sessionId] = app
    chats.saved[appOther.sessionId] = appOther
    chats.saved[lib.sessionId] = lib
    val open = NewTaskChats(chats, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System)
    val draft = open.selected.value
    val actions = mutableListOf<NewTaskAction>()
    draft.repository = NewTaskRepository("/work/app", "app")
    draft.refreshSaved()
    val fixedHost = NewTaskScreenHost(now = { Instant.fromEpochSeconds(7_200) }, onOpenRun = {}, onShowAllWork = {})
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) { NewTaskScreen(NewTaskUiState(), open, fixedHost) { actions += it } }
      }
    }
    waitForIdle()

    onNodeWithText("Recent chats").assertExists()
    onNodeWithText("Build app").assertExists()
    onNodeWithText("Update library").assertDoesNotExist()
    onNodeWithText("2h").assertExists()
    onNodeWithText("Build app").performClick()
    waitForIdle()
    assertEquals(listOf<NewTaskAction>(NewTaskAction.ResumeDraft(app)), actions)

    actions.clear()
    onNodeWithText("Review app").performMouseInput { enter(center) }
    onNodeWithContentDescription("Discard Review app").performClick()
    waitForIdle()
    assertEquals(listOf<NewTaskAction>(NewTaskAction.DiscardDraft(appOther)), actions)
  }

  @Test
  fun `open chat row shows its badge and cannot be discarded`() = runComposeUiTest {
    val chats = FakeExecutionChatRepository()
    val app = savedDraft(chats, "open-app", "/work/app", Instant.fromEpochSeconds(0), "Build app")
    val other = savedDraft(chats, "other-app", "/work/app", Instant.fromEpochSeconds(1), "Other app chat")
    chats.saved[app.sessionId] = app
    chats.saved[other.sessionId] = other
    val open = NewTaskChats(chats, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System)
    open.selected.value.repository = NewTaskRepository("/work/app", "app")
    open.resume(app)
    open.newChat()
    open.refreshSaved()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) { NewTaskScreen(NewTaskUiState(), open, host) {} }
      }
    }
    waitForIdle()

    onAllNodesWithText("open").assertCountEquals(1)
    onNode(hasText("Build app") and hasAnyAncestor(hasContentDescription("Recent chats"))).performMouseInput { moveTo(center) }
    onNodeWithContentDescription("Discard Build app").assertDoesNotExist()
    onNodeWithText("Other app chat").performMouseInput { moveTo(center) }
    onNodeWithContentDescription("Discard Other app chat").assertExists()
  }

  @Test
  fun `recent chat card opens the selected row and ignores delete on an open row`() = runComposeUiTest {
    val repository = FakeExecutionChatRepository()
    val entries = (0..2).map { index ->
      savedDraft(repository, "keyboard-$index", "/work/app", Instant.fromEpochSeconds(index.toLong()), "Keyboard $index")
    }
    entries.forEach { repository.saved[it.sessionId] = it }
    val open = NewTaskChats(repository, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System)
    open.selected.value.repository = NewTaskRepository("/work/app", "app")
    open.resume(entries.first())
    open.newChat()
    open.refreshSaved()
    val actions = mutableListOf<NewTaskAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) { NewTaskScreen(NewTaskUiState(), open, host) { actions += it } }
      }
    }
    waitForIdle()

    val card = onNodeWithContentDescription("Recent chats")
    card.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
    card.assertIsFocused()
    card.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 of 3, Keyboard 2"))
    card.performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.Enter) }
    assertEquals(listOf<NewTaskAction>(NewTaskAction.ResumeDraft(entries[1])), actions)
    card.performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.Delete) }
    assertEquals(listOf<NewTaskAction>(NewTaskAction.ResumeDraft(entries[1])), actions)
  }

  @Test
  fun `tab from recent chats focuses the two row needs you card and opens its selected run`() = runComposeUiTest {
    val repository = FakeExecutionChatRepository()
    val saved = savedDraft(repository, "focus-chat", "/work/app", Instant.fromEpochSeconds(0), "Focus chat")
    repository.saved[saved.sessionId] = saved
    val chats = NewTaskChats(repository, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System)
    chats.selected.value.repository = NewTaskRepository("/work/app", "app")
    chats.refreshSaved()
    val runs = listOf(workItem("SKILL-1", reason = "First pause"), workItem("SKILL-2", reason = "Second pause"))
    val opened = mutableListOf<WorkItem>()
    val runHost = NewTaskScreenHost(now = { Instant.fromEpochMilliseconds(0) }, onOpenRun = { opened += it }, onShowAllWork = {})
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          NewTaskScreen(NewTaskUiState(home = NewTaskHome.Returning(runs, 0, 0)), chats, runHost) {}
        }
      }
    }

    val recent = onNodeWithContentDescription("Recent chats")
    val needsYou = onNodeWithContentDescription("Needs you")
    recent.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
    recent.assertIsFocused()
    recent.performKeyInput { pressKey(Key.Tab) }
    needsYou.assertIsFocused()
    needsYou.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 of 2, ${runs[0].title}"))
    assertEquals(2, needsYou.fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount)
    assertEquals(1, needsYou.fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].columnCount)
    val cardBounds = needsYou.fetchSemanticsNode().boundsInRoot
    val rowBounds = runs.map { onNodeWithText(it.title).fetchSemanticsNode().boundsInRoot }
    assertTrue(rowBounds.all { it.top >= cardBounds.top && it.bottom <= cardBounds.bottom })
    assertTrue(cardBounds.height < 3 * rowBounds.first().height)

    needsYou.performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown); pressKey(Key.Enter) }
    needsYou.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "2 of 2, ${runs[1].title}"))
    assertEquals(listOf(runs[1]), opened)
  }

  @Test
  fun `recent chats list every saved chat and search opens the palette`() = runComposeUiTest {
    val chats = FakeExecutionChatRepository()
    val drafts = (0..6).map { index ->
      savedDraft(chats, "draft-$index", "/work/app", Instant.fromEpochSeconds(index.toLong()), "Draft $index")
    }
    drafts.forEach { chats.saved[it.sessionId] = it }
    val open = NewTaskChats(chats, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System)
    val draft = open.selected.value
    val actions = mutableListOf<NewTaskAction>()
    draft.repository = NewTaskRepository("/work/app", "app")
    draft.refreshSaved()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) { NewTaskScreen(NewTaskUiState(home = NewTaskHome.FirstRun), open, host) { actions += it } }
      }
    }
    waitForIdle()

    (0..6).forEach { onNodeWithText("Draft $it").assertExists() }
    onNodeWithText("Show all").assertDoesNotExist()
    onNodeWithText("Try one of these").assertExists()
    onNodeWithContentDescription("Search chats").performClick()
    assertEquals(listOf<NewTaskAction>(NewTaskAction.ShowAllDrafts), actions)

  }

  // A shortcut the screen ignores or routes to the wrong chat, or a close of a replying chat with no confirmation.
  @Test
  fun `chat shortcuts add, select, cycle and close chats`() = runComposeUiTest {
    val chats = FakeExecutionChatRepository()
    chats.events = { flow { awaitCancellation() } }
    val open = NewTaskChats(chats, FakeWorkControlRepository(), testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System)
    open.chatDispatcher = Dispatchers.Unconfined
    val first = open.selected.value
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) { NewTaskScreen(NewTaskUiState(), open, host) {} }
      }
    }
    val field = onNode(hasSetTextAction())

    field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.T) } }
    runOnIdle { assertEquals(2, open.open.value.size) }
    field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.One) } }
    runOnIdle { assertSame(first, open.selected.value) }
    onNode(hasSetTextAction()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Two) } }
    runOnIdle { assertSame(open.open.value[1], open.selected.value) }
    onNode(hasSetTextAction()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Tab) } }
    runOnIdle { assertSame(first, open.selected.value) }

    runOnIdle {
      first.repository = NewTaskRepository(".", "repo")
      first.agentId = requireNotNull(chats.agent).agentId
      first.selectedAgent = chats.agent
      first.text = "build export"
      first.send()
    }
    onNode(hasSetTextAction()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.W) } }
    onNodeWithText("Keep open").assertExists()
    onNodeWithText("Stop and close").assertExists()
    runOnIdle { assertEquals(2, open.open.value.size) }
  }

  private fun savedDraft(chats: FakeExecutionChatRepository, sessionId: String, root: String, updatedAt: Instant, title: String) =
    ChatHistoryEntry(
      sessionId,
      null,
      requireNotNull(chats.agent),
      updatedAt,
      title,
      listOf(ChatHistoryLine(ChatHistoryRole.Operator, title)),
      ChatNewTaskContext(root, "main"),
    )

  @Test
  fun `an empty board shows the examples and readiness without needs you or chips`() = runComposeUiTest {
    val state = NewTaskUiState(home = newTaskHome(loadedBoard()), detectedAgents = DetectedAgentsRead.Success(listOf(DetectedAgent("claude", "/bin/claude"), DetectedAgent("claude", "/opt/claude"))))
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value.apply { repository = NewTaskRepository("/work/app", "app") }
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) { NewTaskScreen(state, chats, host) {} }
      }
    }

    onNodeWithText("Try one of these").assertExists()
    onNodeWithText("Ready: claude found, app selected").assertExists()
    onAllNodesWithText("Recent chats").assertCountEquals(0)
    onAllNodesWithText("Review my branch").assertCountEquals(0)
  }

  @Test
  fun `a loading board shows neither variant`() = runComposeUiTest {
    val state = NewTaskUiState(home = newTaskHome(BoardState.Loading))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) { NewTaskScreen(state, unconfinedNewTaskChats(), host) {} }
      }
    }

    onAllNodesWithText("Try one of these").assertCountEquals(0)
    onAllNodesWithText("Needs you").assertCountEquals(0)
    onAllNodesWithText("All clear").assertCountEquals(0)
    onAllNodesWithText("Review my branch").assertCountEquals(0)
  }

  @Test
  fun `a failed detection read never claims readiness`() {
    val failed = DetectedAgentsRead.Failed(ReadProblem.Runtime("detection failed"))
    listOf("SkillBill", null).forEach { repository ->
      val line = readinessLine(failed, repository)
      assertEquals("Readiness unknown: agent detection failed", line?.text)
      assertFalse(line?.ready ?: true)
    }
  }

  @Test
  fun `the readiness line names what is missing and stays absent until the read finishes`() {
    val found = DetectedAgentsRead.Success(listOf(DetectedAgent("claude", "/bin/claude"), DetectedAgent("codex", "/bin/codex")))
    assertEquals(ReadinessLine("Ready: claude, codex found, app selected", ready = true), readinessLine(found, "app"))
    assertEquals(ReadinessLine("Not ready: choose a repository", ready = false), readinessLine(found, null))
    assertEquals(ReadinessLine("Not ready: no agent found", ready = false), readinessLine(DetectedAgentsRead.Success(emptyList()), "app"))
    assertNull(readinessLine(null, "app"))
  }

  @Test
  fun `returning content shows needs you rows that open their run and a summary that opens all work`() = runComposeUiTest {
    val paused = workItem("SKILL-1", reason = "the fallback reason").copy(exactPauseReason = "the exact pause reason")
    val plain = workItem("SKILL-2", reason = "the only reason")
    val finished = workItem("SKILL-3", finishedAt = Instant.fromEpochMilliseconds(0))
    val board = loadedBoard(mapOf(BoardSectionKind.NeedsYou to listOf(paused, plain), BoardSectionKind.Finished to listOf(finished)))
    val opened = mutableListOf<WorkItem>()
    var showedAllWork = false
    val twoHoursLater = NewTaskScreenHost(now = { Instant.fromEpochSeconds(7_200) }, onOpenRun = { opened += it }, onShowAllWork = { showedAllWork = true })
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) { NewTaskScreen(NewTaskUiState(home = newTaskHome(board)), unconfinedNewTaskChats(), twoHoursLater) {} }
      }
    }

    onNodeWithText("Needs you").assertExists()
    onNodeWithText("2").assertExists()
    onNodeWithText("the exact pause reason").assertExists()
    onAllNodesWithText("the fallback reason").assertCountEquals(0)
    onNodeWithText("the only reason").assertExists()
    onAllNodesWithText("2h").assertCountEquals(2)
    onAllNodesWithText("Try one of these").assertCountEquals(0)

    onNodeWithText("Title SKILL-2").performClick()
    waitForIdle()
    assertEquals(listOf(plain), opened)

    onNodeWithText("●").assertExists()
    onNodeWithText("0 running · 1 finished today").assertExists()
    onNodeWithContentDescription("0 running · 1 finished today").performClick()
    waitForIdle()
    assertTrue(showedAllWork)
  }

  @Test
  fun `a running count alone keeps the returning content without the needs you label`() = runComposeUiTest {
    val empty = loadedBoard()
    val home = newTaskHome(empty.copy(counts = empty.counts.copy(running = 2)))
    assertEquals(NewTaskHome.Returning(emptyList(), running = 2, finished = 0), home)
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) { NewTaskScreen(NewTaskUiState(home = home), unconfinedNewTaskChats(), host) {} }
      }
    }

    onNodeWithText("All clear").assertExists()
    onNodeWithContentDescription("2 running · 0 finished today").assertExists()
    onNodeWithText("Needs you").assertDoesNotExist()
    onNodeWithText("Nothing here. Start something below.").assertDoesNotExist()
    onAllNodesWithText("Try one of these").assertCountEquals(0)
  }

  @Test
  fun `chips stay disabled until a repository and an agent are selected`() = runComposeUiTest {
    val startable = NewTaskScreenHost(now = { Instant.fromEpochMilliseconds(0) }, onOpenRun = {}, onShowAllWork = {}, onStart = { _, _, _ -> })
    val state = NewTaskUiState(home = newTaskHome(loadedBoard(mapOf(BoardSectionKind.Goals to listOf(workItem("SKILL-1"))))))
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) { NewTaskScreen(state, chats, startable) {} }
      }
    }

    onNodeWithText("Review my branch").assertIsNotEnabled()
    runOnIdle { draft.repository = NewTaskRepository("/work/app", "app") }
    onNodeWithText("Review my branch").assertIsNotEnabled()
    runOnIdle { draft.agentId = "claude" }
    onNodeWithText("Review my branch").assertIsEnabled()
  }

  @Test
  fun `a chip press reports its start kind`() = runComposeUiTest {
    val started = mutableListOf<String>()
    setContent {
      SkillBillTheme(reducedMotion = true) { NewTaskChips({ kind, _ -> started += kind }) }
    }

    listOf("Review my branch", "Run the checks", "QA my branch", "Open a PR").forEach { onNodeWithText(it).performClick() }
    waitForIdle()
    assertEquals(listOf("review", "validation", "qa", "pr"), started)
  }

  @Test
  fun `clicking a suggested goal forwards its exact action fields through the host`() = runComposeUiTest {
    val action = ChatSuggestedAction(
      "Plan selected change",
      "Plan only the selected change.",
      ChatPermissionMode.Act,
      NewTaskActionKind.Plan,
      title = null,
    )
    val chats = FakeExecutionChatRepository().apply {
      events = { flowOf(ChatEvent.SuggestedActions(listOf(action)), ChatEvent.Completed) }
    }
    val controls = FakeWorkControlRepository().apply { newTaskOutcome = NewTaskStartOutcome.NeedsKey(listOf("APP")) }
    val open = NewTaskChats(chats, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System)
    val draft = open.selected.value.apply {
      chatDispatcher = Dispatchers.Unconfined
      repository = NewTaskRepository("/work/app", "app")
      agentId = "claude"
      selectedAgent = chats.agent
      text = "Existing issue SKILL-123 is unrelated."
      send()
    }
    val startable = NewTaskScreenHost(
      now = { Instant.fromEpochMilliseconds(0) },
      onOpenRun = {},
      onShowAllWork = {},
      onStart = { wire, target, selectedAction ->
        NewTaskActionKind.entries.firstOrNull { it.wire == wire }?.toNewTaskKind()?.let { kind ->
          draft.start(kind, target = target, action = selectedAction)
        }
      },
    )
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          NewTaskScreen(NewTaskUiState(), open, startable) {}
        }
      }
    }

    onNodeWithText("Plan selected change").performClick()
    waitForIdle()

    assertEquals("Plan only the selected change.", controls.newTaskRequests.single().requirements)
    assertNull(controls.newTaskRequests.single().title)
  }

  @Test
  fun `pressing an example fills the input and previews its route`() = runComposeUiTest {
    val fill = "Add CSV export to the history screen. Done when a user can download the rows they see."
    val chats = unconfinedNewTaskChats()
    val draft = chats.selected.value
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          NewTaskScreen(NewTaskUiState(home = newTaskHome(loadedBoard())), chats, host) { action ->
            if (action is NewTaskAction.TextChanged) draft.text = action.text
          }
        }
      }
    }

    onNodeWithText("Every goal runs this path").assertDoesNotExist()
    onNodeWithText("Add CSV export to the history screen").performClick()
    waitForIdle()
    assertEquals(fill, draft.text)
    onNodeWithText("Looks like a goal run, all 7 steps").assertExists()
  }
}
