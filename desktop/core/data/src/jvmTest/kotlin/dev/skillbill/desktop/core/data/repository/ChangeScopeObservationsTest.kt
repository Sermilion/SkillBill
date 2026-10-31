package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.ScopeChangesState
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.model.EnvironmentContext
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.workchanges.WorkChangesPort
import skillbill.ports.workchanges.model.WorkChangeListResult
import skillbill.ports.workchanges.model.WorkChangedFile
import skillbill.ports.workchanges.model.WorkCommitRange
import skillbill.ports.workchanges.model.WorkChangeStatus
import skillbill.ports.workchanges.model.WorkFileDiffResult
import skillbill.ports.workchanges.model.WorkGoalScopesResult
import skillbill.ports.workchanges.model.WorkOutsideCommit
import skillbill.ports.workchanges.model.WorkOutsideCommitsResult
import skillbill.ports.workchanges.model.WorkScopeSubtask
import skillbill.workflow.model.DecompositionStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ChangeScopeObservationsTest {
  private val root = Files.createTempDirectory("change-scope-observations")
  private val runtime = RuntimeComponent::class.create(
    RuntimeContext(
      EnvironmentContext(root.resolve("work.db").toString(), environment = emptyMap(), userHome = root, repositoryRoot = root),
      TransportContext(),
      WorkflowOpsContext(),
      OptionalCallbacks(),
    ),
  )

  @Test
  fun `refreshes a working tree scope after each three second interval`() = runTest {
    val port = CountingPort()
    val cache = CommittedChangeCache()
    val lock = Mutex()
    val observations = observations(port)
    val target = ChangeScopeTarget(
      "run", root.toString(), ChangeBaseline.SubtaskStart(root.toString(), "base", emptyList()), emptyList(), false,
    )
    val job = launch { observations.observeScope(target, ChangeScope.All, true, lock, cache).collect { } }
    runCurrent()
    assertEquals(1, port.changeCalls)
    advanceTimeBy(CHANGE_REFRESH_INTERVAL)
    runCurrent()
    assertEquals(2, port.changeCalls)
    job.cancel()
  }

  @Test
  fun `reads a committed range once and reuses it across collections`() = runTest {
    val port = CountingPort()
    val cache = CommittedChangeCache()
    val lock = Mutex()
    val observations = observations(port)
    val target = ChangeScopeTarget(
      "goal",
      root.toString(),
      ChangeBaseline.TargetMergeBase(root.toString(), "main"),
      listOf(
        ScopeSubtask(4, "four", ScopeSubtaskState.DONE, "from", "middle", emptyList()),
        ScopeSubtask(6, "six", ScopeSubtaskState.DONE, "middle", "to", emptyList()),
      ),
      true,
    )
    val scope = ChangeScope.Subtasks(4, 6)
    val first = mutableListOf<ScopeChangesState>()
    val job = launch { observations.observeScope(target, scope, true, lock, cache).collect { first += it } }
    runCurrent()
    advanceTimeBy(12_000)
    runCurrent()
    job.cancel()
    observations.observeScope(target, scope, false, lock, cache).toList()
    assertEquals(1, port.committedListCalls)
    assertEquals(2, first.size)
  }

  @Test
  fun `waits for the shared git mutex before calling the port`() = runTest {
    val port = CountingPort()
    val lock = Mutex(locked = true)
    val observations = observations(port)
    val target = ChangeScopeTarget(
      "run", root.toString(), ChangeBaseline.SubtaskStart(root.toString(), "base", emptyList()), emptyList(), false,
    )
    val job = launch { observations.observeScope(target, ChangeScope.All, false, lock, CommittedChangeCache()).collect { } }
    runCurrent()
    assertEquals(0, port.changeCalls)
    lock.unlock()
    runCurrent()
    assertEquals(1, port.changeCalls)
    job.join()
  }

  @Test
  fun `outside scope refreshes when head changes without a range change`() = runTest {
    val port = CountingPort()
    val observations = observations(port)
    val target = ChangeScopeTarget(
      "goal", root.toString(), ChangeBaseline.TargetMergeBase(root.toString(), "main"),
      listOf(ScopeSubtask(1, "one", ScopeSubtaskState.DONE, "start", "end", emptyList())), true,
    )
    val states = mutableListOf<ScopeChangesState>()
    val job = launch { observations.observeScope(target, ChangeScope.Outside, true, Mutex(), CommittedChangeCache()).collect { states += it } }
    runCurrent()
    port.outsideResult = WorkOutsideCommitsResult.Listed(
      "base", listOf(WorkOutsideCommit("new", "parent", "new commit", emptyList(), 0, 0)),
    )
    advanceTimeBy(CHANGE_REFRESH_INTERVAL)
    runCurrent()
    assertEquals(listOf("new"), (states.last() as ScopeChangesState.Listed).outsideCommits.map { it.sha })
    job.cancel()
  }

  @Test
  fun `outside file diff parses each matching commit and labels hunks in commit order`() = runTest {
    val file = WorkChangedFile("Foo.kt", null, WorkChangeStatus.MODIFIED, 1, 0)
    val port = CountingPort().apply {
      outsideResult = WorkOutsideCommitsResult.Listed(
        "merge-base",
        listOf(
          WorkOutsideCommit("1234567890", "parent1", "first", listOf(file), 1, 0),
          WorkOutsideCommit("abcdef12345", "parent2", "second", listOf(file), 1, 0),
          WorkOutsideCommit("ignored", "parent3", "other", listOf(file.copy(path = "Other.kt")), 1, 0),
        ),
      )
      patches["1234567890"] = "@@ -1,0 +1 @@\n+first line\n"
      patches["abcdef12345"] = "@@ -2,0 +2 @@\n+second line\n"
    }
    val observations = observations(port)
    val target = ChangeScopeTarget(
      "goal", root.toString(), ChangeBaseline.TargetMergeBase(root.toString(), "main"),
      listOf(ScopeSubtask(1, "one", ScopeSubtaskState.DONE, "start", "end", emptyList())), true,
    )
    val state = observations.fileDiff(
      target,
      ChangeScope.Outside,
      ChangedFile("Foo.kt", null, ChangeStatus.Modified, 2, 0),
      Mutex(),
      CommittedChangeCache(),
    ) as FileDiffState.Shown
    assertEquals(listOf("1234567", "abcdef1"), state.diff.hunks.map { it.source })
    assertEquals(2, state.diff.insertions)
    assertEquals(listOf("1234567890", "abcdef12345"), port.patchReads)
  }

  @Test
  fun `too many outside commits do not trigger patch reads`() = runTest {
    val port = CountingPort().apply { outsideResult = WorkOutsideCommitsResult.TooMany(200) }
    val observations = observations(port)
    val target = ChangeScopeTarget(
      "goal", root.toString(), ChangeBaseline.TargetMergeBase(root.toString(), "main"),
      listOf(ScopeSubtask(1, "one", ScopeSubtaskState.DONE, "start", "end", emptyList())), true,
    )
    val state = observations.fileDiff(
      target,
      ChangeScope.Outside,
      ChangedFile("Foo.kt", null, ChangeStatus.Modified, 1, 0),
      Mutex(),
      CommittedChangeCache(),
    )
    assertEquals(FileDiffState.Unavailable(dev.skillbill.desktop.core.domain.model.ReadProblem.Runtime("More than 200 commits outside subtasks.")), state)
    assertEquals(emptyList(), port.patchReads)
  }

  @Test
  fun `reads running subtask stats first and reuses completed stats across refreshes and collections`() = runTest {
    val port = CountingPort()
    val cache = CommittedChangeCache()
    val subtasks = (1..4).map { subtask(it, DecompositionStatus.COMPLETE, start = "s$it", end = "e$it") } +
      subtask(5, DecompositionStatus.IN_PROGRESS, running = true, start = "s5")
    val scopes = WorkGoalScopesResult.Listed(root.toString(), "main", true, subtasks)
    val observations = observations(port, { scopes })
    val states = mutableListOf<SubtaskScopesState>()
    val job = launch { observations.observeSubtasks("goal", true, Mutex(), cache).collect { states += it } }
    runCurrent()
    assertEquals(listOf("working", "committed:e1", "committed:e2", "committed:e3", "committed:e4"), port.readOrder.take(5))
    assertEquals(4, port.committedListCalls)
    advanceTimeBy(10_000)
    runCurrent()
    assertEquals(4, port.changeCalls)
    assertEquals(4, port.committedListCalls)
    val firstListed = states.filterIsInstance<SubtaskScopesState.Listed>().first()
    assertEquals(emptyMap<Int, SubtaskStats>(), firstListed.stats)
    job.cancel()
    observations.observeSubtasks("goal", false, Mutex(), cache).toList()
    assertEquals(4, port.committedListCalls)
  }

  @Test
  fun `omits a failed subtask statistic and reports the failure once across refreshes`() = runTest {
    val port = CountingPort().apply {
      committedResults["e12"] = WorkChangeListResult.BaseUnavailable("gone")
    }
    val diagnostics = RecordingDiagnostics()
    val subtasks = listOf(
      subtask(12, DecompositionStatus.BLOCKED, start = "s12", end = "e12"),
      subtask(13, DecompositionStatus.PENDING, start = "s13"),
      subtask(14, DecompositionStatus.IN_PROGRESS, running = true, start = "s14"),
    )
    val observations = observations(
      port,
      { WorkGoalScopesResult.Listed(root.toString(), "main", true, subtasks) },
      diagnostics,
    )
    val states = mutableListOf<SubtaskScopesState>()
    val job = launch { observations.observeSubtasks("goal", true, Mutex(), CommittedChangeCache()).collect { states += it } }
    runCurrent()
    advanceTimeBy(CHANGE_REFRESH_INTERVAL * 3)
    runCurrent()
    job.cancel()
    val listed = states.filterIsInstance<SubtaskScopesState.Listed>().last()
    assertEquals(ScopeSubtaskState.INTERRUPTED, listed.subtasks.first { it.id == 12 }.state)
    assertEquals(ScopeSubtaskState.NOT_STARTED, listed.subtasks.first { it.id == 13 }.state)
    assertEquals(ScopeSubtaskState.RUNNING, listed.subtasks.first { it.id == 14 }.state)
    assertEquals(false, 12 in listed.stats)
    assertEquals(1, diagnostics.records.count { it == SCOPES_READ_FAILED })
  }

  private fun TestScope.observations(
    port: WorkChangesPort,
    readSubtasks: suspend (String) -> WorkGoalScopesResult = { WorkGoalScopesResult.NotAGoal("not-a-goal") },
    diagnostics: Diagnostics = NoopDiagnostics,
  ) = ChangeScopeObservations(
    runtime,
    diagnostics,
    readSubtasks,
    port,
    StandardTestDispatcher(testScheduler),
  )

  private class CountingPort : WorkChangesPort {
    var changeCalls = 0
    var committedListCalls = 0
    val readOrder = mutableListOf<String>()
    val committedResults = mutableMapOf<String, WorkChangeListResult>()
    var outsideResult: WorkOutsideCommitsResult = WorkOutsideCommitsResult.Listed("base", emptyList())
    val patches = mutableMapOf<String, String>()
    val patchReads = mutableListOf<String>()

    override fun changes(baseline: WorkChangeBaseline): WorkChangeListResult {
      changeCalls++
      readOrder += "working"
      return WorkChangeListResult.Listed("head", emptyList(), 0, 0)
    }

    override fun fileDiff(worktreeRoot: String, baseSha: String, file: WorkChangedFile): WorkFileDiffResult = WorkFileDiffResult.Binary

    override fun committedChanges(worktreeRoot: String, fromSha: String, toSha: String): WorkChangeListResult {
      committedListCalls++
      readOrder += "committed:$toSha"
      return committedResults[toSha] ?: WorkChangeListResult.Listed(toSha, emptyList(), 0, 0)
    }

    override fun committedFileDiff(
      worktreeRoot: String,
      fromSha: String,
      toSha: String,
      file: WorkChangedFile,
    ): WorkFileDiffResult {
      patchReads += toSha
      return patches[toSha]?.let { WorkFileDiffResult.Patch(it) } ?: WorkFileDiffResult.Binary
    }

    override fun uncommittedChanges(worktreeRoot: String): WorkChangeListResult = WorkChangeListResult.Listed("head", emptyList(), 0, 0)

    override fun outsideCommits(
      worktreeRoot: String,
      targetBranch: String?,
      subtaskRanges: List<WorkCommitRange>,
    ): WorkOutsideCommitsResult = outsideResult
  }

  private object NoopDiagnostics : Diagnostics {
    override fun record(category: String) = Unit

    override fun recordRequest(category: String, fields: Map<String, Number>) = Unit
  }

  private class RecordingDiagnostics : Diagnostics {
    val records = mutableListOf<String>()

    override fun record(category: String) {
      records += category
    }

    override fun recordRequest(category: String, fields: Map<String, Number>) = Unit
  }

  private fun subtask(
    id: Int,
    status: DecompositionStatus,
    running: Boolean = false,
    start: String,
    end: String? = null,
  ) = WorkScopeSubtask(id, "subtask $id", status, running, start, end, emptyList())
}
