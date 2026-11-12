package skillbill.application.workflow

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.model.GoalExecutionRootAccess
import skillbill.application.workflow.model.GoalExecutionRootOptions
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.ProjectStoreLocation
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.goalworktree.GoalWorktreeResources
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import java.nio.file.Path

@Inject
class ProjectCheckout(
  private val repositories: RepositoryEnclosingRootPort,
  private val projectStore: ProjectStorePort,
  private val worktrees: GoalWorktreeResources,
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
    return if (repositories.optionalRealPath(recorded) != null) {
      verifyRecorded(recorded, controlState, manifest, invokedRoot, options)
    } else {
      GoalExecutionRootAccess.Failed(
        "the recorded goal checkout $recorded is missing. Restore or inspect that path, including unpublished and " +
          "untracked work, before retrying; a branch or base revision cannot prove that missing work is recoverable.",
      )
    }
  }

  private fun verifyRecorded(
    recorded: Path,
    controlState: GoalRunnerControlState,
    manifest: DecompositionManifest,
    invokedRoot: Path,
    options: GoalExecutionRootOptions,
  ): GoalExecutionRootAccess {
    val ownership = options.ownership
    if (options.allowLegacyRecordedPath && ownership == null) return GoalExecutionRootAccess.Ready(recorded, false)
    if (ownership == null || !matchesOwner(ownership, recorded, controlState, manifest, options)) {
      return GoalExecutionRootAccess.Failed(
        "the recorded goal checkout $recorded has no matching transferred ownership proof. " +
          "Preserve the path and reconcile its owner before retrying.",
      )
    }
    return when (
      val verified =
        worktrees.createOrAdopt(
          invokedRoot,
          manifest.issueKey,
          recorded,
          ownership.initialCommit,
          ownership,
        )
    ) {
      is GoalWorktreeResult.Failed ->
        GoalExecutionRootAccess.Failed(
          "the recorded goal checkout $recorded could not be verified: ${verified.reason}",
        )
      else -> GoalExecutionRootAccess.Ready(recorded, false)
    }
  }

  private fun matchesOwner(
    ownership: GoalPlanningOwnership,
    recorded: Path,
    controlState: GoalRunnerControlState,
    manifest: DecompositionManifest,
    options: GoalExecutionRootOptions,
  ): Boolean =
    sameOwner(ownership, controlState, options) &&
      ownership.normalizedIssueKey == FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(manifest.issueKey) &&
      ownership.ownedPath.toAbsolutePath().normalize() == recorded.toAbsolutePath().normalize()

  private fun sameOwner(
    ownership: GoalPlanningOwnership,
    controlState: GoalRunnerControlState,
    options: GoalExecutionRootOptions,
  ): Boolean =
    ownership.state == GoalPlanningOwnership.State.TRANSFERRED &&
      ownership.parentWorkflowId == options.parentWorkflowId &&
      ownership.repositoryIdentity == controlState.repositoryIdentity
}
