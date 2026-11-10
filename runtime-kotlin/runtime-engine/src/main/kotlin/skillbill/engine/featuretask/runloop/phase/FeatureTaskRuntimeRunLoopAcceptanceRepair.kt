package skillbill.engine.featuretask.runloop.phase

import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairStepRoles
import skillbill.engine.featuretask.model.audit.stepId
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpointRemediation
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runloop.state.coupledSession
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceRepairBlockReason
import skillbill.workflow.taskruntime.model.audit.AcceptanceRepairDecision
import skillbill.workflow.taskruntime.model.audit.AcceptanceRepairStep
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
    val scope = (context.request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair
    val state = scope?.snapshot ?: return null
    val steps = scope.steps
    val settled = AcceptanceRepairDecision.next(state) == AcceptanceRepairStep.Accepted
    val tracePending = state.stage == AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED
    val leftAudit = !tracePending && context.hasLeftSlot(PhaseSlot.AUDIT)
    if (steps == null || settled || leftAudit) return null
    val target = AcceptanceRepairDecision.next(state).stepId(steps) ?: return null
    val reopened =
      if (target == steps.planning) {
        listOf(target, steps.repair, steps.audit)
      } else if (target == steps.repair && state.stage == AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED) {
        listOf(target, steps.audit)
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
    val decision = AcceptanceRepairDecision.next(state)
    if (decision == AcceptanceRepairStep.Accepted) return Routing.Continue
    if (decision is AcceptanceRepairStep.Blocked) {
      FeatureTaskRuntimeRunLoopPhaseBlocking.blockAt(
        context.request,
        context.state,
        context.runState.coupledSession(),
        phaseId,
        AcceptanceRepairBlockReason.build(state, decision.criterionRef),
      )
      return Routing.Stop
    }
    return reenterFocusedWork(context, phaseId, decision, steps)
  }

  private fun reenterFocusedWork(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
    decision: AcceptanceRepairStep,
    steps: AcceptanceCriterionRepairStepRoles,
  ): Routing {
    val target = decision.stepId(steps) ?: return Routing.Continue
    val planningReentry = target == steps.planning
    val repairReentryAfterAudit = target == steps.repair && phaseId == steps.audit
    if (planningReentry || repairReentryAfterAudit) {
      if (!FeatureTaskRuntimeRunLoopCheckpointRemediation.establishRemediationCheckpoint(
          context,
          phaseId,
          steps.repairLoopId,
        )
      ) {
        return Routing.Stop
      }
      val reopened =
        if (planningReentry) listOf(target, steps.repair, steps.audit) else listOf(target, steps.audit)
      context.runState.coupledRunTransitions.reenterAcceptanceSteps(reopened)
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
