package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeNumstatResult
import skillbill.ports.workflow.gitops.worktree.WorkflowGitWorktreeOperations
import skillbill.workflow.model.goalobservability.GoalObservabilityChangedFileSummary
import skillbill.workflow.model.goalobservability.GoalObservabilityDiffStat
import skillbill.workflow.model.goalobservability.GoalObservabilitySelectedDiffHunks
import java.nio.file.Path

internal object NoopWorkflowGitWorktreeOperations : WorkflowGitWorktreeOperations {
  override fun stageAll(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "")

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(value = "")
  }

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult {
    return WorkflowWorktreeActivityResult(
      status = WorkflowGitOperationStatus.OK,
      changedFileSummary =
        GoalObservabilityChangedFileSummary(
          total = 0,
          added = 0,
          modified = 0,
          deleted = 0,
          renamed = 0,
          untracked = 0,
        ),
      diffStat = GoalObservabilityDiffStat(filesChanged = 0, insertions = 0, deletions = 0),
    )
  }

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult {
    return WorkflowSelectedDiffHunksResult(
      status = WorkflowGitOperationStatus.OK,
      selectedDiffHunks = GoalObservabilitySelectedDiffHunks(),
    )
  }

  override fun worktreeNumstat(repoRoot: Path): WorkflowWorktreeNumstatResult =
    WorkflowWorktreeNumstatResult(status = WorkflowGitOperationStatus.OK, files = emptyList())
}
