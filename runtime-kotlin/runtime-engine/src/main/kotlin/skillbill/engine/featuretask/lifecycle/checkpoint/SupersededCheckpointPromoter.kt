package skillbill.engine.featuretask.lifecycle.checkpoint

import skillbill.engine.featuretask.lifecycle.subtask.FeatureTaskRuntimeSubtaskCommitAmend
import skillbill.engine.featuretask.lifecycle.subtask.FeatureTaskRuntimeSubtaskCommitCreate
import skillbill.engine.featuretask.lifecycle.subtask.FeatureTaskRuntimeSubtaskCommitDecision
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

public class SupersededCheckpointPromoter(
  public val gitOperations: WorkflowGitOperations,
) {
  internal fun FeatureTaskRuntimeSubtaskCommitDecision.promote(
    repoRoot: Path,
    branch: String,
    durableCommitSha: String?,
    sequenceNumber: Int,
    stageable: List<String>,
  ): FeatureTaskRuntimeSubtaskCommitDecision {
    val replacement =
      if (this is FeatureTaskRuntimeSubtaskCommitCreate && stageable.isEmpty()) {
        durableCommitSha?.trim()?.takeIf(String::isNotBlank)?.let { durable ->
          gitOperations.headCommitSha(repoRoot)
            .takeIf { it is WorkflowGitOperationResult.Ok }
            ?.value
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.takeIf { headSha -> durable != headSha }
            ?.takeIf { headSha ->
              gitOperations.isCommitAncestor(repoRoot, durable, headSha).let { result ->
                result is WorkflowGitOperationResult.Ok && result.value == "true"
              }
            }
            ?.let { headSha ->
              val unpushed = gitOperations.localBranchHasUnpushedCommits(repoRoot, branch)
              val isUnpushed =
                unpushed is WorkflowGitOperationResult.Ok &&
                  unpushed.value.orEmpty().trim().equals("true", ignoreCase = true)
              FeatureTaskRuntimeSubtaskCommitAmend(
                ownedHeadSha = headSha,
                sequenceNumber = sequenceNumber,
                recoveredFromTrailer = false,
                rewritesPublishedHistory = !isUnpushed,
              )
            }
        }
      } else {
        null
      }
    return replacement ?: this
  }
}
