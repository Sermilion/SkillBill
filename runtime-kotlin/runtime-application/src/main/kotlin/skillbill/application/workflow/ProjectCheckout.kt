package skillbill.application.workflow

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.model.GoalExecutionRootAccess
import skillbill.application.workflow.model.GoalExecutionRootOptions
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.ProjectStoreLocation
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.goalworktree.GoalWorktreeResources
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import skillbill.workflow.decomposition.baseForSubtask
import skillbill.workflow.decomposition.branchForSubtask
import skillbill.workflow.decomposition.executionRootSubtaskId
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import java.nio.file.Path

@Inject
class ProjectCheckout(
  private val repositories: RepositoryEnclosingRootPort,
  private val projectStore: ProjectStorePort,
  private val worktrees: GoalWorktreeResources,
  private val git: WorkflowGitOperations,
) {
  fun repositoryIdentityCandidates(repoRoot: Path): List<String> =
    listOf(repositories.primaryRepositoryIdentity(repoRoot), repositories.repositoryIdentity(repoRoot)).distinct()

  fun store(repoRoot: Path): ProjectStoreLocation = projectStore.resolve(repoRoot)

  fun ensureGoalExecutionRoot(
    controlState: GoalRunnerControlState,
    manifest: DecompositionManifest,
    invokedRoot: Path,
    options: GoalExecutionRootOptions = GoalExecutionRootOptions(),
  ): GoalExecutionRootAccess {
    val recorded = controlState.executionWorktreePath?.let(Path::of)
    if (recorded == null) {
      val hasLaunchedChild = manifest.subtasks.any { !it.workflowId.isNullOrBlank() }
      val candidate =
        Path.of(
          FeatureTaskExecutionIdentityPolicy.goalExecutionRootCandidate(
            null,
            hasLaunchedChild,
            controlState.repositoryIdentity,
            invokedRoot.toString(),
          ),
        )
      return GoalExecutionRootAccess.Ready(repositories.optionalRealPath(candidate) ?: invokedRoot, false)
    }
    if (repositories.optionalRealPath(recorded) != null) return GoalExecutionRootAccess.Ready(recorded, false)
    if (!options.recreateCompleted) return GoalExecutionRootAccess.Ready(invokedRoot, false)
    return recreateWorktree(manifest, invokedRoot, recorded, options)
  }

  private fun recreateWorktree(
    manifest: DecompositionManifest,
    invokedRoot: Path,
    recorded: Path,
    options: GoalExecutionRootOptions,
  ): GoalExecutionRootAccess {
    val primaryRoot = repositories.primaryRepositoryRoot(invokedRoot)
    val subtaskId = options.revisionSubtaskId ?: manifest.executionRootSubtaskId()
    val revision = worktreeRevision(manifest, primaryRoot, subtaskId, options.requireExistingBranch)
    if (revision is GoalRevision.Failed) return GoalExecutionRootAccess.Failed(revision.reason)
    val value = (revision as GoalRevision.Ready).value
    val resolved =
      if (value.isBlank()) {
        (git.headCommitSha(primaryRoot) as? WorkflowGitOperationResult.Ok)?.value?.trim()
          ?: return GoalExecutionRootAccess.Failed("could not read the base revision for the goal worktree")
      } else {
        value
      }
    return when (val result = worktrees.createOrAdopt(primaryRoot, manifest.issueKey, recorded, resolved)) {
      is GoalWorktreeResult.Created -> GoalExecutionRootAccess.Ready(result.path, true)
      is GoalWorktreeResult.Adopted -> GoalExecutionRootAccess.Ready(result.path, true)
      is GoalWorktreeResult.Failed -> GoalExecutionRootAccess.Failed(result.reason)
    }
  }

  private fun worktreeRevision(
    manifest: DecompositionManifest,
    primaryRoot: Path,
    subtaskId: Int?,
    requireExistingBranch: Boolean,
  ): GoalRevision {
    val subtask = subtaskId?.let { id -> manifest.subtasks.firstOrNull { it.id == id } }
    val branch =
      subtaskId?.let { manifest.branchForSubtask(it) }?.takeIf(String::isNotBlank)
        ?: manifest.featureBranch
    val required = requireExistingBranch || subtask?.hasStarted() == true
    val label = subtaskId ?: "the active subtask"
    if (branch.isNullOrBlank()) {
      return if (required) {
        GoalRevision.Failed("the goal branch for subtask $label is not recorded")
      } else {
        GoalRevision.Ready(subtaskId?.let { manifest.baseForSubtask(it) } ?: manifest.baseBranch)
      }
    }
    return existingBranchRevision(
      primaryRoot,
      branch,
      required,
      label,
      subtaskId?.let { manifest.baseForSubtask(it) } ?: manifest.baseBranch,
    )
  }

  private fun existingBranchRevision(
    primaryRoot: Path,
    branch: String,
    required: Boolean,
    label: Any,
    base: String,
  ): GoalRevision {
    return when (val exists = git.branchExists(primaryRoot, branch)) {
      is WorkflowGitOperationResult.Failed ->
        GoalRevision.Failed(
          "could not check whether goal branch '$branch' exists: ${exists.error}",
        )
      is WorkflowGitOperationResult.Ok ->
        when {
          exists.value.trim() == "true" -> GoalRevision.Ready(branch)
          required -> GoalRevision.Failed("goal branch '$branch' for subtask $label is missing")
          else -> GoalRevision.Ready(base)
        }
    }
  }
}

private sealed interface GoalRevision {
  data class Ready(val value: String) : GoalRevision

  data class Failed(val reason: String) : GoalRevision
}
