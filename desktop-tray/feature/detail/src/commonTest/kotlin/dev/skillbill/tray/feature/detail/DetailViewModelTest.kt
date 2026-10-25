package dev.skillbill.tray.feature.detail

import dev.skillbill.tray.core.domain.model.ControlConfirmation
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.DetailList
import dev.skillbill.tray.core.domain.model.ExecutionScope
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.RunOptions
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.model.WorkSubtask
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.DetailReadCall
import dev.skillbill.tray.core.testing.FakeWorkBoardRepository
import dev.skillbill.tray.core.testing.FakeWorkControlRepository
import dev.skillbill.tray.core.testing.FakeWorkDetailRepository
import dev.skillbill.tray.core.testing.TestClock
import dev.skillbill.tray.core.testing.board
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.AppendState
import dev.skillbill.tray.core.ui.FeedbackTone
import dev.skillbill.tray.core.ui.LaunchRequests
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val details = FakeWorkDetailRepository()
  private val controls = FakeWorkControlRepository()
  private val boards = FakeWorkBoardRepository()
  private val clock = TestClock(fixtureInstant)
  private val identity = workItem("a").identity

  @BeforeTest
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun controllable(): WorkItem = workItem("a").copy(controlEligible = true, pauseEligible = true)

  private fun subtask(id: Int, status: String = "pending") = WorkSubtask(id, "Subtask $id", status, null, null, emptyList())

  private fun launchOptions() = RunOptions(canCancel = true, canLaunch = true, launchLabel = "Launch", agentId = null, agentChoices = emptyList(), unavailableReason = null)

  private fun TestScope.viewModel(first: Detail? = detail(controllable())): DetailViewModel {
    val vm = DetailViewModel(identity, details, controls, boards, clock, LaunchRequests(backgroundScope, controls, boards, clock))
    runCurrent()
    if (first != null) show(first)
    return vm
  }

  private fun TestScope.show(next: Detail) {
    details.emit(Outcome.Value(next))
    runCurrent()
  }

  /** Reports the boards a launch observes; [recent] is the finished section, the others report no live work. */
  private fun emitBoards(recent: List<WorkItem> = emptyList()) {
    listOf(WorkSection.NEEDS_YOU, WorkSection.GOALS, WorkSection.PHASES).forEach { boards.emit(it, Outcome.Value(board(emptyList()))) }
    boards.emit(WorkSection.RECENT, Outcome.Value(board(recent)))
  }

  private fun DetailViewModel.loaded(): DetailLoad.Loaded = assertIs<DetailLoad.Loaded>(state.value.load)

  private fun DetailViewModel.subtaskIds(): List<Int> = loaded().detail.subtasks.map { it.id }

  @Test
  fun `a failed first read is shown as failed and the next read replaces it`() = runTest(dispatcher) {
    val vm = viewModel(first = null)
    assertEquals(DetailLoad.Loading, vm.state.value.load)
    val problem = Problem(ProblemCode.DATABASE_ACCESS, "Unreadable")

    details.emit(Outcome.Failure(problem))
    runCurrent()
    assertEquals(DetailLoad.Failed(problem), vm.state.value.load)

    show(detail(controllable()))
    assertEquals(null, vm.state.value.readHealth.reportedProblem)
  }

  @Test
  fun `a failed refresh keeps the detail with a read error that blocks controls until the next good read`() = runTest(dispatcher) {
    val vm = viewModel()
    val problem = Problem(ProblemCode.BUSY, "Store is busy")
    details.onDetail = { Outcome.Failure(problem) }

    details.emit(Outcome.Failure(problem))
    runCurrent()
    assertNull(vm.state.value.readHealth.reportedProblem)
    assertIs<DetailReadHealth.GracePeriod>(vm.state.value.readHealth)

    vm.onAction(DetailAction.Pause)
    runCurrent()
    assertTrue(controls.calls.isEmpty())
    advanceTimeBy(250)
    details.emit(Outcome.Failure(problem))
    runCurrent()
    advanceTimeBy(249)
    runCurrent()
    assertNull(vm.state.value.readHealth.reportedProblem)
    advanceTimeBy(1)
    runCurrent()
    assertEquals(problem, vm.state.value.readHealth.reportedProblem)
    assertIs<DetailReadHealth.Failed>(vm.state.value.readHealth)

    show(detail(controllable()))
    assertEquals(DetailReadHealth.Healthy, vm.state.value.readHealth)
    vm.onAction(DetailAction.Pause)
    runCurrent()
    assertEquals(listOf("pause:a"), controls.calls)
  }

  @Test
  fun `a successful observation during the grace period cancels the warning and retry`() = runTest(dispatcher) {
    val vm = viewModel()
    val before = vm.loaded().detail
    details.emit(Outcome.Failure(Problem(ProblemCode.BUSY, "Detail changed during this read. Retry.")))
    runCurrent()
    advanceTimeBy(499)
    runCurrent()
    assertEquals(before, vm.loaded().detail)
    assertNull(vm.state.value.readHealth.reportedProblem)

    show(before.copy(item = before.item.copy(summary = "Updated")))
    advanceTimeBy(1000)
    runCurrent()
    assertNull(vm.state.value.readHealth.reportedProblem)
    assertTrue(details.readCalls.isEmpty())
    assertEquals("Updated", vm.loaded().detail.item.summary)
  }

  @Test
  fun `a failed refresh retries after the grace period even without another observation`() = runTest(dispatcher) {
    val vm = viewModel()
    details.onDetail = { Outcome.Value(detail(controllable()).copy(featureTitle = "Recovered")) }
    details.emit(Outcome.Failure(Problem(ProblemCode.BUSY, "Store is busy")))
    runCurrent()
    advanceTimeBy(500)
    runCurrent()

    assertEquals("Recovered", vm.loaded().detail.featureTitle)
    assertNull(vm.state.value.readHealth.reportedProblem)
    assertEquals(1, details.readCalls.size)
  }

  @Test
  fun `a control the runtime does not offer is refused without calling it`() = runTest(dispatcher) {
    val vm = viewModel(detail(workItem("a")))

    listOf(DetailAction.Pause, DetailAction.Stop, DetailAction.Cancel, DetailAction.Launch(null)).forEach(vm::onAction)
    runCurrent()

    assertTrue(controls.calls.isEmpty())
    assertIs<ControlState.Refused>(vm.state.value.control)
  }

  @Test
  fun `one control runs at a time and its confirmation names only what was confirmed`() = runTest(dispatcher) {
    val vm = viewModel()
    val gate = CompletableDeferred<Outcome<ControlConfirmation>>()
    controls.onPause = { gate.await() }

    vm.onAction(DetailAction.Pause)
    vm.onAction(DetailAction.Pause)
    vm.onAction(DetailAction.Stop)
    runCurrent()

    assertEquals(ControlState.InProgress(ControlKind.PAUSE), vm.state.value.control)
    assertEquals(listOf("pause:a"), controls.calls)

    gate.complete(Outcome.Value(ControlConfirmation.INTENT_RECORDED))
    runCurrent()

    assertEquals(ControlState.Idle, vm.state.value.control)
    val feedback = vm.state.value.pendingFeedback.single()
    assertEquals(FeedbackTone.CONFIRMATION, feedback.tone)
    assertEquals("Pause recorded. The goal pauses at its next checkpoint.", feedback.message)
    assertEquals(1, boards.refreshCount)
  }

  @Test
  fun `a failed control becomes problem feedback and still re-reads the board`() = runTest(dispatcher) {
    val vm = viewModel()
    controls.onStop = { Outcome.Failure(Problem(ProblemCode.BUSY, "Runner is busy")) }

    vm.onAction(DetailAction.Stop)
    runCurrent()

    val feedback = vm.state.value.pendingFeedback.single()
    assertEquals(FeedbackTone.PROBLEM, feedback.tone)
    assertEquals("Runner is busy", feedback.message)
    assertEquals(ControlState.Refused(ControlKind.STOP, Problem(ProblemCode.BUSY, "Runner is busy"), feedback.id), vm.state.value.control)
    assertEquals(1, boards.refreshCount)
  }

  @Test
  fun `cancel says nothing of its own and relies on the board refresh`() = runTest(dispatcher) {
    val vm = viewModel(detail(controllable().copy(controlEligible = false)).copy(runOptions = launchOptions()))

    vm.onAction(DetailAction.Cancel)
    runCurrent()

    assertEquals(listOf("cancel:a"), controls.calls)
    assertTrue(vm.state.value.pendingFeedback.isEmpty())
    assertEquals(1, boards.refreshCount)
  }

  @Test
  fun `an answer for a replaced store is dropped without feedback or refresh`() = runTest(dispatcher) {
    val vm = viewModel(detail(controllable().copy(databaseGeneration = 1)))
    val gate = CompletableDeferred<Outcome<ControlConfirmation>>()
    controls.onPause = { gate.await() }
    vm.onAction(DetailAction.Pause)
    runCurrent()

    show(detail(controllable().copy(databaseGeneration = 2)))
    gate.complete(Outcome.Value(ControlConfirmation.INTENT_RECORDED))
    runCurrent()

    assertTrue(vm.state.value.pendingFeedback.isEmpty())
    assertEquals(0, boards.refreshCount)
    assertEquals(ControlState.Idle, vm.state.value.control)
  }

  @Test
  fun `pending feedback keeps the newest entries with increasing ids`() = runTest(dispatcher) {
    val vm = viewModel()
    controls.onStop = { Outcome.Failure(Problem(ProblemCode.BUSY, "Runner is busy")) }

    repeat(10) {
      vm.onAction(DetailAction.Stop)
      runCurrent()
    }
    assertEquals((3L..10L).toList(), vm.state.value.pendingFeedback.map { it.id })

    vm.onAction(DetailAction.AcknowledgeFeedback(5))
    assertEquals(7, vm.state.value.pendingFeedback.size)
  }

  @Test
  fun `a launch of a standalone phase hands the selection to the attempt that registers`() = runTest(dispatcher) {
    clock.current = fixtureInstant.plus(10.seconds)
    val running = controllable().copy(
      scope = ExecutionScope.STANDALONE_PHASE,
      phaseId = "validation",
      startedAt = fixtureInstant,
    )
    val vm = viewModel(detail(running).copy(runOptions = launchOptions()))

    vm.onAction(DetailAction.Launch("codex"))
    runCurrent()
    emitBoards()
    runCurrent()
    assertEquals(listOf("launch:a:codex"), controls.calls)
    assertEquals(ControlState.InProgress(ControlKind.LAUNCH), vm.state.value.control, "The launch is followed until an attempt registers.")

    val successor = workItem("b", issueKey = running.issueKey).copy(
      identity = WorkIdentity("b", identity.repositoryIdentity),
      phaseId = "validation",
      startedAt = fixtureInstant.plus(15.seconds),
    )
    emitBoards(recent = listOf(successor))
    runCurrent()

    assertEquals(listOf<DetailEffect>(DetailEffect.SelectSuccessor(identity, successor.identity)), vm.effects.take(1).toList())
    assertEquals(ControlState.Idle, vm.state.value.control)
  }

  @Test
  fun `a monitor refused during admission replaces the canceled selection without waiting for the launch timeout`() = runTest(dispatcher) {
    clock.current = fixtureInstant.plus(10.seconds)
    val canceled = controllable().copy(
      scope = ExecutionScope.STANDALONE_PHASE,
      phaseId = "monitor",
      lifecycle = Lifecycle.CANCELED,
      startedAt = fixtureInstant,
    )
    val vm = viewModel(detail(canceled).copy(runOptions = launchOptions()))
    vm.onAction(DetailAction.Launch("codex"))
    runCurrent()
    emitBoards()
    runCurrent()

    val blocked = canceled.copy(
      identity = WorkIdentity("blocked-monitor", identity.repositoryIdentity),
      lifecycle = Lifecycle.BLOCKED,
      liveSection = WorkSection.NEEDS_YOU,
      startedAt = fixtureInstant.plus(11.seconds),
      summary = "The previous monitor has unpublished commits.",
    )
    emitBoards()
    boards.emit(WorkSection.NEEDS_YOU, Outcome.Value(board(listOf(blocked))))
    runCurrent()

    assertEquals(ControlState.Idle, vm.state.value.control)
    assertEquals(listOf<DetailEffect>(DetailEffect.SelectSuccessor(identity, blocked.identity)), vm.effects.take(1).toList())
  }

  @Test
  fun `a launch whose attempt never registers ends with a problem after two minutes`() = runTest(dispatcher) {
    val vm = viewModel(detail(controllable().copy(scope = ExecutionScope.STANDALONE_PHASE, phaseId = "validation")).copy(runOptions = launchOptions()))

    vm.onAction(DetailAction.Launch(null))
    runCurrent()
    emitBoards()
    runCurrent()
    advanceTimeBy(119.seconds)
    runCurrent()
    assertEquals(ControlState.InProgress(ControlKind.LAUNCH), vm.state.value.control)

    advanceTimeBy(2.seconds)
    runCurrent()

    assertEquals(ControlState.Idle, vm.state.value.control)
    val feedback = vm.state.value.pendingFeedback.single()
    assertEquals(FeedbackTone.PROBLEM, feedback.tone)
    assertTrue(feedback.message.startsWith("The harness started but has not registered a new attempt yet"))
  }

  @Test
  fun `each list reads its own cursor and an exhausted list stops offering more`() = runTest(dispatcher) {
    val vm = viewModel(detail(controllable()).copy(subtasks = listOf(subtask(1)), cursors = mapOf(DetailList.SUBTASKS to "s1", DetailList.PHASES to "p1")))
    details.onDetail = { Outcome.Value(detail(controllable()).copy(subtasks = listOf(subtask(2)))) }
    assertEquals(AppendState.Idle, vm.state.value.append(DetailList.SUBTASKS))

    vm.onAction(DetailAction.LoadMore(DetailList.SUBTASKS))
    runCurrent()

    assertEquals(listOf(DetailReadCall(identity, "s1", DetailList.SUBTASKS, null)), details.readCalls)
    assertEquals(listOf(1, 2), vm.subtaskIds())
    assertEquals(AppendState.Exhausted, vm.state.value.append(DetailList.SUBTASKS))
    assertEquals(AppendState.Idle, vm.state.value.append(DetailList.PHASES))
  }

  @Test
  fun `a failed append keeps the rows and can be retried while a second request waits for the first`() = runTest(dispatcher) {
    val vm = viewModel(detail(controllable()).copy(subtasks = listOf(subtask(1)), cursors = mapOf(DetailList.SUBTASKS to "s1")))
    val problem = Problem(ProblemCode.DATABASE_ACCESS, "Timed out")
    details.onDetail = { Outcome.Failure(problem) }

    vm.onAction(DetailAction.LoadMore(DetailList.SUBTASKS))
    runCurrent()
    assertEquals(AppendState.Failed(problem), vm.state.value.append(DetailList.SUBTASKS))
    assertEquals(listOf(1), vm.subtaskIds())

    val gate = CompletableDeferred<Outcome<Detail>>()
    details.onDetail = { gate.await() }
    vm.onAction(DetailAction.LoadMore(DetailList.SUBTASKS))
    vm.onAction(DetailAction.LoadMore(DetailList.SUBTASKS))
    runCurrent()
    assertEquals(2, details.readCalls.size, "The failed read and exactly one retry.")
    assertEquals(AppendState.Loading, vm.state.value.append(DetailList.SUBTASKS))

    gate.complete(Outcome.Value(detail(controllable()).copy(subtasks = listOf(subtask(2)))))
    runCurrent()
    assertEquals(listOf(1, 2), vm.subtaskIds())
  }

  @Test
  fun `a refresh re-reads only the lists that kept more than their first page`() = runTest(dispatcher) {
    val vm = viewModel(detail(controllable()).copy(subtasks = listOf(subtask(1)), cursors = mapOf(DetailList.SUBTASKS to "s1")))
    details.onDetail = { Outcome.Value(detail(controllable()).copy(subtasks = listOf(subtask(2)))) }
    vm.onAction(DetailAction.LoadMore(DetailList.SUBTASKS))
    runCurrent()
    val before = details.readCalls.size
    val gate = CompletableDeferred<Outcome<Detail>>()
    details.onDetail = { gate.await() }

    show(detail(controllable()).copy(subtasks = listOf(subtask(1, "done")), cursors = mapOf(DetailList.SUBTASKS to "s1")))

    assertTrue(vm.loaded().refreshing, "The old rows stay on screen while the kept pages are read again.")
    assertEquals(listOf(1, 2), vm.subtaskIds())
    gate.complete(Outcome.Value(detail(controllable()).copy(subtasks = listOf(subtask(2, "done")))))
    runCurrent()

    assertEquals(listOf(DetailReadCall(identity, "s1", DetailList.SUBTASKS, null)), details.readCalls.drop(before))
    assertTrue(!vm.loaded().refreshing)
    assertEquals(listOf("done", "done"), vm.loaded().detail.subtasks.map { it.status })
  }

  @Test
  fun `a window that starts after an anchor is re-read from that anchor`() = runTest(dispatcher) {
    val first = detail(controllable()).copy(subtasks = listOf(subtask(5)), anchors = mapOf(DetailList.SUBTASKS to "after-4"))
    val vm = viewModel(first)
    details.onDetail = { Outcome.Value(detail(controllable()).copy(subtasks = listOf(subtask(5, "done")), anchors = first.anchors)) }

    show(first)

    assertEquals(listOf(DetailReadCall(identity, null, DetailList.SUBTASKS, "after-4")), details.readCalls)
    assertEquals("done", vm.loaded().detail.subtasks.single().status)
  }

  @Test
  fun `a list that became shorter ends sooner without reading missing pages`() = runTest(dispatcher) {
    val vm = viewModel(detail(controllable()).copy(subtasks = listOf(subtask(1)), cursors = mapOf(DetailList.SUBTASKS to "s1")))
    details.onDetail = { Outcome.Value(detail(controllable()).copy(subtasks = listOf(subtask(2)))) }
    vm.onAction(DetailAction.LoadMore(DetailList.SUBTASKS))
    runCurrent()
    val before = details.readCalls.size

    show(detail(controllable()).copy(subtasks = listOf(subtask(1))))

    assertEquals(before, details.readCalls.size)
    assertEquals(listOf(1), vm.subtaskIds())
    assertEquals(AppendState.Exhausted, vm.state.value.append(DetailList.SUBTASKS))
  }

  @Test
  fun `a refresh that cannot read a kept page leaves the old detail with a read error`() = runTest(dispatcher) {
    val vm = viewModel(detail(controllable()).copy(subtasks = listOf(subtask(1)), cursors = mapOf(DetailList.SUBTASKS to "s1")))
    details.onDetail = { Outcome.Value(detail(controllable()).copy(subtasks = listOf(subtask(2)))) }
    vm.onAction(DetailAction.LoadMore(DetailList.SUBTASKS))
    runCurrent()
    val problem = Problem(ProblemCode.DATABASE_ACCESS, "Timed out")
    details.onDetail = { Outcome.Failure(problem) }

    show(detail(controllable()).copy(subtasks = listOf(subtask(1)), cursors = mapOf(DetailList.SUBTASKS to "s1")))
    advanceTimeBy(500)
    runCurrent()

    val loaded = vm.loaded()
    assertEquals(problem, vm.state.value.readHealth.reportedProblem)
    assertTrue(!loaded.refreshing)
    assertEquals(listOf(1, 2), vm.subtaskIds())
  }

  @Test
  fun `a refresh action asks the board to re-read`() = runTest(dispatcher) {
    val vm = viewModel()

    vm.onAction(DetailAction.Refresh)
    runCurrent()

    assertEquals(1, boards.refreshCount)
  }
}
