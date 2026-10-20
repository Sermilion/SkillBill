package skillbill.engine.featuretask.runloop.phase

import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairStepRoles
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpointRemediation
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runloop.state.coupledSession
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot

internal object FeatureTaskRuntimeRunLoopAcceptanceRepair {
  sealed interface Routing {
    data class Reenter(val phaseId: String) : Routing

    data object Continue : Routing

    data object Stop : Routing
  }

  fun ownsCompletedPhase(
    phaseId: String,
    steps: AcceptanceCriterionRepairStepRoles?,
  ): Boolean =
    phaseId in
      setOf(
        steps?.audit,
        steps?.repair,
      )

  fun resume(context: FeatureTaskRuntimeRunLoopContext): String? {
    val scope = (context.request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair ?: return null
    val state = scope.snapshot ?: return null
    val steps = scope.steps
    if (steps == null || state.stage == AcceptanceCriterionRepairStage.ACCEPTED ||
      context.hasLeftSlot(PhaseSlot.AUDIT)
    ) {
      return null
    }
    val target =
      if (state.pendingClarificationQuestion != null) {
        steps.audit
      } else {
        when (state.stage) {
          AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED ->
            if (state.mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY) {
              steps.audit
            } else {
              steps.planning
            }
          AcceptanceCriterionRepairStage.REPAIR_RESERVED ->
            steps.repair
          else -> steps.audit
        }
      }
    val reopened =
      if (target == steps.planning) {
        listOf(target, steps.repair, steps.audit)
      } else {
        listOf(target)
      }
    context.runState.coupledRunTransitions.reenterAcceptanceSteps(reopened)
    return target
  }

  fun next(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
  ): Routing {
    val scope = (context.request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair
    val state = scope?.snapshot
    val steps = scope?.steps
    val inactive = scope == null || state == null || steps == null || !ownsCompletedPhase(phaseId, steps)
    if (inactive) return Routing.Continue
    requireNotNull(state)
    requireNotNull(scope)
    requireNotNull(steps)
    if (state.stage == AcceptanceCriterionRepairStage.ACCEPTED) return Routing.Continue
    if (state.stage == AcceptanceCriterionRepairStage.BUDGET_BLOCKED) {
      FeatureTaskRuntimeRunLoopPhaseBlocking.blockAt(
        context.request,
        context.state,
        context.runState.coupledSession(),
        phaseId,
        "The current acceptance criterion exhausted its three unsuccessful repair attempts. " +
          "Other criteria have separate budgets. ${scope.currentCriterionContext}",
      )
      return Routing.Stop
    }
    return reenterFocusedWork(context, phaseId, state, steps)
  }

  private fun reenterFocusedWork(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
    state: AcceptanceCriterionRepairState,
    steps: AcceptanceCriterionRepairStepRoles,
  ): Routing {
    val target =
      when (state.stage) {
        AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED ->
          if (state.mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY) {
            steps.audit
          } else {
            steps.planning
          }
        AcceptanceCriterionRepairStage.REPAIR_RESERVED ->
          if (state.pendingClarificationQuestion != null) steps.audit else steps.repair
        else -> steps.audit
      }
    if (target == steps.planning) {
      if (!FeatureTaskRuntimeRunLoopCheckpointRemediation.establishRemediationCheckpoint(
          context,
          phaseId,
          steps.repairLoopId,
        )
      ) {
        return Routing.Stop
      }
      context.runState.coupledRunTransitions.reenterAcceptanceSteps(
        listOf(
          target,
          steps.repair,
          steps.audit,
        ),
      )
    } else {
      context.runState.coupledRunTransitions.reenterAcceptanceSteps(listOf(target))
    }
    return Routing.Reenter(target)
  }

  fun reviewEntryBlockReason(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
  ): String? {
    if (context.runState.selectedOwnerOf(phaseId)?.slot != PhaseSlot.CODE_REVIEW) return null
    val scope = (context.request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair ?: return null
    val state = scope.snapshot ?: return "The authored acceptance catalog has not been audited yet."
    return if (state.stage != AcceptanceCriterionRepairStage.ACCEPTED) {
      "Not every acceptance criterion is accepted yet. Resume the criterion audit before review."
    } else {
      null
    }
  }
}
