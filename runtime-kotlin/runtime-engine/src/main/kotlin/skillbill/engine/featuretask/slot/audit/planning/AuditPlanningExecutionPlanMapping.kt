package skillbill.engine.featuretask.slot.audit.planning

import skillbill.engine.featuretask.slot.PhaseExecutionPlanMapping
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object AuditPlanningExecutionPlanMapping {
  fun map(
    recorded: ResolvedPhaseExecutionPlan,
    current: ResolvedPhaseExecutionPlan,
    audit: PhaseStrategy,
  ): PhaseExecutionPlanMapping? {
    val identity = recorded.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT }
    if (identity?.strategyId != audit.strategyId || identity.semanticRevision != 1 || audit.semanticRevision != 2) {
      return null
    }
    return PhaseExecutionPlanMapping(
      previous = legacyComposition(current, audit, recorded),
      supported = copyWithEvidence(current, recorded),
    )
  }

  private fun legacyComposition(
    current: ResolvedPhaseExecutionPlan,
    audit: PhaseStrategy,
    recorded: ResolvedPhaseExecutionPlan,
  ): ResolvedPhaseExecutionPlan {
    val planStep = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX
    val repairStep = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX
    val auditSteps = listOf(repairStep, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
    val traversal =
      current.traversal.copy(
        forwardPhaseIds = current.traversal.forwardPhaseIds - planStep,
        backwardEdges =
          current.traversal.backwardEdges.map { edge ->
            if (edge.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_REPAIR_LOOP_ID) {
              edge.copy(destinationPhaseId = repairStep)
            } else {
              edge
            }
          },
        loopOnlyPhaseIds = current.traversal.loopOnlyPhaseIds - planStep,
        loopOnlySuccessors = current.traversal.loopOnlySuccessors - planStep,
      )
    return ResolvedPhaseExecutionPlan(
      current.definitionId,
      current.definitionSemanticRevision,
      current.selectedStrategies.map { identity ->
        if (identity.slot == PhaseSlot.AUDIT) identity.copy(semanticRevision = 1, steps = auditSteps) else identity
      },
      current.reviewSelection,
      current.qualityGateSelection,
      traversal,
      (current.dispatchStrategyByStep - planStep).mapValues { (_, owner) ->
        if (owner.slot == PhaseSlot.AUDIT) owner.copy(semanticRevision = 1) else owner
      },
      (current.stepPolicyIdentities - planStep).mapValues { (step, identity) ->
        if (step in auditSteps) {
          audit.policyFor(
            step,
          ).semanticIdentity(audit.strategyId, 1, step)
        } else {
          identity
        }
      },
      (current.resumeInterpretationIdentities - planStep).mapValues { (step, identity) ->
        if (step in auditSteps) "${audit.strategyId}/1:$step" else identity
      },
      recorded.effectivePolicies,
      recorded.effectivePolicySettings,
    )
  }

  private fun copyWithEvidence(
    current: ResolvedPhaseExecutionPlan,
    recorded: ResolvedPhaseExecutionPlan,
  ): ResolvedPhaseExecutionPlan =
    ResolvedPhaseExecutionPlan(
      current.definitionId,
      current.definitionSemanticRevision,
      current.selectedStrategies,
      current.reviewSelection,
      current.qualityGateSelection,
      current.traversal,
      current.dispatchStrategyByStep,
      current.stepPolicyIdentities,
      current.resumeInterpretationIdentities,
      recorded.effectivePolicies,
      recorded.effectivePolicySettings,
    )
}
