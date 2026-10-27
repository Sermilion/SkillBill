package skillbill.engine.goalrunner.qa

import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.history.GoalFinalRepairOutput
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.util.concurrent.atomic.AtomicReference

internal class GoalQualityRepairOutputs(private val outcomeStore: GoalRunnerWorkflowOutcomeStore) {
  fun sink(
    state: GoalRunnerManifestState,
    definition: SkeletonDefinition,
    persistenceFailure: AtomicReference<SkillBillRuntimeException?>,
  ): (String, String, Int, String) -> Unit =
    { phaseId, invocationId, occurrence, output ->
      val accepted =
        (
          definition == SkeletonDefinition.GOAL_QA &&
            phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX
        ) ||
          (
            definition == SkeletonDefinition.VALIDATION &&
              phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
          )
      if (accepted) {
        try {
          if (!outcomeStore.recordGoalFinalRepairOutput(
              state.parentWorkflowId,
              invocationId,
              GoalFinalRepairOutput(phaseId, invocationId, occurrence, output),
            )
          ) {
            throw SkillBillRuntimeException(
              WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
              "Could not persist accepted final repair output '$phaseId#$occurrence' for '${state.parentWorkflowId}'.",
            )
          }
        } catch (failure: SkillBillRuntimeException) {
          persistenceFailure.set(failure)
          throw failure
        }
      }
    }

  fun reconcile(
    state: GoalRunnerManifestState,
    definition: SkeletonDefinition,
    result: PhaseRunResult,
  ) {
    val validationRepairs =
      if (definition == SkeletonDefinition.VALIDATION) {
        outcomeStore.goalQualityExecution(state.parentWorkflowId)
          ?.execution
          ?.progress
          ?.validation
          ?.validationGateProgress
          ?.acceptedRepairOutputs
          .orEmpty()
      } else {
        emptyList()
      }
    validationRepairs.forEach { repair ->
      if (!outcomeStore.recordGoalFinalRepairOutput(
          state.parentWorkflowId,
          result.invocationId,
          GoalFinalRepairOutput(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
            result.invocationId,
            repair.repairTurn,
            repair.output,
          ),
        )
      ) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
          "Could not reconcile accepted validation repair '${repair.repairTurn}' for '${state.parentWorkflowId}'.",
        )
      }
    }
    result.identifiedCompletedOutputs
      .filter {
        definition == SkeletonDefinition.VALIDATION ||
          it.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX
      }
      .filterNot {
        validationRepairs.isNotEmpty() &&
          it.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
      }
      .forEach { output ->
        if (!outcomeStore.recordGoalFinalRepairOutput(
            state.parentWorkflowId,
            result.invocationId,
            GoalFinalRepairOutput(output.phaseId, result.invocationId, output.occurrence, output.output),
          )
        ) {
          throw SkillBillRuntimeException(
            WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
            "Could not reconcile accepted repair output '${output.phaseId}#${output.occurrence}' " +
              "for '${state.parentWorkflowId}'.",
          )
        }
      }
  }

  fun warning(
    parentWorkflowId: String,
    invocationId: String,
    warning: String?,
  ) {
    if (!outcomeStore.recordGoalFinalRepairWarning(parentWorkflowId, invocationId, warning)) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
        "Could not persist final QA repair context for '$parentWorkflowId'.",
      )
    }
  }
}
