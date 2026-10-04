package skillbill.workflow.taskruntime.phase.task

import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdgeCapScope
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseEntryGate
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration

internal object FeatureTaskRuntimePhaseWorkflowTransitions {
  fun transitions(definition: WorkflowDefinition): FeatureTaskRuntimeTransitionDeclaration =
    FeatureTaskRuntimeTransitionDeclaration(
      forwardPhaseIds = definition.stepIds,
      entryGates =
        listOf(
          FeatureTaskRuntimePhaseEntryGate(
            phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
            requiredPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
            requiredVerdict = FeatureTaskRuntimeVerdict.SATISFIED,
          ),
          FeatureTaskRuntimePhaseEntryGate(
            phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
            requiredPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
            requiredVerdict = FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED,
          ),
        ),
      backwardEdges =
        listOf(
          FeatureTaskRuntimeBackwardEdge(
            fromPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
            triggeringVerdict = FeatureTaskRuntimeVerdict.ADVANCE,
            destinationPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX,
            loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_REPAIR_LOOP_ID,
            perEdgeCap = null,
            warnAfterIterations = FeatureTaskRuntimePhaseWorkflowDefinition.SEMANTIC_LOOP_WARNING_THRESHOLD,
          ),
          FeatureTaskRuntimeBackwardEdge(
            fromPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
            triggeringVerdict = FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED,
            destinationPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
            loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID,
            perEdgeCap = 1,
            capExhaustionBehavior = FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE,
            capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
          ),
        ) +
          FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_LOOP_ID_BY_PRODUCER.map { (producer, loopId) ->
            FeatureTaskRuntimeBackwardEdge(
              fromPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
              triggeringVerdict = FeatureTaskRuntimeVerdict.RECORD_REJECTED,
              destinationPhaseId = producer,
              loopId = loopId,
              perEdgeCap = FeatureTaskRuntimePhaseWorkflowDefinition.MAX_RECORD_REGENERATION_ATTEMPTS,
              capExhaustionBehavior = FeatureTaskRuntimeCapExhaustionBehavior.BLOCK,
              capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
            )
          },
      loopOnlyPhaseIds =
        setOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
        ),
      loopOnlySuccessors =
        mapOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX to
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX,
        ),
    )

  fun backwardEdgeForLoop(
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    loopId: String,
  ): FeatureTaskRuntimeBackwardEdge? = transitions.backwardEdges.firstOrNull { it.loopId == loopId }
}
