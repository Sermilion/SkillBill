package skillbill.engine.goalrunner.manifest

import skillbill.application.workflow.decomposition.listDecomposedParentsForPurge
import skillbill.application.workflow.decomposition.listPlanWorkflowsForPurge
import skillbill.contracts.issuekey.normalizeRequiredIssueKey
import skillbill.engine.goalrunner.model.GoalPurgeOwnership
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.model.GoalPurgeTableCounts
import skillbill.ports.persistence.model.GoalPurgeTarget
import skillbill.ports.repository.RepositoryEnclosingRootPort
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
    val identity = repositoryEnclosingRootPort.repositoryIdentity(repoRoot)
    return database.read { unitOfWork ->
      val discovered = unitOfWork.workflowStates.listDecomposedParentsForPurge(issueKey, identity)
      val parents = discovered.parents
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
          unitOfWork.workflowStates.listPlanWorkflowsForPurge(issueKey, identity).mapTo(linkedSetOf()) {
            it.workflowId
          },
        unclassifiedWorkflows =
          discovered.unclassifiedRows.filterNot { row -> row.workflowId in ownedChildIds }.map { row ->
            "workflow ${row.workflowId} matches $issueKey but its state cannot be decoded (${row.reason}); " +
              "it was not purged"
          },
      )
    }
  }

  override fun verifyOwnedWorkflowIds(
    candidateIds: Set<String>,
    parentWorkflowIds: Set<String>,
    issueKey: String,
    repoRoot: Path,
  ): Set<String> {
    if (candidateIds.isEmpty()) return emptySet()
    val normalizedIssueKey = normalizeRequiredIssueKey(issueKey)
    val identity = repositoryEnclosingRootPort.repositoryIdentity(repoRoot)
    return database.read { unitOfWork ->
      val goalChildIds =
        parentWorkflowIds.flatMapTo(hashSetOf()) { parentId ->
          unitOfWork.workflowStates.listGoalChildWorkflowIdsByParent(parentId)
        }
      candidateIds.filterTo(linkedSetOf()) { candidateId ->
        candidateId in goalChildIds ||
          unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(candidateId)?.let { owned ->
            owned.normalizedIssueKey == normalizedIssueKey && owned.repositoryIdentity == identity
          } == true
      }
    }
  }

  override fun purgeDecomposedGoal(target: GoalPurgeTarget): GoalPurgeTableCounts =
    database.transaction { unitOfWork -> unitOfWork.purgeDecomposedGoal(target) }

  override fun countDecomposedGoalState(target: GoalPurgeTarget): GoalPurgeTableCounts =
    database.read { unitOfWork -> unitOfWork.countDecomposedGoalState(target) }
}
