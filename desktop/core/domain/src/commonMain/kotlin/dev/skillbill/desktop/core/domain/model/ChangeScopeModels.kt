package dev.skillbill.desktop.core.domain.model

sealed interface ChangeScope {
  data object All : ChangeScope
  data class Subtasks(val from: Int, val to: Int) : ChangeScope
  data object Outside : ChangeScope
  data object Uncommitted : ChangeScope
}

enum class ScopeKind { All, Subtasks, Outside, Uncommitted }

val ChangeScope.kind: ScopeKind
  get() = when (this) {
    ChangeScope.All -> ScopeKind.All
    is ChangeScope.Subtasks -> ScopeKind.Subtasks
    ChangeScope.Outside -> ScopeKind.Outside
    ChangeScope.Uncommitted -> ScopeKind.Uncommitted
  }

enum class ScopeSubtaskState { DONE, RUNNING, INTERRUPTED, NOT_STARTED }

data class ScopeSubtask(
  val id: Int,
  val name: String,
  val state: ScopeSubtaskState,
  val startSha: String?,
  val endSha: String?,
  val baselineUntrackedPaths: List<String>,
) {
  val selectable: Boolean get() = state != ScopeSubtaskState.NOT_STARTED && startSha != null
}

data class ChangeScopeTarget(
  val workId: String,
  val worktreeRoot: String,
  val allBaseline: ChangeBaseline,
  val subtasks: List<ScopeSubtask>,
  val rangesSupported: Boolean,
  val committedOnly: Boolean = false,
)

data class CommitRange(val fromSha: String, val toSha: String?)

sealed interface ScopeRead {
  data class Baseline(val baseline: ChangeBaseline) : ScopeRead
  data class Committed(val fromSha: String, val toSha: String) : ScopeRead
  data object Uncommitted : ScopeRead
  data class Outside(val ranges: List<CommitRange>) : ScopeRead
  data class Unavailable(val reason: String) : ScopeRead
}

data class SubtaskStats(val files: List<ChangedFile>, val insertions: Int, val deletions: Int)

data class OutsideCommit(val sha: String, val parentSha: String, val subject: String, val files: List<ChangedFile>)

sealed interface SubtaskScopesState {
  data object Loading : SubtaskScopesState
  data class Listed(
    val worktreeRoot: String,
    val targetBranch: String?,
    val rangesSupported: Boolean,
    val subtasks: List<ScopeSubtask>,
    val stats: Map<Int, SubtaskStats>,
    val committedHeadBranch: String? = null,
  ) : SubtaskScopesState
  data object NotAGoal : SubtaskScopesState
  data class Unavailable(val problem: ReadProblem) : SubtaskScopesState
}

sealed interface ScopeChangesState {
  data object Loading : ScopeChangesState
  data class Listed(val changes: ChangeSet, val outsideCommits: List<OutsideCommit> = emptyList()) : ScopeChangesState
  data class BaseUnavailable(val reason: String) : ScopeChangesState
  data class NotARepository(val worktreeRoot: String) : ScopeChangesState
  data class TooManyCommits(val limit: Int) : ScopeChangesState
  data class Unavailable(val problem: ReadProblem) : ScopeChangesState
}
