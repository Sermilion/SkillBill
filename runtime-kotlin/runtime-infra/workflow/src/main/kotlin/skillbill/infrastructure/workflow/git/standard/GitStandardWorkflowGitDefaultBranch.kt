package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

private const val ORIGIN_PREFIX = "origin/"
private val CONVENTIONAL_DEFAULT_BRANCHES = listOf("main", "master", "trunk")

internal fun gitDefaultBranch(repoRoot: Path): WorkflowGitOperationResult {
  val originHead = runGitCommand(repoRoot, "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD")
  val fromOrigin =
    (originHead as? WorkflowGitOperationResult.Ok)
      ?.value
      ?.trim()
      ?.removePrefix(ORIGIN_PREFIX)
      ?.takeIf(String::isNotBlank)
  val resolved =
    fromOrigin
      ?: CONVENTIONAL_DEFAULT_BRANCHES.firstOrNull { candidate ->
        listOf("refs/heads/$candidate", "refs/remotes/origin/$candidate").any { ref ->
          runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", ref) is WorkflowGitOperationResult.Ok
        }
      }
  return resolved?.let { WorkflowGitOperationResult.Ok(value = it) }
    ?: WorkflowGitOperationResult.Failed(
      error =
        "Could not determine the default branch: origin/HEAD is unset and none of " +
          "${CONVENTIONAL_DEFAULT_BRANCHES.joinToString()} exists.",
    )
}
