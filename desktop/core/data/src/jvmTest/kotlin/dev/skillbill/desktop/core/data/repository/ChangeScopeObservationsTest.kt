package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangeScopeTimeline
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.ScopeChangesState
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.ScopeCommit
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import dev.skillbill.desktop.core.domain.model.TimelineProblem
import dev.skillbill.desktop.core.domain.model.resolveScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.model.EnvironmentContext
import skillbill.ports.workchanges.WorkChangesPort
import skillbill.ports.workchanges.model.WorkChangeListResult
import skillbill.ports.workchanges.model.WorkChangedFile
import skillbill.ports.workchanges.model.WorkChangeStatus
import skillbill.ports.workchanges.model.WorkCommitTimelineResult
import skillbill.ports.workchanges.model.WorkFileDiffResult
import skillbill.ports.workchanges.model.WorkGoalScopesResult
import skillbill.ports.workchanges.model.WorkGoalTimelineResult
import skillbill.ports.workchanges.model.WorkScopeSubtask
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.workchanges.model.WorkTimelineCommit
import skillbill.workflow.model.DecompositionStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChangeScopeObservationsTest {
  private val root = Files.createTempDirectory("change-scope-observations")
  private val spanFile = ChangedFile("shared.kt", null, ChangeStatus.Modified, 1, 0)
  private val runtime = RuntimeComponent::class.create(
    RuntimeContext(
      EnvironmentContext(root.resolve("work.db").toString(), environment = emptyMap(), userHome = root, repositoryRoot = root),
      TransportContext(), WorkflowOpsContext(), OptionalCallbacks(),
    ),
  )

  @Test
  fun `reads timeline after subtask refresh and maps owned commits`() = runTest {
    val port = CountingPort()
    val diagnostics = RecordingDiagnostics()
    var timelineReads = 0
    val scopes = listed()
    val observations = observations(port, scopes, diagnostics) {
      timelineReads++
      WorkGoalTimelineResult.Attributed(
        "base", "head", listOf(WorkTimelineCommit("commit-1", "base", "subject", listOf(1))), emptySet(),
      )
    }

    val state = observations.observeSubtasks("goal", false, Mutex(), CommittedChangeCache()).toList()
      .filterIsInstance<SubtaskScopesState.Listed>().single()

    assertEquals(1, timelineReads)
    assertEquals(listOf("commit-1"), state.subtasks.single().commitShas)
    assertEquals("base", state.timeline?.mergeBaseSha)
    assertEquals(emptyList(), diagnostics.records)
  }

  @Test
  fun `timeline failure keeps subtask metadata and reports once`() = runTest {
    val diagnostics = RecordingDiagnostics()
    val observations = observations(CountingPort(), listed(), diagnostics) {
      WorkGoalTimelineResult.Failed("git failed")
    }

    val state = observations.observeSubtasks("goal", false, Mutex(), CommittedChangeCache()).toList()
      .filterIsInstance<SubtaskScopesState.Listed>().single()

    assertEquals("one", state.subtasks.single().name)
    assertEquals(ScopeSubtaskState.DONE, state.subtasks.single().state)
    assertEquals(TimelineProblem.Unavailable(dev.skillbill.desktop.core.domain.model.ReadProblem.Runtime("git failed")), state.timelineProblem)
    assertEquals(listOf(SCOPES_READ_FAILED), diagnostics.records)
  }

  @Test
  fun `stacked goals skip the timeline read`() = runTest {
    var timelineReads = 0
    val observations = observations(CountingPort(), listed(rangesSupported = false), RecordingDiagnostics()) {
      timelineReads++
      WorkGoalTimelineResult.Failed("must not be read")
    }

    val state = observations.observeSubtasks("goal", false, Mutex(), CommittedChangeCache()).toList()
      .filterIsInstance<SubtaskScopesState.Listed>().single()

    assertEquals(0, timelineReads)
    assertEquals(null, state.timeline)
    assertEquals(null, state.timelineProblem)
  }

  @Test
  fun `stacked stats keep review base and recorded end behavior`() = runTest {
    val port = CountingPort()
    val scopes = listed(rangesSupported = false).copy(
      subtasks = listOf(
        WorkScopeSubtask(1, "running", DecompositionStatus.IN_PROGRESS, true, null, emptyList(), "review-1"),
        WorkScopeSubtask(2, "completed", DecompositionStatus.COMPLETE, false, "end-2", emptyList(), "review-2"),
        WorkScopeSubtask(
          3,
          "no change",
          DecompositionStatus.COMPLETED_NO_CHANGE,
          false,
          "recorded-3",
          emptyList(),
          "review-3",
        ),
      ),
    )
    val state = observations(port, scopes, RecordingDiagnostics()) { WorkGoalTimelineResult.Failed("unused") }
      .observeSubtasks("goal", false, Mutex(), CommittedChangeCache()).toList()
      .filterIsInstance<SubtaskScopesState.Listed>().single()

    assertEquals(setOf(1, 2, 3), state.stats.keys)
    assertEquals(listOf("review-2" to "end-2", "review-3" to "review-3"), port.committedRanges)
    assertEquals(listOf("review-1"), port.workingBases)
  }

  @Test
  fun `operator scope refuses more than two hundred per commit reads before git calls`() = runTest {
    val commits = (1..201).map { ScopeCommit("s$it", "p$it", "commit $it", emptyList()) }
    val target = ChangeScopeTarget(
      "goal", root.toString(), ChangeBaseline.TargetMergeBase(root.toString(), "main"),
      listOf(ScopeSubtask(1, "one", ScopeSubtaskState.DONE, "s1", emptyList(), false, emptyList())), true,
      timeline = dev.skillbill.desktop.core.domain.model.ChangeScopeTimeline("base", "head", commits),
    )
    val state = observations(CountingPort(), listed(), RecordingDiagnostics()) { WorkGoalTimelineResult.Failed("unused") }
      .observeScope(target, ChangeScope.Operator, false, Mutex(), CommittedChangeCache()).toList().last()

    assertEquals(ScopeChangesState.TooManyCommits(200), state)
  }

  @Test
  fun `commit stats keep successful reads and report a failed sha once across collections`() = runTest {
    val port = CountingPort().apply {
      committedFilesByRange["base" to "one"] = listOf(WorkChangedFile("one.kt", null, WorkChangeStatus.MODIFIED, 2, 1))
      failedRanges += "one" to "two"
    }
    val diagnostics = RecordingDiagnostics()
    val target = ChangeScopeTarget(
      "goal", root.toString(), ChangeBaseline.TargetMergeBase(root.toString(), "main"),
      listOf(ScopeSubtask(1, "one", ScopeSubtaskState.DONE, "two", listOf("one", "two"), false, emptyList())), true,
      timeline = ChangeScopeTimeline("base", "two", listOf(ScopeCommit("one", "base", "one", listOf(1)), ScopeCommit("two", "one", "two", listOf(1)))),
    )
    val observations = observations(port, listed(), diagnostics) { WorkGoalTimelineResult.Failed("unused") }
    val cache = CommittedChangeCache()
    val first = observations.observeCommitStats(target, Mutex(), cache).toList().last()
    val second = observations.observeCommitStats(target, Mutex(), cache).toList().last()

    assertEquals(setOf("one"), first.keys)
    assertEquals(mapOf("one" to SubtaskStats(listOf(ChangedFile("one.kt", null, ChangeStatus.Modified, 2, 1)), 2, 1)), second)
    assertEquals(listOf(SCOPES_READ_FAILED), diagnostics.records)
  }

  @Test
  fun `interleaved running scope includes working changes without reading another owners commit`() = runTest {
    val port = CountingPort().apply {
      workingFiles = listOf(WorkChangedFile("working.kt", null, WorkChangeStatus.MODIFIED, 2, 0))
      committedFilesByRange["parent-1" to "commit-1"] =
        listOf(WorkChangedFile("owned.kt", null, WorkChangeStatus.MODIFIED, 1, 0))
      committedFilesByRange["parent-3" to "commit-3"] =
        listOf(WorkChangedFile("owned-again.kt", null, WorkChangeStatus.MODIFIED, 3, 0))
      committedFilesByRange["parent-1" to "commit-3"] =
        listOf(WorkChangedFile("other-owner.kt", null, WorkChangeStatus.MODIFIED, 5, 0))
    }
    val target = ChangeScopeTarget(
      "goal",
      root.toString(),
      ChangeBaseline.TargetMergeBase(root.toString(), "main"),
      listOf(
        ScopeSubtask(1, "running", ScopeSubtaskState.RUNNING, null, listOf("commit-1", "commit-3"), false, emptyList()),
        ScopeSubtask(2, "other", ScopeSubtaskState.NOT_STARTED, null, emptyList(), false, emptyList()),
      ),
      rangesSupported = true,
      timeline = ChangeScopeTimeline(
        "base",
        "commit-3",
        listOf(
          ScopeCommit("commit-1", "parent-1", "first", listOf(1)),
          ScopeCommit("commit-2", "parent-2", "other", listOf(2)),
          ScopeCommit("commit-3", "parent-3", "last", listOf(1)),
        ),
      ),
    )

    val state = observations(port, listed(), RecordingDiagnostics()) { WorkGoalTimelineResult.Failed("unused") }
      .observeScope(target, ChangeScope.Subtasks(1, 1), true, Mutex(), CommittedChangeCache())
      .first { it is ScopeChangesState.Listed } as ScopeChangesState.Listed

    assertEquals(setOf("owned.kt", "owned-again.kt", "working.kt"), state.changes.files.map { it.path }.toSet())
    assertEquals(listOf("parent-1" to "commit-1", "parent-3" to "commit-3"), port.committedRanges)
    assertEquals(listOf("commit-3"), port.workingBases)
  }

  @Test
  fun `range diff labels a subtask that spans an operator commit`() = runTest {
    val diagnostics = RecordingDiagnostics()
    val port = operatorSpanPort(WorkFileDiffResult.Patch("@@ -2,0 +2 @@\n+from operator"))
    val diff = observations(port, listed(), diagnostics) { WorkGoalTimelineResult.Failed("unused") }
      .fileDiff(operatorSpanTarget(), ChangeScope.Subtasks(1, 1), spanFile, Mutex(), CommittedChangeCache())
    val shown = assertIs<FileDiffState.Shown>(diff)
    assertEquals(listOf("subtask 1 · one", "op"), shown.diff.hunks.map { it.source })
    assertEquals(emptyList(), diagnostics.records)
  }

  @Test
  fun `failed operator patch returns the unlabelled range diff`() = runTest {
    val diagnostics = RecordingDiagnostics()
    val port = operatorSpanPort(WorkFileDiffResult.Failed("missing"))
    val diff = observations(port, listed(), diagnostics) { WorkGoalTimelineResult.Failed("unused") }
      .fileDiff(operatorSpanTarget(), ChangeScope.Subtasks(1, 1), spanFile, Mutex(), CommittedChangeCache())
    val shown = assertIs<FileDiffState.Shown>(diff)
    assertEquals(listOf(null, null), shown.diff.hunks.map { it.source })
    assertEquals(listOf(SCOPES_READ_FAILED), diagnostics.records)
  }

  private fun operatorSpanTarget() = ChangeScopeTarget(
    "goal",
    root.toString(),
    ChangeBaseline.TargetMergeBase(root.toString(), "main"),
    listOf(ScopeSubtask(1, "one", ScopeSubtaskState.DONE, "s2", listOf("s1", "s2"), false, emptyList())),
    rangesSupported = true,
    timeline = ChangeScopeTimeline(
      "base",
      "s2",
      listOf(
        ScopeCommit("s1", "base", "one", listOf(1)),
        ScopeCommit("op", "s1", "operator", emptyList()),
        ScopeCommit("s2", "op", "two", listOf(1)),
      ),
    ),
  )

  private fun operatorSpanPort(operatorPatch: WorkFileDiffResult) = CountingPort().apply {
    val file = WorkChangedFile("shared.kt", null, WorkChangeStatus.MODIFIED, 1, 0)
    committedFilesByRange["base" to "s1"] = listOf(file)
    committedFilesByRange["s1" to "op"] = listOf(file)
    patches["base" to "s2"] = WorkFileDiffResult.Patch("@@ -1,0 +1 @@\n+from subtask\n@@ -2,0 +2 @@\n+from operator")
    patches["base" to "s1"] = WorkFileDiffResult.Patch("@@ -1,0 +1 @@\n+from subtask")
    patches["s1" to "op"] = operatorPatch
  }

  private fun listed(rangesSupported: Boolean = true) = WorkGoalScopesResult.Listed(
    worktreeRoot = root.toString(),
    targetBranch = "main",
    rangesSupported = rangesSupported,
    subtasks = listOf(WorkScopeSubtask(1, "one", DecompositionStatus.COMPLETE, false, "commit-1", emptyList())),
    issueKey = "SKILL-1",
    featureBranch = "feat/SKILL-1",
  )

  private fun observations(
    port: WorkChangesPort,
    scopes: WorkGoalScopesResult.Listed,
    diagnostics: Diagnostics,
    timeline: (WorkGoalScopesResult.Listed) -> WorkGoalTimelineResult,
  ) = ChangeScopeObservations(runtime, diagnostics, { scopes }, port, readTimeline = timeline)

  private class CountingPort : WorkChangesPort {
    var committedCalls = 0
    val committedRanges = mutableListOf<Pair<String, String>>()
    val workingBases = mutableListOf<String>()
    var workingFiles = emptyList<WorkChangedFile>()
    val committedFilesByRange = mutableMapOf<Pair<String, String>, List<WorkChangedFile>>()
    val patches = mutableMapOf<Pair<String, String>, WorkFileDiffResult>()
    val failedRanges = mutableSetOf<Pair<String, String>>()
    override fun changes(baseline: WorkChangeBaseline): WorkChangeListResult {
      workingBases += (baseline as WorkChangeBaseline.SubtaskStart).baseSha
      return WorkChangeListResult.Listed(
        baseline.baseSha,
        workingFiles,
        workingFiles.sumOf { it.insertions ?: 0 },
        workingFiles.sumOf { it.deletions ?: 0 },
      )
    }
    override fun fileDiff(worktreeRoot: String, baseSha: String, file: WorkChangedFile) = WorkFileDiffResult.Failed("unused")
    override fun committedChanges(worktreeRoot: String, fromSha: String, toSha: String): WorkChangeListResult {
      committedCalls++
      committedRanges += fromSha to toSha
      if ((fromSha to toSha) in failedRanges) return WorkChangeListResult.Failed("git failed")
      val files = committedFilesByRange[fromSha to toSha].orEmpty()
      return WorkChangeListResult.Listed(toSha, files, files.sumOf { it.insertions ?: 0 }, files.sumOf { it.deletions ?: 0 })
    }
    override fun committedFileDiff(worktreeRoot: String, fromSha: String, toSha: String, file: WorkChangedFile): WorkFileDiffResult =
      patches[fromSha to toSha] ?: WorkFileDiffResult.Failed("missing")
    override fun uncommittedChanges(worktreeRoot: String) = WorkChangeListResult.Failed("unused")
    override fun commitTimeline(worktreeRoot: String, targetBranch: String?, recordedShas: List<String>, headBranch: String?) =
      WorkCommitTimelineResult.Failed("unused")
  }

  private class RecordingDiagnostics : Diagnostics {
    val records = mutableListOf<String>()
    override fun record(category: String) { records += category }
    override fun recordRequest(category: String, fields: Map<String, Number>) = Unit
  }
}
