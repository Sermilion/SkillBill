package dev.skillbill.desktop.core.domain.model

sealed interface ChangeScope {
  data object All : ChangeScope
  data class Subtasks(val from: Int, val to: Int) : ChangeScope
  data class Commits(val shas: List<String>) : ChangeScope
  data object Operator : ChangeScope
  data object Uncommitted : ChangeScope
}

enum class ScopeKind { All, Subtasks, Commits, Operator, Uncommitted }

val ChangeScope.kind: ScopeKind
  get() = when (this) {
    ChangeScope.All -> ScopeKind.All
    is ChangeScope.Subtasks -> ScopeKind.Subtasks
    is ChangeScope.Commits -> ScopeKind.Commits
    ChangeScope.Operator -> ScopeKind.Operator
    ChangeScope.Uncommitted -> ScopeKind.Uncommitted
  }

data class ScopeCommit(val sha: String, val parentSha: String, val subject: String, val ownerIds: List<Int>) {
  val operator: Boolean get() = ownerIds.isEmpty()
  val orderedOwnerIds: List<Int> get() = ownerIds.sorted()
}

data class ChangeScopeTimeline(val mergeBaseSha: String, val headSha: String, val commits: List<ScopeCommit>)

sealed interface TimelineProblem {
  data class OnOtherBranch(val currentBranch: String?, val featureBranch: String) : TimelineProblem
  data class BaseUnavailable(val reason: String) : TimelineProblem
  data class NotARepository(val worktreeRoot: String) : TimelineProblem
  data class Unavailable(val problem: ReadProblem) : TimelineProblem
}

enum class ScopeSubtaskState { DONE, RUNNING, INTERRUPTED, NOT_STARTED }

data class ScopeSubtask(
  val id: Int,
  val name: String,
  val state: ScopeSubtaskState,
  val recordedSha: String?,
  val commitShas: List<String>,
  val changedAfterFinish: Boolean,
  val baselineUntrackedPaths: List<String>,
  val reviewBaseSha: String? = null,
  val completedNoChange: Boolean = false,
) {
  val selectable: Boolean get() = state != ScopeSubtaskState.NOT_STARTED &&
    (commitShas.isNotEmpty() || state == ScopeSubtaskState.RUNNING || state == ScopeSubtaskState.INTERRUPTED ||
      !reviewBaseSha.isNullOrBlank())
}

data class ChangeScopeTarget(
  val workId: String,
  val worktreeRoot: String,
  val allBaseline: ChangeBaseline,
  val subtasks: List<ScopeSubtask>,
  val rangesSupported: Boolean,
  val committedOnly: Boolean = false,
  val timeline: ChangeScopeTimeline? = null,
  val timelineProblem: TimelineProblem? = null,
  val chat: Boolean = false,
)

sealed interface ScopeRead {
  data class Baseline(val baseline: ChangeBaseline) : ScopeRead
  data class Committed(val fromSha: String, val toSha: String) : ScopeRead
  data object Uncommitted : ScopeRead
  data class PerCommit(val commits: List<ScopeCommit>) : ScopeRead
  data class Unavailable(val reason: String) : ScopeRead
}

data class SubtaskStats(val files: List<ChangedFile>, val insertions: Int, val deletions: Int)
data class CommitChanges(val commit: ScopeCommit, val files: List<ChangedFile>)

sealed interface SubtaskScopesState {
  data object Loading : SubtaskScopesState
  data class Listed(
    val worktreeRoot: String,
    val targetBranch: String?,
    val rangesSupported: Boolean,
    val subtasks: List<ScopeSubtask>,
    val stats: Map<Int, SubtaskStats>,
    val committedHeadBranch: String? = null,
    val timeline: ChangeScopeTimeline? = null,
    val timelineProblem: TimelineProblem? = null,
  ) : SubtaskScopesState
  data object NotAGoal : SubtaskScopesState
  data class Unavailable(val problem: ReadProblem) : SubtaskScopesState
}

sealed interface ScopeChangesState {
  data object Loading : ScopeChangesState
  data class Listed(val changes: ChangeSet, val perCommit: List<CommitChanges> = emptyList()) : ScopeChangesState
  data class BaseUnavailable(val reason: String) : ScopeChangesState
  data class NotARepository(val worktreeRoot: String) : ScopeChangesState
  data class TooManyCommits(val limit: Int) : ScopeChangesState
  data class Unavailable(val problem: ReadProblem) : ScopeChangesState
}
