package skillbill.engine.work

import skillbill.engine.goalrunner.goalRepositoryIdentity
import skillbill.engine.work.model.IdeStatusRepositoryResolution
import skillbill.error.core.catchingNonCancellation
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.work.model.IssueRepositoryIdentityLookup
import skillbill.ports.work.model.WorkItem
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import java.nio.file.Path

internal class IdeStatusRepositoryCorrelation(
  private val unitOfWork: UnitOfWork,
  private val repositoryIdentity: String,
) {
  fun matches(
    item: WorkItem,
    family: IdeStatusWorkflowFamily,
  ): Boolean? =
    when (family) {
      IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME ->
        matchesFeatureTaskRepository(item.workflowId)
      IdeStatusWorkflowFamily.FEATURE_GOAL ->
        matchesGoalRepository(item)
      IdeStatusWorkflowFamily.FEATURE_VERIFY ->
        matchesVerifyRepository(item)
    }

  private fun matchesFeatureTaskRepository(workflowId: String): Boolean? {
    val identity = unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(workflowId) ?: return null
    return identity.repositoryIdentity == repositoryIdentity
  }

  private fun matchesGoalRepository(item: WorkItem): Boolean? {
    val bound = unitOfWork.goalRunnerControls.controlState(item.workflowId).repositoryIdentity
    return when {
      bound == null -> {
        val issueKey = item.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey) ?: return null
        val childrenHere =
          unitOfWork.workflowStates
            .findGoalChildFeatureTaskCandidates(issueKey, repositoryIdentity)
        val childCountAnywhere = unitOfWork.workflowStates.countGoalChildIdentities(issueKey)
        childrenHere.isNotEmpty() || childCountAnywhere == 0
      }
      bound == repositoryIdentity -> true
      else -> false
    }
  }

  private fun matchesVerifyRepository(item: WorkItem): Boolean? {
    unitOfWork.standalonePhaseStatuses.latestWorkflowExecution(item.workflowId)?.let { registration ->
      return registration.repositoryIdentity == repositoryIdentity
    }
    val issueKey = item.issueKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val normalized = FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(issueKey)
    return when (val lookup = unitOfWork.workList.repositoryIdentityForIssue(normalized, item.workflowId)) {
      IssueRepositoryIdentityLookup.Missing -> null
      is IssueRepositoryIdentityLookup.Unique -> lookup.repositoryIdentity == repositoryIdentity
      is IssueRepositoryIdentityLookup.Ambiguous -> repositoryIdentity in lookup.repositoryIdentities
    }
  }
}

internal fun resolveRepositoryIdentity(
  repoRootArg: String,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): IdeStatusRepositoryResolution {
  val resolvedStart =
    catchingNonCancellation {
      repositoryEnclosingRootPort.canonicalPath(Path.of(repoRootArg))
    }.getOrNull()
      ?: return IdeStatusRepositoryResolution.Invalid("Repository root cannot be resolved: $repoRootArg")
  val gitRoot =
    findGitRoot(resolvedStart, repositoryEnclosingRootPort)
      ?: return IdeStatusRepositoryResolution.Invalid("Path is not inside a Git repository: $repoRootArg")
  val canonicalGitRoot = repositoryEnclosingRootPort.canonicalPath(gitRoot)
  val identity = goalRepositoryIdentity(canonicalGitRoot, repositoryEnclosingRootPort)
  return if (
    identity.isBlank() ||
    !identity.startsWith(FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX)
  ) {
    IdeStatusRepositoryResolution.Missing(
      "Could not form canonical repository identity for: $repoRootArg",
    )
  } else {
    val primaryRoot = repositoryEnclosingRootPort.primaryRepositoryRoot(canonicalGitRoot)
    IdeStatusRepositoryResolution.Ok(
      identity = identity,
      repoRoot = canonicalGitRoot,
      monitorIdentity = goalRepositoryIdentity(primaryRoot, repositoryEnclosingRootPort),
    )
  }
}

private fun findGitRoot(
  start: Path,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): Path? {
  var candidate: Path? = start
  while (candidate != null) {
    if (repositoryEnclosingRootPort.optionalRealPath(candidate.resolve(".git")) != null) return candidate
    candidate = candidate.parent
  }
  return null
}
