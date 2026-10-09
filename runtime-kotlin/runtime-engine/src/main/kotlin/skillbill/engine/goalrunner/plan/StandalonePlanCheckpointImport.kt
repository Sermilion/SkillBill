package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalplanning.GoalPlanningPreparationProjectionGate
import skillbill.engine.goalplanning.appliedOrThrow
import skillbill.engine.goalplanning.planOrThrow
import skillbill.engine.goalplanning.toFailure
import skillbill.error.shellcontent.invalidGoalPlanningPreparationSchemaError
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.SharedGoalPreplanLookupResult
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.workflow.decomposition.model.DecompositionManifest

@Inject
class StandalonePlanCheckpointImport(
  private val envelopeValidator: FeatureTaskRuntimeWireArtifactValidator,
) {
  private val gate = GoalPlanningPreparationProjectionGate(envelopeValidator)

  internal fun import(
    unitOfWork: UnitOfWork,
    sourceWorkflowId: String?,
    identity: GoalPlanningIdentity,
    manifest: DecompositionManifest,
  ) {
    if (sourceWorkflowId == null) return
    val sourceIdentity = identity.copy(parentGoalWorkflowId = sourceWorkflowId)
    val store = unitOfWork.goalPlanningPreparations
    when (val target = store.findSharedPreplan(identity)) {
      is SharedGoalPreplanLookupResult.Found -> if (target.checkpoint != null) return
      is SharedGoalPreplanLookupResult.Conflicted -> throw target.conflict.toFailure()
    }
    val shared =
      when (val result = store.findSharedPreplan(sourceIdentity)) {
        is SharedGoalPreplanLookupResult.Found -> result.checkpoint
        is SharedGoalPreplanLookupResult.Conflicted -> throw result.conflict.toFailure()
      } ?: invalid(
        sourceWorkflowId,
        "completed standalone plan has no implementation checkpoints; " +
          "settle it with `skill-bill phase plan ${manifest.issueKey}`",
      )
    gate.validateSharedPreplan(shared)
    manifest.subtasks.forEach { subtask ->
      store.findSubtaskPlan(sourceIdentity, subtask.id, subtask.specPath).planOrThrow()
        ?.also(gate::validateSubtaskPlan)
        ?: invalid(
          sourceWorkflowId,
          "standalone plan checkpoint for subtask ${subtask.id} is missing",
        )
    }
    store.transferPlanningOwnership(sourceIdentity, identity).appliedOrThrow()
  }

  private fun invalid(
    workflowId: String,
    reason: String,
  ): Nothing = throw invalidGoalPlanningPreparationSchemaError(workflowId, "standalone_plan", reason)
}
