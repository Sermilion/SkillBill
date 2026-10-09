package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_SCHEMA_ID
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalplanning.readStoredPlanningRecord
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedPreplanProduction
import skillbill.engine.goalrunner.planning.outcome.governedSubSpecReady
import skillbill.engine.goalrunner.planning.outcome.proseRecordPayload
import skillbill.engine.goalrunner.planning.outcome.resolvedGovernedPath
import skillbill.error.shellcontent.invalidGoalPlanningPreparationSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.text.sha256HexUtf8
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.phaseRecords
import java.nio.file.Path

@Inject
class StandalonePlanPreparation(
  private val database: DatabaseSessionFactory,
  private val checkpoint: GoalPlanningPreparationCheckpoint,
  private val sharedProduction: GoalPlanningSharedPreplanProduction,
  private val manifestStore: GoalRunnerManifestStore,
  private val files: DecompositionManifestStore,
  private val repositories: RepositoryEnclosingRootPort,
) {
  internal fun capture(
    workflowId: String,
    issueKey: String,
    repoRoot: Path,
  ) {
    val state =
      manifestStore.readByIssueKey(issueKey, repoRoot)
        ?: invalid(workflowId, "standalone plan has no verified decomposition manifest")
    val identity = planningIdentity(state, workflowId, repoRoot)
    val existing = checkpoint.findSharedPreplan(identity)
    if (existing != null &&
      state.manifest.subtasks.all {
        checkpoint.findSubtaskPlan(identity, it.id, it.specPath) != null
      }
    ) {
      return
    }
    val preplan = verifiedStandalonePreplan(workflowId)
    val shared =
      sharedProduction.gatherSharedContext(
        state.copy(parentWorkflowId = identity.parentGoalWorkflowId),
        GoalRunnerRunRequest(issueKey, repoRoot, "standalone-plan-import"),
        existing?.let(sharedProduction::planningPacketFrom),
      )
    val provenance =
      GoalPlanningContractProvenance(
        shared.parentSpecHash,
        shared.decompositionManifestHash,
        GOAL_PLANNING_PREPARATION_SCHEMA_ID,
      )
    if (existing != null && existing.provenance != provenance) {
      invalid(workflowId, "saved goal planning provenance differs; use the existing scoped replan recovery")
    }
    val payload = existing?.preplanPayload ?: sharedProduction.enrichPreplan(preplan, shared.planningPacket)
    val sharedCheckpoint =
      existing ?: SharedGoalPreplanCheckpoint(
        identity = identity,
        provenance = provenance,
        payloadSha256 = sha256HexUtf8(payload),
        preplanPayload = payload,
      )
    val plans =
      state.manifest.subtasks.mapIndexedNotNull { order, subtask ->
        if (checkpoint.findSubtaskPlan(identity, subtask.id, subtask.specPath) != null) return@mapIndexedNotNull null
        val path = resolvedGovernedPath(shared.repoRoot, subtask.specPath, repositories)
        val spec = files.readText(path)
        if (!governedSubSpecReady(spec)) {
          invalid(workflowId, "subtask ${subtask.id} has no ready implementation details")
        }
        val planPayload = proseRecordPayload("plan", spec)
        GoalSubtaskPlanCheckpoint(
          identity = identity,
          subtaskId = subtask.id,
          manifestOrder = order,
          governedSubSpecPath = shared.repoRoot.relativize(path).joinToString("/"),
          subSpecHash = sha256HexUtf8(spec),
          provenance = provenance,
          payloadSha256 = sha256HexUtf8(planPayload),
          planPayload = planPayload,
        )
      }
    checkpoint.checkpointBundle(sharedCheckpoint, plans)
  }

  private fun verifiedStandalonePreplan(workflowId: String): String {
    val records =
      database.read { it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)?.artifacts?.phaseRecords() }
        ?: invalid(workflowId, "standalone plan workflow is missing")
    val preplan =
      records["preplan"]
        ?.takeIf { it.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED }
        ?.outputArtifact ?: invalid(workflowId, "standalone preplan is not completed")
    val plan =
      records["plan"]
        ?.takeIf { it.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED }
        ?.outputArtifact ?: invalid(workflowId, "standalone plan is not completed")
    readStoredPlanningRecord(plan, "plan", workflowId)
    readStoredPlanningRecord(preplan, "preplan", workflowId)
    return preplan
  }

  private fun planningIdentity(
    state: GoalRunnerManifestState,
    workflowId: String,
    repoRoot: Path,
  ): GoalPlanningIdentity {
    val ownerId =
      state.parentWorkflowId.takeIf { parentId ->
        parentId.isNotBlank() && parentId != workflowId &&
          database.read {
            it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentId)
              ?.steps?.any { step -> step.planWorkflowId == workflowId } == true
          }
      } ?: workflowId
    return GoalPlanningIdentity(ownerId, state.manifest.issueKey, repositories.repositoryIdentity(repoRoot))
  }

  private fun invalid(
    workflowId: String,
    reason: String,
  ): Nothing = throw invalidGoalPlanningPreparationSchemaError(workflowId, "standalone_plan", reason)
}
