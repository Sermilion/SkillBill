package skillbill.engine.goalrunner.qa

import skillbill.engine.featuretask.phaserun.PhaseRunCompletedOutput
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.history.GoalFinalRepairOutput
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRepairOutput
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
    if (definition == SkeletonDefinition.GOAL_BUILD) {
      reconcileInitialBuild(state, result.invocationId)
      return
    }
    val validationRepairs =
      if (definition == SkeletonDefinition.VALIDATION) {
        outcomeStore.goalQualityExecution(state.parentWorkflowId)?.execution?.let { execution ->
          execution.validationOccurrences.flatMap { archived ->
            archived.progress.validationGateProgress?.acceptedRepairOutputs.orEmpty()
          } +
            execution.progress?.validation?.validationGateProgress?.acceptedRepairOutputs.orEmpty()
        }.orEmpty()
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
    val retainedOutputs =
      if (definition == SkeletonDefinition.VALIDATION) {
        outcomeStore.goalQualityExecution(state.parentWorkflowId)?.execution?.let { execution ->
          execution.validationOccurrences.flatMap { it.progress.acceptedOutputs } +
            execution.progress?.validation?.acceptedOutputs.orEmpty()
        }.orEmpty()
      } else {
        emptyList()
      }
    (
      retainedOutputs.map { output ->
        PhaseRunCompletedOutput(output.phaseId, output.occurrence, output.output)
      } + result.identifiedCompletedOutputs
    )
      .filter {
        definition == SkeletonDefinition.VALIDATION &&
          it.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ||
          it.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX
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

  fun reconcileInitialBuild(
    state: GoalRunnerManifestState,
    invocationId: String,
  ) {
    val execution =
      outcomeStore.goalQualityExecution(state.parentWorkflowId)?.execution
        ?.takeIf { it.invocationId == invocationId } ?: return
    val repairs =
      (
        execution.preQaBuildOccurrences.flatMap { it.progress.buildGateProgress?.acceptedRepairOutputs.orEmpty() } +
          execution.preQaBuildOccurrences.flatMap { it.progress.carriedBuildRepairOutputs } +
          execution.progress?.build?.carriedBuildRepairOutputs.orEmpty() +
          execution.progress?.build?.buildGateProgress?.acceptedRepairOutputs.orEmpty()
      )
    val repairsByTurn = linkedMapOf<Int, FeatureTaskRuntimeValidationGateRepairOutput>()
    repairs.forEach { repair ->
      rememberUniqueRepair(repairsByTurn, repair, state.parentWorkflowId)
    }
    val reconciled = repairsByTurn.values.sortedBy { it.repairTurn }
    val history = outcomeStore.goalFinalRepairContext(state.parentWorkflowId)?.outputs.orEmpty()
    reconciled.forEach { repair ->
      requireAgreedRepairHistory(history, invocationId, repair, state.parentWorkflowId)
    }
    reconciled.forEach { repair ->
      if (!outcomeStore.recordGoalFinalRepairOutput(
          state.parentWorkflowId,
          invocationId,
          GoalFinalRepairOutput(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
            invocationId,
            repair.repairTurn,
            repair.output,
          ),
        )
      ) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
          "Could not reconcile accepted initial build repair '${repair.repairTurn}' for '${state.parentWorkflowId}'.",
        )
      }
    }
  }

  private fun rememberUniqueRepair(
    repairsByTurn: MutableMap<Int, FeatureTaskRuntimeValidationGateRepairOutput>,
    repair: FeatureTaskRuntimeValidationGateRepairOutput,
    parentWorkflowId: String,
  ) {
    val prior = repairsByTurn.putIfAbsent(repair.repairTurn, repair)
    if (prior != null && prior != repair) conflictingRepair(repair.repairTurn, parentWorkflowId)
  }

  private fun requireAgreedRepairHistory(
    history: List<GoalFinalRepairOutput>,
    invocationId: String,
    repair: FeatureTaskRuntimeValidationGateRepairOutput,
    parentWorkflowId: String,
  ) {
    val prior = history.filter { sameBuildRepair(it, invocationId, repair.repairTurn) }
    val conflicting = prior.any { it.output != repair.output } || prior.map { it.output }.distinct().size > 1
    if (conflicting) conflictingRepair(repair.repairTurn, parentWorkflowId)
  }

  private fun sameBuildRepair(
    output: GoalFinalRepairOutput,
    invocationId: String,
    repairTurn: Int,
  ): Boolean =
    output.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD &&
      output.invocationId == invocationId &&
      output.occurrence == repairTurn

  private fun conflictingRepair(
    repairTurn: Int,
    parentWorkflowId: String,
  ): Nothing =
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
      "Conflicting accepted initial build repair '$repairTurn' for '$parentWorkflowId'.",
    )

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
