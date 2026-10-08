package skillbill.engine.featuretask.lifecycle.branch

import skillbill.ports.workflow.gitops.DefaultBranchGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

private const val FALLBACK_BASE_BRANCH = "main"

fun DefaultBranchGitOperations.baseBranchOrDefault(
  repoRoot: Path,
  recordedBaseBranch: String?,
): String =
  recordedBaseBranch?.trim()?.takeIf(String::isNotBlank)
    ?: repositoryDefaultBranch(repoRoot)
    ?: FALLBACK_BASE_BRANCH

internal fun DefaultBranchGitOperations.repositoryDefaultBranch(repoRoot: Path): String? =
  (defaultBranch(repoRoot) as? WorkflowGitOperationResult.Ok)?.value?.trim()?.takeIf(String::isNotBlank)
