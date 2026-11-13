package skillbill.engine.goalrunner.launch

import me.tatarka.inject.annotations.Inject
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.goalworktree.GoalWorktreeResources
import java.nio.file.Path

@Inject
public class GoalBranchCheckouts(
  internal val git: WorkflowGitOperations,
  private val worktrees: GoalWorktreeResources,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
) {
  internal fun heldElsewhereReason(
    issueKey: String,
    branch: String,
    executionRoot: Path,
  ): String? {
    if (branch.isBlank()) return null
    val holder = worktrees.checkoutHolding(executionRoot, branch) ?: return null
    if (holder == repositoryEnclosingRootPort.enclosingRepositoryRoot(executionRoot)) return null
    return "Goal '$issueKey' needs branch '$branch', which is checked out at $holder. " +
      "Switch that checkout to another branch, then run sona goal $issueKey again."
  }
}
