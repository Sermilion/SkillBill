package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.ChatEvent
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
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class NewTaskHomeTest {
  private val host = NewTaskScreenHost(now = { Instant.fromEpochMilliseconds(0) }, onOpenRun = {}, onShowAllWork = {})

  @Test
  fun `an empty board shows the examples and readiness without needs you or chips`() = runComposeUiTest {
    val state = NewTaskUiState(home = newTaskHome(loadedBoard()), detectedAgents = DetectedAgentsRead.Success(listOf(DetectedAgent("claude", "/bin/claude"), DetectedAgent("claude", "/opt/claude"))))
    val draft = testNewTaskDraft().apply { repository = NewTaskRepository("/work/app", "app") }
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) { NewTaskScreen(state, draft, host) {} }
      }
    }

    onNodeWithText("Try one of these").assertExists()
    onNodeWithText("Ready: claude found, app selected").assertExists()
    onAllNodesWithText("Needs you").assertCountEquals(0)
    onAllNodesWithText("Review my branch").assertCountEquals(0)
  }

  @Test
  fun `a loading board shows neither variant`() = runComposeUiTest {
    val state = NewTaskUiState(home = newTaskHome(BoardState.Loading))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) { NewTaskScreen(state, testNewTaskDraft(), host) {} }
      }
    }

    onAllNodesWithText("Try one of these").assertCountEquals(0)
    onAllNodesWithText("Needs you").assertCountEquals(0)
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
        CompositionLocalProvider(LocalShellInput provides ShellInput()) { NewTaskScreen(NewTaskUiState(home = newTaskHome(board)), testNewTaskDraft(), twoHoursLater) {} }
      }
    }

    onNodeWithText("Needs you").assertExists()
    onNodeWithText("the exact pause reason").assertExists()
    onAllNodesWithText("the fallback reason").assertCountEquals(0)
    onNodeWithText("the only reason").assertExists()
    onAllNodesWithText("2h ago").assertCountEquals(2)
    onAllNodesWithText("Try one of these").assertCountEquals(0)

    onNodeWithText("Title SKILL-2").performClick()
    waitForIdle()
    assertEquals(listOf(plain), opened)

    onNodeWithText("0 running · 1 finished today").performClick()
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
        CompositionLocalProvider(LocalShellInput provides ShellInput()) { NewTaskScreen(NewTaskUiState(home = home), testNewTaskDraft(), host) {} }
      }
    }

    onNodeWithText("2 running · 0 finished today").assertExists()
    onAllNodesWithText("Needs you").assertCountEquals(0)
    onAllNodesWithText("Try one of these").assertCountEquals(0)
  }

  @Test
  fun `chips stay disabled until a repository and an agent are selected`() = runComposeUiTest {
    val startable = NewTaskScreenHost(now = { Instant.fromEpochMilliseconds(0) }, onOpenRun = {}, onShowAllWork = {}, onStart = { _, _, _ -> })
    val state = NewTaskUiState(home = newTaskHome(loadedBoard(mapOf(BoardSectionKind.Goals to listOf(workItem("SKILL-1"))))))
    val draft = testNewTaskDraft()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) { NewTaskScreen(state, draft, startable) {} }
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
    val draft = NewTaskDraft(chats, controls, testDatabaseSessionController(), ToastQueue(), WindowCoroutineScope(CoroutineScope(Dispatchers.Unconfined)), Clock.System).apply {
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
          NewTaskScreen(NewTaskUiState(), draft, startable) {}
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
    val draft = testNewTaskDraft()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          NewTaskScreen(NewTaskUiState(home = newTaskHome(loadedBoard())), draft, host) { action ->
            if (action is NewTaskAction.TextChanged) draft.text = action.text
          }
        }
      }
    }

    onNodeWithText("Every goal runs this path").assertExists()
    onNodeWithText("Add CSV export to the history screen").performClick()
    waitForIdle()
    assertEquals(fill, draft.text)
    onNodeWithText("Looks like a goal run, all 7 steps").assertExists()
  }
}
