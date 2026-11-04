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
  fun `non adjacent rename history keeps identity totals and patches when old path is reused`() = runTest {
    val port = CountingPort().apply {
      committedFilesByRange["base" to "one"] = listOf(
        WorkChangedFile("old.kt", null, WorkChangeStatus.MODIFIED, 2, 1),
        WorkChangedFile("unrelated.kt", null, WorkChangeStatus.MODIFIED, 7, 2),
      )
      committedFilesByRange["two" to "three"] = listOf(WorkChangedFile("middle.kt", "old.kt", WorkChangeStatus.RENAMED, 1, 2))
      committedFilesByRange["four" to "five"] = listOf(WorkChangedFile("new.kt", "middle.kt", WorkChangeStatus.RENAMED, 2, 3))
      committedFilesByRange["six" to "seven"] = listOf(WorkChangedFile("old.kt", null, WorkChangeStatus.ADDED, 4, 0))
      patches["base" to "one"] = WorkFileDiffResult.Patch("@@ -1,0 +1 @@\n+first")
      patches["two" to "three"] = WorkFileDiffResult.Patch("@@ -2,0 +2 @@\n+renamed once")
      patches["four" to "five"] = WorkFileDiffResult.Patch("@@ -3,0 +3 @@\n+renamed twice")
      patches["six" to "seven"] = WorkFileDiffResult.Patch("@@ -4,0 +4 @@\n+replacement")
    }
    val target = ChangeScopeTarget(
      "goal",
      root.toString(),
      ChangeBaseline.TargetMergeBase(root.toString(), "main"),
      listOf(ScopeSubtask(1, "one", ScopeSubtaskState.DONE, "seven", listOf("one", "three", "five", "seven"), false, emptyList())),
      rangesSupported = true,
      timeline = ChangeScopeTimeline(
        "base",
        "seven",
        listOf(
          ScopeCommit("one", "base", "edit", listOf(1)),
          ScopeCommit("two", "one", "other", listOf(2)),
          ScopeCommit("three", "two", "rename", listOf(1)),
          ScopeCommit("four", "three", "other", listOf(2)),
          ScopeCommit("five", "four", "rename again", listOf(1)),
          ScopeCommit("six", "five", "other", listOf(2)),
          ScopeCommit("seven", "six", "reuse old path", listOf(1)),
        ),
      ),
    )
    val observations = observations(port, listed(), RecordingDiagnostics()) { WorkGoalTimelineResult.Failed("unused") }
    val cache = CommittedChangeCache()
    val scope = ChangeScope.Subtasks(1, 1)
    val state = observations.observeScope(target, scope, false, Mutex(), cache).toList().last() as ScopeChangesState.Listed

    assertEquals(
      listOf(
        ChangedFile("new.kt", "old.kt", ChangeStatus.Renamed, 5, 6),
        ChangedFile("old.kt", null, ChangeStatus.Added, 4, 0),
        ChangedFile("unrelated.kt", null, ChangeStatus.Modified, 7, 2),
      ),
      state.changes.files,
    )
    val renamed = observations.fileDiff(target, scope, state.changes.files.first(), Mutex(), cache) as FileDiffState.Shown
    val reused = observations.fileDiff(target, scope, state.changes.files.single { it.path == "old.kt" }, Mutex(), cache) as FileDiffState.Shown
    assertEquals(listOf("first", "renamed once", "renamed twice"), renamed.diff.hunks.flatMap { it.lines }.filter { it.kind == dev.skillbill.desktop.core.domain.model.DiffLineKind.Added }.map { it.text })
    assertEquals(listOf("replacement"), reused.diff.hunks.flatMap { it.lines }.filter { it.kind == dev.skillbill.desktop.core.domain.model.DiffLineKind.Added }.map { it.text })
    assertEquals(listOf("old.kt", "middle.kt", "new.kt", "old.kt"), port.patchFiles.map { it.third.path })
    val commitScope = ChangeScope.Commits(listOf("one", "three", "five"))
    val commitState = observations.observeScope(target, commitScope, false, Mutex(), cache).toList().last() as ScopeChangesState.Listed
    assertEquals(
      listOf(
        ChangedFile("new.kt", "old.kt", ChangeStatus.Renamed, 5, 6),
        ChangedFile("unrelated.kt", null, ChangeStatus.Modified, 7, 2),
      ),
      commitState.changes.files,
    )
    val commitDiff = observations.fileDiff(target, commitScope, commitState.changes.files.first(), Mutex(), cache) as FileDiffState.Shown
    assertEquals(listOf("first", "renamed once", "renamed twice"), commitDiff.diff.hunks.flatMap { it.lines }.filter { it.kind == dev.skillbill.desktop.core.domain.model.DiffLineKind.Added }.map { it.text })
    port.committedFilesByRange["base" to "operator-one"] = listOf(WorkChangedFile("operator-old.kt", null, WorkChangeStatus.MODIFIED, 1, 0))
    port.committedFilesByRange["operator-two" to "operator-three"] = listOf(WorkChangedFile("operator-new.kt", "operator-old.kt", WorkChangeStatus.RENAMED, 2, 0))
    port.patches["base" to "operator-one"] = WorkFileDiffResult.Patch("@@ -1,0 +1 @@\n+operator first")
    port.patches["operator-two" to "operator-three"] = WorkFileDiffResult.Patch("@@ -2,0 +2 @@\n+operator rename")
    val operatorTarget = target.copy(
      timeline = ChangeScopeTimeline(
        "base",
        "operator-three",
        listOf(
          ScopeCommit("operator-one", "base", "operator edit", emptyList()),
          ScopeCommit("operator-two", "operator-one", "subtask", listOf(1)),
          ScopeCommit("operator-three", "operator-two", "operator rename", emptyList()),
        ),
      ),
    )
    val operatorState = observations.observeScope(operatorTarget, ChangeScope.Operator, false, Mutex(), cache).toList().last() as ScopeChangesState.Listed
    assertEquals(listOf(ChangedFile("operator-new.kt", "operator-old.kt", ChangeStatus.Renamed, 3, 0)), operatorState.changes.files)
    val operatorDiff = observations.fileDiff(operatorTarget, ChangeScope.Operator, operatorState.changes.files.single(), Mutex(), cache) as FileDiffState.Shown
    assertEquals(listOf("operator first", "operator rename"), operatorDiff.diff.hunks.flatMap { it.lines }.filter { it.kind == dev.skillbill.desktop.core.domain.model.DiffLineKind.Added }.map { it.text })

    val scopes = listed().copy(
      subtasks = listOf(WorkScopeSubtask(1, "one", DecompositionStatus.COMPLETE, false, "seven", emptyList())),
    )
    val statsObservations = observations(port, scopes, RecordingDiagnostics()) {
      WorkGoalTimelineResult.Attributed(
        "base",
        "seven",
        listOf(
          WorkTimelineCommit("one", "base", "edit", listOf(1)),
          WorkTimelineCommit("two", "one", "other", listOf(2)),
          WorkTimelineCommit("three", "two", "rename", listOf(1)),
          WorkTimelineCommit("four", "three", "other", listOf(2)),
          WorkTimelineCommit("five", "four", "rename again", listOf(1)),
          WorkTimelineCommit("six", "five", "other", listOf(2)),
          WorkTimelineCommit("seven", "six", "reuse old path", listOf(1)),
        ),
        emptySet(),
      )
    }
    val statsCache = CommittedChangeCache()
    val fresh = statsObservations.observeSubtasks("goal", false, Mutex(), statsCache).toList()
      .filterIsInstance<SubtaskScopesState.Listed>().single().stats.getValue(1)
    val callsAfterFresh = port.committedCalls
    val cached = statsObservations.observeSubtasks("goal", false, Mutex(), statsCache).toList()
      .filterIsInstance<SubtaskScopesState.Listed>().single().stats.getValue(1)
    assertEquals(state.changes.files, fresh.files)
    assertEquals(fresh, cached)
    assertEquals(callsAfterFresh, port.committedCalls)
  }

  @Test
  fun `working tree rename extends committed identity and patch history`() = runTest {
    val port = CountingPort().apply {
      committedFilesByRange["base" to "one"] = listOf(WorkChangedFile("old.kt", null, WorkChangeStatus.MODIFIED, 2, 0))
      committedFilesByRange["two" to "three"] = listOf(WorkChangedFile("new.kt", "old.kt", WorkChangeStatus.RENAMED, 1, 0))
      workingFiles = listOf(WorkChangedFile("current.kt", "new.kt", WorkChangeStatus.RENAMED, 3, 0))
      patches["base" to "one"] = WorkFileDiffResult.Patch("@@ -1,0 +1 @@\n+committed edit")
      patches["two" to "three"] = WorkFileDiffResult.Patch("@@ -2,0 +2 @@\n+committed rename")
      workingPatches["current.kt"] = WorkFileDiffResult.Patch("@@ -3,0 +3 @@\n+working rename")
    }
    val target = ChangeScopeTarget(
      "goal",
      root.toString(),
      ChangeBaseline.TargetMergeBase(root.toString(), "main"),
      listOf(ScopeSubtask(1, "running", ScopeSubtaskState.RUNNING, null, listOf("one", "three"), false, emptyList())),
      rangesSupported = true,
      timeline = ChangeScopeTimeline(
        "base",
        "three",
        listOf(
          ScopeCommit("one", "base", "edit", listOf(1)),
          ScopeCommit("two", "one", "other", listOf(2)),
          ScopeCommit("three", "two", "rename", listOf(1)),
        ),
      ),
    )
    val observations = observations(port, listed(), RecordingDiagnostics()) { WorkGoalTimelineResult.Failed("unused") }
    val scope = ChangeScope.Subtasks(1, 1)
    val cache = CommittedChangeCache()
    val state = observations.observeScope(target, scope, true, Mutex(), cache)
      .first { it is ScopeChangesState.Listed } as ScopeChangesState.Listed

    assertEquals(listOf(ChangedFile("current.kt", "old.kt", ChangeStatus.Renamed, 6, 0)), state.changes.files)
    val diff = observations.fileDiff(target, scope, state.changes.files.single(), Mutex(), cache) as FileDiffState.Shown
    assertEquals(
      listOf("committed edit", "committed rename", "working rename"),
      diff.diff.hunks.flatMap { it.lines }.filter { it.kind == dev.skillbill.desktop.core.domain.model.DiffLineKind.Added }.map { it.text },
    )
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
    val workingPatches = mutableMapOf<String, WorkFileDiffResult>()
    val patchFiles = mutableListOf<Triple<String, String, WorkChangedFile>>()
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
    override fun fileDiff(worktreeRoot: String, baseSha: String, file: WorkChangedFile) =
      workingPatches[file.path] ?: WorkFileDiffResult.Failed("unused")
    override fun committedChanges(worktreeRoot: String, fromSha: String, toSha: String): WorkChangeListResult {
      committedCalls++
      committedRanges += fromSha to toSha
      if ((fromSha to toSha) in failedRanges) return WorkChangeListResult.Failed("git failed")
      val files = committedFilesByRange[fromSha to toSha].orEmpty()
      return WorkChangeListResult.Listed(toSha, files, files.sumOf { it.insertions ?: 0 }, files.sumOf { it.deletions ?: 0 })
    }
    override fun committedFileDiff(worktreeRoot: String, fromSha: String, toSha: String, file: WorkChangedFile): WorkFileDiffResult {
      patchFiles += Triple(fromSha, toSha, file)
      return patches[fromSha to toSha] ?: WorkFileDiffResult.Failed("missing")
    }
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
