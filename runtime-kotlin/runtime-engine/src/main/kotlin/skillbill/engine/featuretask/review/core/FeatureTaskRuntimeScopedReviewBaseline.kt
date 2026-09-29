package skillbill.engine.featuretask.review.core

import skillbill.engine.featuretask.lifecycle.checkpoint.reviewUntrackedExclusions
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import java.nio.file.Path

object FeatureTaskRuntimeScopedReviewBaseline {
  fun untrackedExclusions(
    gitOperations: RepositoryOwnedPathsGitOperations,
    repoRoot: Path,
    resolved: FeatureTaskRuntimeResolvedBranch,
  ): List<String> {
    val current = gitOperations.repositoryOwnedPaths(repoRoot)

    if (current !is WorkflowGitNameListResult.Listed) return resolved.baselineUntrackedPaths
    return reviewUntrackedExclusions(
      baselineUntrackedPaths = resolved.baselineUntrackedPaths,
      currentUntrackedPaths =
        current.names
          .map(String::trim)
          .filter(String::isNotBlank),
      ownedPaths = resolved.workflowOwnedPaths,
    )
  }

  fun of(
    gitOperations: RepositoryOwnedPathsGitOperations,
    repoRoot: Path,
    resolved: FeatureTaskRuntimeResolvedBranch,
    reviewBaseSha: String,
  ): GoalSubtaskReviewBaseline =
    GoalSubtaskReviewBaseline(
      reviewBaseSha,
      untrackedExclusions(gitOperations, repoRoot, resolved),
      resolved.workflowOwnedPaths,
    )
}
