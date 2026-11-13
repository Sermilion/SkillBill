package skillbill.engine.goalrunner.manifest

import skillbill.application.workflow.decomposition.listDecomposedParentsForPurge
import skillbill.application.workflow.decomposition.listPlanWorkflowsForPurge
import skillbill.contracts.issuekey.normalizeRequiredIssueKey
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goal.goalRepositoryIdentityCandidates
import skillbill.engine.goalrunner.model.GoalPurgeOwnership
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.model.GoalPurgeTableCounts
import skillbill.ports.persistence.model.GoalPurgeTarget
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.nio.file.Path

internal class WorkflowGoalRunnerPurgePersistence(
  private val database: DatabaseSessionFactory,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
) : GoalRunnerManifestPurgeCommands {
  override fun listOwnedGoalChildWorkflowIds(parentWorkflowId: String): List<String> =
    database.read { it.workflowStates.listGoalChildWorkflowIdsByParent(parentWorkflowId) }

  override fun discoverPurgeOwnership(
    issueKey: String,
    repoRoot: Path,
  ): GoalPurgeOwnership {
    val identities = goalRepositoryIdentityCandidates(repoRoot, repositoryEnclosingRootPort)
    return database.read { unitOfWork ->
      val discovered = identities.map { unitOfWork.workflowStates.listDecomposedParentsForPurge(issueKey, it) }
      val parents = discovered.flatMap { it.parents }.distinctBy { it.record.workflowId }
      val ownedChildIds =
        parents.flatMapTo(hashSetOf()) { parent ->
          unitOfWork.workflowStates.listGoalChildWorkflowIdsByParent(parent.record.workflowId)
        }
      GoalPurgeOwnership(
        manifests =
          parents.mapNotNull { parent ->
            parent.manifest?.let { manifest ->
              GoalRunnerManifestState(
                parentWorkflowId = parent.record.workflowId,
                dbPath = unitOfWork.dbPath.toString(),
                manifest = manifest,
                controlState = unitOfWork.goalRunnerControls.controlState(parent.record.workflowId),
              )
            }
          },
        parentWorkflowIds = parents.mapTo(linkedSetOf()) { it.record.workflowId },
        planWorkflowIds =
          identities.flatMapTo(linkedSetOf()) { identity ->
            unitOfWork.workflowStates.listPlanWorkflowsForPurge(issueKey, identity).map { it.workflowId }
          },
        unclassifiedWorkflows =
          discovered.flatMap { it.unclassifiedRows }.distinctBy { it.workflowId }.filterNot { row ->
            row.workflowId in ownedChildIds
          }.map { row ->
            "workflow ${row.workflowId} matches $issueKey but its state cannot be decoded (${row.reason}); " +
              "it was not purged"
          },
        siblingWorktreeWorkflows = unitOfWork.siblingWorktreeWorkflows(issueKey, repoRoot, identities),
      )
    }
  }

  private fun UnitOfWork.siblingWorktreeWorkflows(
    issueKey: String,
    repoRoot: Path,
    identities: List<String>,
  ): List<String> {
    val primaryRoot = repositoryEnclosingRootPort.primaryRepositoryRoot(repoRoot)
    return workflowStates
      .findFeatureTaskWorkflowsForIssue(FeatureTaskWorkflowMode.RUNTIME, normalizeRequiredIssueKey(issueKey), null)
      .groupBy({ row -> recordedRepositoryIdentity(row.workflowId) }, { row -> row.workflowId })
      .mapNotNull { (owner, workflowIds) ->
        val prefix = FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX
        val worktree =
          owner?.takeIf { it !in identities && it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.let(Path::of)
            ?.takeIf { repositoryEnclosingRootPort.optionalRealPath(it) != null }
            ?.takeIf { repositoryEnclosingRootPort.primaryRepositoryRoot(it) == primaryRoot }
            ?: return@mapNotNull null
        "workflows ${workflowIds.sorted().joinToString()} match $issueKey but belong to worktree $worktree; " +
          "they were not purged (run `sona goal purge $issueKey --repo-root $worktree`)"
      }
  }

  private fun UnitOfWork.recordedRepositoryIdentity(workflowId: String): String? =
    goalRunnerControls.controlState(workflowId).repositoryIdentity
      ?: workflowStates.getFeatureTaskExecutionIdentity(workflowId)?.repositoryIdentity

  override fun verifyOwnedWorkflowIds(
    candidateIds: Set<String>,
    parentWorkflowIds: Set<String>,
    issueKey: String,
    repoRoot: Path,
  ): Set<String> {
    if (candidateIds.isEmpty()) return emptySet()
    val normalizedIssueKey = normalizeRequiredIssueKey(issueKey)
    val identities = goalRepositoryIdentityCandidates(repoRoot, repositoryEnclosingRootPort)
    return database.read { unitOfWork ->
      val goalChildIds =
        parentWorkflowIds.flatMapTo(hashSetOf()) { parentId ->
          unitOfWork.workflowStates.listGoalChildWorkflowIdsByParent(parentId)
        }
      candidateIds.filterTo(linkedSetOf()) { candidateId ->
        candidateId in goalChildIds ||
          unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(candidateId)?.let { owned ->
            owned.normalizedIssueKey == normalizedIssueKey && owned.repositoryIdentity in identities
          } == true
      }
    }
  }

  override fun purgeDecomposedGoal(target: GoalPurgeTarget): GoalPurgeTableCounts =
    database.transaction { unitOfWork -> unitOfWork.purgeDecomposedGoal(target) }

  override fun countDecomposedGoalState(target: GoalPurgeTarget): GoalPurgeTableCounts =
    database.read { unitOfWork -> unitOfWork.countDecomposedGoalState(target) }
}
