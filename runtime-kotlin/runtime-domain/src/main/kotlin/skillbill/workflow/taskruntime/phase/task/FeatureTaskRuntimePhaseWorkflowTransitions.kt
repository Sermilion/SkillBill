package skillbill.workflow.taskruntime.phase.task

import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdgeCapScope
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeCrossSlotAllowance
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
            phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA,
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
        ) + monitorFixEdges() +
          qaFixEdges() +
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
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX,
        ),
      loopOnlySuccessors =
        mapOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX to
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX,
        ),
    )

  private fun qaFixEdges(): List<FeatureTaskRuntimeBackwardEdge> =
    listOf(
      FeatureTaskRuntimeBackwardEdge(
        fromPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA,
        triggeringVerdict = FeatureTaskRuntimeVerdict.QA_FINDINGS,
        destinationPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX,
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.QA_FIX_LOOP_ID,
        perEdgeCap = FeatureTaskRuntimePhaseWorkflowDefinition.MAX_QA_FIX_ATTEMPTS,
        capExhaustionBehavior = FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE,
        capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
      ),
      FeatureTaskRuntimeBackwardEdge(
        fromPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX,
        triggeringVerdict = FeatureTaskRuntimeVerdict.ADVANCE,
        destinationPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.QA_FIX_RETURN_LOOP_ID,
        perEdgeCap = null,
        crossSlotAllowance = FeatureTaskRuntimeCrossSlotAllowance.DECLARED,
      ),
      FeatureTaskRuntimeBackwardEdge(
        fromPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX,
        triggeringVerdict = FeatureTaskRuntimeVerdict.ADVANCE,
        destinationPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.QA_FIX_BUILD_LOOP_ID,
        perEdgeCap = null,
        crossSlotAllowance = FeatureTaskRuntimeCrossSlotAllowance.DECLARED,
      ),
      FeatureTaskRuntimeBackwardEdge(
        fromPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
        triggeringVerdict = FeatureTaskRuntimeVerdict.SATISFIED,
        destinationPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA,
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.QA_REQUALIFY_LOOP_ID,
        perEdgeCap = null,
        crossSlotAllowance = FeatureTaskRuntimeCrossSlotAllowance.DECLARED,
      ),
      FeatureTaskRuntimeBackwardEdge(
        fromPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
        triggeringVerdict = FeatureTaskRuntimeVerdict.QA_INVALIDATED,
        destinationPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.QA_INVALIDATION_LOOP_ID,
        perEdgeCap = FeatureTaskRuntimePhaseWorkflowDefinition.MAX_QA_INVALIDATIONS,
        capExhaustionBehavior = FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE,
        capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
        crossSlotAllowance = FeatureTaskRuntimeCrossSlotAllowance.DECLARED,
      ),
    )

  val composedOnlyWithSteps: Map<String, Set<String>> =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.QA_INVALIDATION_LOOP_ID to
        setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA),
    )

  val composedOnlyWithoutSteps: Map<String, Set<String>> =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.QA_FIX_BUILD_LOOP_ID to
        setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT),
      FeatureTaskRuntimePhaseWorkflowDefinition.QA_REQUALIFY_LOOP_ID to
        setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT),
    )

  private fun monitorFixEdges(): List<FeatureTaskRuntimeBackwardEdge> =
    listOf(
      FeatureTaskRuntimeBackwardEdge(
        fromPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR,
        triggeringVerdict = FeatureTaskRuntimeVerdict.CI_FAILED,
        destinationPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX,
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.MONITOR_FIX_LOOP_ID,
        perEdgeCap = FeatureTaskRuntimePhaseWorkflowDefinition.MAX_MONITOR_FIX_ATTEMPTS - 1,
        capExhaustionBehavior = FeatureTaskRuntimeCapExhaustionBehavior.BLOCK,
        capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
      ),
      FeatureTaskRuntimeBackwardEdge(
        fromPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR,
        triggeringVerdict = FeatureTaskRuntimeVerdict.PROGRESS,
        destinationPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX,
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.MONITOR_FIX_TRIGGER_LOOP_ID,
        perEdgeCap = null,
        capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
      ),
      FeatureTaskRuntimeBackwardEdge(
        fromPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX,
        triggeringVerdict = FeatureTaskRuntimeVerdict.ADVANCE,
        destinationPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR,
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.MONITOR_FIX_COMMIT_LOOP_ID,
        perEdgeCap = null,
        capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
      ),
    )

  fun backwardEdgeForLoop(
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    loopId: String,
  ): FeatureTaskRuntimeBackwardEdge? = transitions.backwardEdges.firstOrNull { it.loopId == loopId }
}
