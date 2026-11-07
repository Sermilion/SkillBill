package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeScopeTimeline
import dev.skillbill.desktop.core.domain.model.ScopeCommit
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangeSet
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.Comment
import dev.skillbill.desktop.core.domain.model.CommentAuthor
import dev.skillbill.desktop.core.domain.model.CommentRejection
import dev.skillbill.desktop.core.domain.model.CommentSide
import dev.skillbill.desktop.core.domain.model.CommentState
import dev.skillbill.desktop.core.domain.model.CommentThread
import dev.skillbill.desktop.core.domain.model.ChangesState
import dev.skillbill.desktop.core.domain.model.DiffCommentsState
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailAttempts
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.LatestActivity
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.ScopeChangesState
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.repository.WorkChangeScopesRepository
import dev.skillbill.desktop.core.domain.repository.WorkChangesRepository
import dev.skillbill.desktop.core.domain.repository.DiffCommentRepository
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.testing.FakeWorkDetailRepository
import dev.skillbill.desktop.core.testing.workItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChangesWindowViewModelTest {
  private val details = FakeWorkDetailRepository()
  private val scopes = FakeChangeScopes()
  private val opener = RecordingOpener()
  private val comments = FakeDiffComments()
  private val baseline = ChangeBaseline.RunStart("/repo", "base", emptyList())

  @AfterTest
  fun tearDown() = Dispatchers.resetMain()

  @Test
  fun `a scope the execution does not offer falls back to all changes`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val phase = workItem("P", kind = WorkKind.Phase)
    detailOf(phase)

    val viewModel = ChangesWindowViewModel(phase, ChangeScope.Subtasks(2, 2), details, scopes, opener, comments)
    advanceUntilIdle()

    assertEquals(ChangeScope.All, viewModel.state.value.scope)
    assertEquals(listOf<ChangeScope>(ChangeScope.All), scopes.shown)
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `the review bar buttons work in the changes dialog`() = runComposeUiTest {
    val phase = workItem("P", kind = WorkKind.Phase)
    detailOf(phase)
    comments.state.value = availablePending()
    scopes.list(ChangeScope.All).value = listed(file("src/A.kt"))
    val viewModel = ChangesWindowViewModel(phase, ChangeScope.All, details, scopes, opener, comments)
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Dialog({}, DialogProperties(usePlatformDefaultWidth = false)) {
          Box(Modifier.size(1100.dp, 700.dp)) { ChangesScreen(viewModel, ChangesWindowKeys(), {}) }
        }
      }
    }
    waitUntil(timeoutMillis = 5_000) { viewModel.state.value.pendingCount == 2 }

    onNodeWithText("Discard all").performMouseInput { click() }
    waitUntil(timeoutMillis = 5_000) { viewModel.state.value.review.confirmingDiscard }
    onNodeWithText("Keep").performMouseInput { click() }
    waitUntil(timeoutMillis = 5_000) { !viewModel.state.value.review.confirmingDiscard }

    comments.submitResult = Outcome.Success(2)
    onNodeWithText("Add review").performMouseInput { click() }
    waitUntil(timeoutMillis = 5_000) { viewModel.state.value.pendingCount == 0 }
  }

  @Test
  fun `a failed submit keeps the drafts and shows the reason`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val phase = workItem("P", kind = WorkKind.Phase)
    detailOf(phase)
    comments.state.value = availablePending()
    comments.submitResult = Outcome.Failure(CommentRejection("closed"))
    val viewModel = ChangesWindowViewModel(phase, ChangeScope.All, details, scopes, opener, comments)
    runCurrent()

    viewModel.onAction(ChangesWindowAction.SubmitReview)
    runCurrent()

    assertEquals(2, viewModel.state.value.pendingCount)
    assertEquals("closed", viewModel.state.value.review.problem)
    assertEquals(false, viewModel.state.value.review.submitting)
    assertEquals(null, viewModel.state.value.reviewToast)
  }

  @Test
  fun `a submit that never returns frees the bar after the timeout`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val phase = workItem("P", kind = WorkKind.Phase)
    detailOf(phase)
    comments.state.value = availablePending()
    comments.reviewWrite = CompletableDeferred()
    val viewModel = ChangesWindowViewModel(phase, ChangeScope.All, details, scopes, opener, comments)
    runCurrent()

    viewModel.onAction(ChangesWindowAction.SubmitReview)
    runCurrent()
    assertEquals(ReviewWrite.Submit, viewModel.state.value.review.inFlight)

    advanceTimeBy(20_001)
    runCurrent()
    assertEquals(null, viewModel.state.value.review.inFlight)
    assertNotNull(viewModel.state.value.review.problem)
    assertEquals(2, viewModel.state.value.pendingCount)
  }

  @Test
  fun `a discard that throws frees the bar and shows a problem`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val phase = workItem("P", kind = WorkKind.Phase)
    detailOf(phase)
    comments.state.value = availablePending()
    comments.discardFailure = NoSuchMethodError("discardPending")
    val viewModel = ChangesWindowViewModel(phase, ChangeScope.All, details, scopes, opener, comments)
    runCurrent()

    viewModel.onAction(ChangesWindowAction.RequestDiscard)
    viewModel.onAction(ChangesWindowAction.ConfirmDiscard)
    runCurrent()

    assertEquals(null, viewModel.state.value.review.inFlight)
    assertNotNull(viewModel.state.value.review.problem)
    assertEquals(2, viewModel.state.value.pendingCount)
  }

  @Test
  fun `a successful submit clears the bar and the toast expires`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val phase = workItem("P", kind = WorkKind.Phase)
    detailOf(phase)
    comments.state.value = availablePending()
    comments.submitResult = Outcome.Success(2)
    val viewModel = ChangesWindowViewModel(phase, ChangeScope.All, details, scopes, opener, comments)
    runCurrent()

    viewModel.onAction(ChangesWindowAction.SubmitReview)
    runCurrent()
    assertEquals(0, viewModel.state.value.pendingCount)
    assertEquals("Review added · 2 comments", viewModel.state.value.reviewToast)

    advanceTimeBy(2_600)
    runCurrent()
    assertEquals(null, viewModel.state.value.reviewToast)
  }

  @Test
  fun `a completed save does not erase a replacement draft at the same anchor`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val phase = workItem("P", kind = WorkKind.Phase)
    detailOf(phase)
    comments.state.value = availablePending()
    val write = CompletableDeferred<Outcome<Unit, CommentRejection>>()
    comments.pendingWrite = write
    val viewModel = ChangesWindowViewModel(phase, ChangeScope.All, details, scopes, opener, comments)
    runCurrent()
    viewModel.onAction(ChangesWindowAction.OpenComposer("src/A.kt", CommentSide.New, 10))
    viewModel.onAction(ChangesWindowAction.ComposerText("original"))
    viewModel.onAction(ChangesWindowAction.SavePending)
    runCurrent()

    viewModel.onAction(ChangesWindowAction.CancelComposer)
    viewModel.onAction(ChangesWindowAction.OpenComposer("src/A.kt", CommentSide.New, 10))
    viewModel.onAction(ChangesWindowAction.ComposerText("replacement"))
    write.complete(Outcome.Success(Unit))
    runCurrent()

    assertEquals("replacement", viewModel.state.value.composer?.text)
    assertEquals(false, viewModel.state.value.composer?.saving)
  }

  @Test
  fun `a submit completed after a branch change cannot show feedback on the new branch`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val phase = workItem("P", kind = WorkKind.Phase)
    detailOf(phase)
    comments.state.value = availablePending()
    val write = CompletableDeferred<Outcome<Int, CommentRejection>>()
    comments.reviewWrite = write
    val viewModel = ChangesWindowViewModel(phase, ChangeScope.All, details, scopes, opener, comments)
    runCurrent()
    viewModel.onAction(ChangesWindowAction.SubmitReview)
    runCurrent()

    comments.state.value = availablePending().copy(branch = "other")
    runCurrent()
    write.complete(Outcome.Success(2))
    runCurrent()

    assertEquals(2, viewModel.state.value.pendingCount)
    assertEquals(null, viewModel.state.value.reviewToast)
    assertEquals(ReviewBarUi(), viewModel.state.value.review)
  }

  @Test
  fun `a refresh keeps a still-listed selection and otherwise selects the first file`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val phase = workItem("P", kind = WorkKind.Phase)
    detailOf(phase)
    val viewModel = ChangesWindowViewModel(phase, ChangeScope.All, details, scopes, opener, comments)
    scopes.list(ChangeScope.All).value = listed(file("a/A.kt"), file("b/B.kt"))
    advanceUntilIdle()

    viewModel.onAction(ChangesWindowAction.SelectFile("b/B.kt"))
    scopes.list(ChangeScope.All).value = listed(file("a/A.kt"), file("b/B.kt", insertions = 4))
    advanceUntilIdle()
    assertEquals("b/B.kt", viewModel.state.value.selectedPath)

    scopes.list(ChangeScope.All).value = listed(file("a/A.kt"), file("c/C.kt"))
    advanceUntilIdle()
    assertEquals("a/A.kt", viewModel.state.value.selectedPath)
  }

  @Test
  fun `a subtask chip narrows the scope, shows loading, and keeps the file selected`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val goal = workItem("G")
    detailOf(goal)
    val foo = file("a/Foo.kt")
    scopes.subtasks.value = SubtaskScopesState.Listed(
      worktreeRoot = "/repo",
      targetBranch = "main",
      rangesSupported = true,
      subtasks = listOf(subtask(3), subtask(5)),
      stats = mapOf(3 to SubtaskStats(listOf(foo), 1, 0), 5 to SubtaskStats(listOf(foo), 1, 0)),
      timeline = timelineFor(listOf(subtask(3), subtask(5))),
    )
    val viewModel = ChangesWindowViewModel(goal, ChangeScope.All, details, scopes, opener, comments)
    scopes.list(ChangeScope.All).value = listed(file("a/Bar.kt"), foo)
    advanceUntilIdle()
    viewModel.onAction(ChangesWindowAction.SelectFile(foo.path))
    advanceUntilIdle()
    assertEquals(2, viewModel.state.value.groups.single().rows.single { it.file == foo }.touchCount)
    assertEquals(listOf(3, 5), (viewModel.state.value.diff as DiffPaneUi.Shown).chips.map { it.id })

    viewModel.onAction(ChangesWindowAction.NarrowToSubtask(5))
    assertEquals(ChangeScope.Subtasks(5, 5), viewModel.state.value.scope)
    assertEquals(ScopeListUi.Loading, viewModel.state.value.list)

    scopes.list(ChangeScope.Subtasks(5, 5)).value = listed(foo)
    advanceUntilIdle()
    assertEquals(ChangeScope.Subtasks(5, 5), scopes.shown.last())
    assertEquals(foo.path, viewModel.state.value.selectedPath)
    assertEquals(ChangeScope.Subtasks(5, 5) to foo.path, scopes.diffReads.last())
  }

  @Test
  fun `a subtask scope with a lost base shows the reason and a chip scope loads its own list`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val goal = workItem("G")
    detailOf(goal)
    val foo = file("a/Foo.kt")
    scopes.subtasks.value = SubtaskScopesState.Listed(
      worktreeRoot = "/repo",
      targetBranch = "main",
      rangesSupported = true,
      subtasks = listOf(subtask(3), subtask(5)),
      stats = mapOf(3 to SubtaskStats(listOf(foo), 1, 0), 5 to SubtaskStats(listOf(foo), 1, 0)),
      timeline = timelineFor(listOf(subtask(3), subtask(5))),
    )
    scopes.list(ChangeScope.Subtasks(3, 3)).value = ScopeChangesState.BaseUnavailable("start3 is not in the repository")
    val viewModel = ChangesWindowViewModel(goal, ChangeScope.Subtasks(3, 3), details, scopes, opener, comments)
    advanceUntilIdle()

    assertEquals(ChangeScope.Subtasks(3, 3), viewModel.state.value.scope)
    assertEquals<ScopeListUi>(ScopeListUi.BaseUnavailable("start3 is not in the repository"), viewModel.state.value.list)

    viewModel.onAction(ChangesWindowAction.NarrowToSubtask(5))
    scopes.list(ChangeScope.Subtasks(5, 5)).value = listed(foo)
    advanceUntilIdle()

    assertEquals(listOf<ChangeScope>(ChangeScope.Subtasks(3, 3), ChangeScope.Subtasks(5, 5)), scopes.shown.filterNot { it == ChangeScope.All })
    assertEquals(ScopeListUi.Listed(ChangeSet("base", listOf(foo), 1, 0)), viewModel.state.value.list)
    assertEquals(foo.path, viewModel.state.value.selectedPath)
  }

  @Test
  fun `a missing execution shows the missing state and stops change reads`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val phase = workItem("P", kind = WorkKind.Phase)
    val detail = detailOf(phase)
    val viewModel = ChangesWindowViewModel(phase, ChangeScope.All, details, scopes, opener, comments)
    advanceUntilIdle()

    detail.value = DetailState(phase, DetailSections.Gone)
    scopes.list(ChangeScope.All).value = listed(file("a/A.kt"))
    advanceUntilIdle()

    assertEquals(WindowContent.Missing, viewModel.state.value.content)
    assertEquals(ScopeListUi.Loading, viewModel.state.value.list)
    assertEquals(listOf<ChangeScope>(ChangeScope.All), scopes.shown)
  }

  @Test
  fun `shift-pick extends from the anchor and a drag stops at the last selectable subtask`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val viewModel = goalWindow((1..33).map { id -> if (id > 21) notStarted(id) else subtask(id) })

    viewModel.onAction(ChangesWindowAction.PickSubtask(4, extend = false, fromPicker = false))
    viewModel.onAction(ChangesWindowAction.PickSubtask(9, extend = true, fromPicker = true))
    assertEquals(ChangeScope.Subtasks(4, 9), viewModel.state.value.scope)

    viewModel.onAction(ChangesWindowAction.DragSubtasks(18, 30))
    assertEquals(ChangeScope.Subtasks(18, 21), viewModel.state.value.scope)

    viewModel.onAction(ChangesWindowAction.PickSubtask(25, extend = false, fromPicker = false))
    assertEquals(ChangeScope.Subtasks(18, 21), viewModel.state.value.scope)
  }

  @Test
  fun `steps skip subtasks that cannot be selected, stop at both ends and restart from all changes`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val viewModel = goalWindow(listOf(subtask(1), subtask(2).copy(recordedSha = null, commitShas = emptyList()), subtask(3), notStarted(4)))

    viewModel.onAction(ChangesWindowAction.Step(forward = true))
    assertEquals(ChangeScope.Subtasks(1, 1), viewModel.state.value.scope)
    viewModel.onAction(ChangesWindowAction.Step(forward = true))
    assertEquals(ChangeScope.Subtasks(3, 3), viewModel.state.value.scope)
    viewModel.onAction(ChangesWindowAction.Step(forward = true))
    assertEquals(ChangeScope.Subtasks(3, 3), viewModel.state.value.scope)

    viewModel.onAction(ChangesWindowAction.Step(forward = false))
    assertEquals(ChangeScope.Subtasks(1, 1), viewModel.state.value.scope)
    viewModel.onAction(ChangesWindowAction.Step(forward = false))
    assertEquals(ChangeScope.Subtasks(1, 1), viewModel.state.value.scope)

    viewModel.onAction(ChangesWindowAction.PickAll)
    assertEquals(ChangeScope.All, viewModel.state.value.scope)
    viewModel.onAction(ChangesWindowAction.Step(forward = false))
    assertEquals(ChangeScope.Subtasks(3, 3), viewModel.state.value.scope)
  }

  @Test
  fun `view switches preserve spanned operator commits and remove subtask quick buttons`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val goal = workItem("G")
    detailOf(goal)
    val subtask = ScopeSubtask(2, "Two", ScopeSubtaskState.DONE, "c", listOf("a", "c"), false, emptyList())
    scopes.subtasks.value = SubtaskScopesState.Listed(
      "/repo", "main", true, listOf(subtask), emptyMap(),
      timeline = ChangeScopeTimeline("base", "c", listOf(
        ScopeCommit("a", "base", "first", listOf(2)),
        ScopeCommit("op", "a", "operator", emptyList()),
        ScopeCommit("c", "op", "last", listOf(2)),
      )),
    )
    val viewModel = ChangesWindowViewModel(goal, ChangeScope.All, details, scopes, opener, comments)
    advanceUntilIdle()
    viewModel.requestScope(ChangeScope.Subtasks(2, 2))

    viewModel.onAction(ChangesWindowAction.SetView(ChangesView.Commits))
    assertEquals(ChangeScope.Commits(listOf("a", "op", "c")), viewModel.state.value.scope)
    assertEquals(false, viewModel.state.value.quick.any { it.kind == QuickScope.Operator || it.kind == QuickScope.Running })
    viewModel.onAction(ChangesWindowAction.SetView(ChangesView.Subtasks))
    assertEquals(ChangeScope.Subtasks(2, 2), viewModel.state.value.scope)
  }

  @Test
  fun `operator quick selection shows the limit while all keeps its changes`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val viewModel = goalWindow(listOf(subtask(1)))
    val changed = file("src/Changed.kt")
    scopes.list(ChangeScope.All).value = listed(changed)
    scopes.list(ChangeScope.Operator).value = ScopeChangesState.TooManyCommits(200)
    advanceUntilIdle()

    viewModel.onAction(ChangesWindowAction.SelectQuickScope(QuickScope.Operator))
    advanceUntilIdle()
    assertEquals(ChangeScope.Operator, viewModel.state.value.scope)
    assertEquals(ScopeListUi.TooManyCommits(200), viewModel.state.value.list)

    viewModel.onAction(ChangesWindowAction.SelectQuickScope(QuickScope.All))
    advanceUntilIdle()
    assertEquals(ChangeScope.All, viewModel.state.value.scope)
    assertEquals(listOf(changed), (viewModel.state.value.list as ScopeListUi.Listed).changes.files)
  }

  @Test
  fun `a window opened on a subtask still counts all changes`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val goal = workItem("G")
    detailOf(goal)
    scopes.subtasks.value = SubtaskScopesState.Listed(
      "/repo", "main", rangesSupported = true, subtasks = listOf(subtask(1)), stats = emptyMap(), timeline = timelineFor(listOf(subtask(1))),
    )
    scopes.list(ChangeScope.All).value = listed(file("a/A.kt"), file("b/B.kt"))
    val viewModel = ChangesWindowViewModel(goal, ChangeScope.Subtasks(1, 1), details, scopes, opener, comments)
    advanceUntilIdle()

    assertEquals(ChangeScope.Subtasks(1, 1), viewModel.state.value.scope)
    assertEquals(2, viewModel.state.value.quick.first { it.kind == QuickScope.All }.count)
  }

  @Test
  fun `the diff stream reads wanted files once and following a scroll reuses them`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val phase = workItem("P", kind = WorkKind.Phase)
    detailOf(phase)
    val viewModel = ChangesWindowViewModel(phase, ChangeScope.All, details, scopes, opener, comments)
    scopes.list(ChangeScope.All).value = listed(file("a/A.kt"), file("b/B.kt"))
    advanceUntilIdle()
    val request = viewModel.state.value.scrollRequest

    viewModel.onAction(ChangesWindowAction.EnsureDiffs(listOf("a/A.kt", "b/B.kt")))
    advanceUntilIdle()
    assertEquals(setOf("a/A.kt", "b/B.kt"), viewModel.state.value.fileDiffs.keys)
    assertEquals(1, scopes.diffReads.count { it.second == "b/B.kt" })

    viewModel.onAction(ChangesWindowAction.FollowScroll("b/B.kt"))
    advanceUntilIdle()
    assertEquals("b/B.kt", viewModel.state.value.selectedPath)
    assertEquals("b/B.kt", (viewModel.state.value.diff as DiffPaneUi.Shown).file.path)
    assertEquals(1, scopes.diffReads.count { it.second == "b/B.kt" })
    assertEquals(request, viewModel.state.value.scrollRequest)

    viewModel.onAction(ChangesWindowAction.SelectFile("a/A.kt"))
    assertEquals(request + 1, viewModel.state.value.scrollRequest)
  }

  @Test
  fun `a refresh drops only the diffs of files that changed and re-reads the wanted ones`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val phase = workItem("P", kind = WorkKind.Phase)
    detailOf(phase)
    val viewModel = ChangesWindowViewModel(phase, ChangeScope.All, details, scopes, opener, comments)
    scopes.list(ChangeScope.All).value = listed(file("a/A.kt"), file("b/B.kt"), file("c/C.kt"))
    advanceUntilIdle()
    viewModel.onAction(ChangesWindowAction.EnsureDiffs(listOf("b/B.kt", "c/C.kt")))
    advanceUntilIdle()

    scopes.list(ChangeScope.All).value = listed(file("a/A.kt"), file("b/B.kt", insertions = 9))
    advanceUntilIdle()

    assertEquals(9, viewModel.state.value.fileDiffs.getValue("b/B.kt").file.insertions)
    assertEquals(2, scopes.diffReads.count { it.second == "b/B.kt" })
    assertEquals(false, "c/C.kt" in viewModel.state.value.fileDiffs)
  }

  @Test
  fun `a new scope forgets the loaded diffs`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val goal = workItem("G")
    detailOf(goal)
    scopes.subtasks.value = SubtaskScopesState.Listed(
      "/repo", "main", rangesSupported = true, subtasks = listOf(subtask(1)), stats = emptyMap(), timeline = timelineFor(listOf(subtask(1))),
    )
    val viewModel = ChangesWindowViewModel(goal, ChangeScope.All, details, scopes, opener, comments)
    scopes.list(ChangeScope.All).value = listed(file("a/A.kt"))
    advanceUntilIdle()
    viewModel.onAction(ChangesWindowAction.EnsureDiffs(listOf("a/A.kt")))
    advanceUntilIdle()

    viewModel.onAction(ChangesWindowAction.NarrowToSubtask(1))

    assertEquals(emptyMap(), viewModel.state.value.fileDiffs)
  }

  @Test
  fun `a rejected state change keeps the stored state and shows its reason`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val phase = workItem("P", kind = WorkKind.Phase)
    detailOf(phase)
    comments.state.value = DiffCommentsState.Available("main", listOf(
      CommentThread("thread", "src/A.kt", CommentSide.New, 2, null, CommentState.Open, false, null, null, null,
        listOf(Comment("comment", CommentAuthor.User, null, "Question", null, epoch, null))),
    ), canAuthor = true)
    comments.stateChange = Outcome.Failure(CommentRejection("closed"))
    val viewModel = ChangesWindowViewModel(phase, ChangeScope.All, details, scopes, opener, comments)
    advanceUntilIdle()

    viewModel.onAction(ChangesWindowAction.SetThreadState("thread", CommentState.Resolved))
    advanceUntilIdle()

    val available = viewModel.state.value.comments as DiffCommentsState.Available
    assertEquals(CommentState.Open, available.threads.single().state)
    assertEquals("closed", viewModel.state.value.threadUi.getValue("thread").problem)
  }

  @Test
  fun `file moves stop at the end and open skips deleted files`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val phase = workItem("P", kind = WorkKind.Phase)
    detailOf(phase)
    val viewModel = ChangesWindowViewModel(phase, ChangeScope.All, details, scopes, opener, comments)
    val deleted = ChangedFile("a/Gone.kt", null, ChangeStatus.Deleted, 0, 3)
    scopes.list(ChangeScope.All).value = listed(deleted, file("a/Kept.kt"))
    advanceUntilIdle()

    viewModel.onAction(ChangesWindowAction.OpenSelectedFile)
    advanceUntilIdle()
    assertEquals(emptyList(), opener.opened)

    viewModel.onAction(ChangesWindowAction.MoveFile(1))
    viewModel.onAction(ChangesWindowAction.MoveFile(1))
    assertEquals("a/Kept.kt", viewModel.state.value.selectedPath)

    opener.outcome = OpenOutcome.NotOpened
    viewModel.onAction(ChangesWindowAction.OpenSelectedFile)
    advanceUntilIdle()
    assertEquals(listOf("/repo" to "a/Kept.kt"), opener.opened)
    assertEquals(FILE_NOT_OPENED, viewModel.state.value.openProblem)

    viewModel.onAction(ChangesWindowAction.MoveFile(-1))
    assertEquals(null, viewModel.state.value.openProblem)
  }

  private fun TestScope.goalWindow(subtasks: List<ScopeSubtask>): ChangesWindowViewModel {
    val goal = workItem("G")
    detailOf(goal)
    val commits = subtasks.flatMap { subtask -> subtask.commitShas.mapIndexed { index, sha ->
      ScopeCommit(sha, "parent-$sha", sha, listOf(subtask.id))
    } }
    scopes.subtasks.value = SubtaskScopesState.Listed(
      "/repo", "main", rangesSupported = true, subtasks = subtasks, stats = emptyMap(),
      timeline = ChangeScopeTimeline("base", "head", commits),
    )
    val viewModel = ChangesWindowViewModel(goal, ChangeScope.All, details, scopes, opener, comments)
    advanceUntilIdle()
    return viewModel
  }

  private fun notStarted(id: Int) = ScopeSubtask(id, "Subtask $id", ScopeSubtaskState.NOT_STARTED, null, emptyList(), false, emptyList())

  private class RecordingOpener : WorkChangesRepository {
    var outcome = OpenOutcome.Opened
    val opened = mutableListOf<Pair<String, String>>()

    override fun observe(baseline: ChangeBaseline?, running: Boolean): Flow<ChangesState> = emptyFlow()

    override suspend fun fileDiff(baseline: ChangeBaseline, baseSha: String, file: ChangedFile): FileDiffState = FileDiffState.Binary

    override suspend fun open(baseline: ChangeBaseline, file: ChangedFile): OpenOutcome {
      opened += baseline.worktreeRoot to file.path
      return outcome
    }
  }

  private class FakeDiffComments : DiffCommentRepository {
    val state = MutableStateFlow<DiffCommentsState>(DiffCommentsState.Loading)
    var stateChange: Outcome<Unit, CommentRejection> = Outcome.Success(Unit)
    var submitResult: Outcome<Int, CommentRejection> = Outcome.Success(0)
    var pendingWrite: CompletableDeferred<Outcome<Unit, CommentRejection>>? = null
    var reviewWrite: CompletableDeferred<Outcome<Int, CommentRejection>>? = null
    var discardFailure: Throwable? = null

    override fun observe(baseline: ChangeBaseline): Flow<DiffCommentsState> = state
    override suspend fun reply(threadId: String, body: String): Outcome<Unit, CommentRejection> = Outcome.Success(Unit)
    override suspend fun setState(threadId: String, state: CommentState): Outcome<Unit, CommentRejection> = stateChange
    override suspend fun deleteComment(commentId: String): Outcome<Unit, CommentRejection> = Outcome.Success(Unit)
    override suspend fun addPending(baseline: ChangeBaseline, branch: String, path: String, side: CommentSide?, line: Int?, body: String): Outcome<Unit, CommentRejection> = pendingWrite?.await() ?: Outcome.Success(Unit)
    override suspend fun editComment(commentId: String, body: String): Outcome<Unit, CommentRejection> = Outcome.Success(Unit)
    override suspend fun submitReview(baseline: ChangeBaseline, branch: String): Outcome<Int, CommentRejection> {
      reviewWrite?.let { return it.await() }
      if (submitResult is Outcome.Success) state.value = availablePending().copy(threads = availablePending().threads.map { it.copy(state = CommentState.Open) })
      return submitResult
    }
    override suspend fun discardPending(baseline: ChangeBaseline, branch: String): Outcome<Int, CommentRejection> {
      discardFailure?.let { throw it }
      return Outcome.Success(0)
    }
  }

  private fun detailOf(item: WorkItem): MutableStateFlow<DetailState> {
    val flow = MutableStateFlow(DetailState(item, DetailSections.Loaded(detail(baseline), null, emptyMap(), emptySet(), stale = false)))
    details.details = mapOf(item.id to flow)
    return flow
  }

  private fun file(path: String, insertions: Int = 1) = ChangedFile(path, null, ChangeStatus.Modified, insertions, 0)

  private fun listed(vararg files: ChangedFile) = ScopeChangesState.Listed(ChangeSet("base", files.toList(), files.sumOf { it.insertions ?: 0 }, 0))

  private fun subtask(id: Int) = ScopeSubtask(id, "Subtask $id", ScopeSubtaskState.DONE, "end$id", listOf("end$id"), false, emptyList())

  private fun timelineFor(subtasks: List<ScopeSubtask>) = ChangeScopeTimeline(
    "base", "head", subtasks.flatMap { subtask -> subtask.commitShas.map { ScopeCommit(it, "parent-$it", it, listOf(subtask.id)) } },
  )

  private class FakeChangeScopes : WorkChangeScopesRepository {
    val subtasks = MutableStateFlow<SubtaskScopesState>(SubtaskScopesState.Loading)
    val observed = mutableListOf<ChangeScope>()
    val diffReads = mutableListOf<Pair<ChangeScope, String>>()
    private val lists = mutableMapOf<ChangeScope, MutableStateFlow<ScopeChangesState>>()

    /** Scopes read for the shown list; the window always observes Uncommitted and Outside for the quick buttons. */
    val shown: List<ChangeScope> get() = observed.filterNot { it == ChangeScope.Uncommitted || it == ChangeScope.Operator }

    fun list(scope: ChangeScope): MutableStateFlow<ScopeChangesState> = lists.getOrPut(scope) { MutableStateFlow(ScopeChangesState.Loading) }

    override fun observeSubtasks(goalWorkflowId: String, running: Boolean): Flow<SubtaskScopesState> = subtasks

    override fun observeCommitStats(target: ChangeScopeTarget): Flow<Map<String, SubtaskStats>> = emptyFlow()

    override fun observeScope(target: ChangeScopeTarget, scope: ChangeScope, running: Boolean): Flow<ScopeChangesState> {
      observed += scope
      return list(scope)
    }

    override suspend fun fileDiff(target: ChangeScopeTarget, scope: ChangeScope, file: ChangedFile): FileDiffState {
      diffReads += scope to file.path
      return FileDiffState.Binary
    }
  }

  private companion object {
    val epoch: Instant = Instant.fromEpochMilliseconds(0)

    fun availablePending() = DiffCommentsState.Available("main", listOf(
      CommentThread("pending-1", "src/A.kt", CommentSide.New, 10, null, CommentState.Pending, false, null, null, null, emptyList()),
      CommentThread("pending-2", "src/A.kt", CommentSide.New, 11, null, CommentState.Pending, false, null, null, null, emptyList()),
    ), canAuthor = true)

    fun detail(baseline: ChangeBaseline) = Detail(
      featureTitle = null, workflowId = null, invocationId = null, activeAgent = null, executionLiveness = null,
      updatedAt = epoch, lastAgentActivityLabel = null, lastAgentActivityAt = null, currentSubtaskStartedAt = null,
      subtaskActiveDurationMs = null, subtaskActiveDurationAsOf = null, qaVerdict = null, qaOutcome = null, stepPipeline = emptyList(),
      goalFinishPipeline = emptyList(), currentPhase = null, completeCount = 0, pendingCount = 0, blockedCount = 0, subtasks = emptyList(),
      phases = emptyList(), dependencies = emptyList(), attempts = DetailAttempts(emptyMap(), emptyMap(), emptyMap(), null, 0),
      validation = emptyList(), phaseResults = emptyList(), goalQa = null, goalAssessment = null, latestActivity = LatestActivity(null, null),
      executionHistory = emptyList(), incident = null, incidentHistory = emptyList(), goalSpecBundle = null, observedAt = epoch,
      changeBaseline = baseline,
    )
  }
}
