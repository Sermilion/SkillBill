package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.git.GitRepositoryFingerprintOperations
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeNumstatResult
import skillbill.ports.workflow.gitops.worktree.WorkflowGitWorktreeOperations
import java.nio.file.Path

internal object GitStandardWorkflowGitWorktreeOperations : WorkflowGitWorktreeOperations {
  override fun stageAll(repoRoot: Path): WorkflowGitOperationResult = runGitCommand(repoRoot, "add", "-A")

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    runGitCommand(repoRoot, "status", "--porcelain", "-uall")

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult =
    GitRepositoryFingerprintOperations.worktreeActivity(repoRoot)

  override fun worktreeNumstat(repoRoot: Path): WorkflowWorktreeNumstatResult =
    GitRepositoryFingerprintOperations.worktreeNumstat(repoRoot)

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = GitRepositoryFingerprintOperations.selectedDiffHunks(repoRoot, request)
}
