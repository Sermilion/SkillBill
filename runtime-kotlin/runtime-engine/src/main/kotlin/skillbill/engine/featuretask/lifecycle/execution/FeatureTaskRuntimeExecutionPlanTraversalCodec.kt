package skillbill.engine.featuretask.lifecycle.execution

import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdgeCapScope
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseEntryGate
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as Keys

internal fun encodeExecutionPlanTraversal(traversal: FeatureTaskRuntimeTransitionDeclaration): Map<String, Any?> =
  mapOf(
    Keys.FORWARD_STEPS to traversal.forwardPhaseIds,
    Keys.BACKWARD_EDGES to
      traversal.backwardEdges.map { edge ->
        mapOf(
          Keys.FROM_STEP to edge.fromPhaseId,
          Keys.VERDICT to edge.triggeringVerdict.wireValue,
          Keys.DESTINATION_STEP to edge.destinationPhaseId,
          Keys.LOOP_ID to edge.loopId,
          Keys.PER_EDGE_CAP to edge.perEdgeCap,
          Keys.CAP_EXHAUSTION_BEHAVIOR to edge.capExhaustionBehavior.name,
          Keys.CAP_SCOPE to edge.capScope.name,
          Keys.WARN_AFTER_ITERATIONS to edge.warnAfterIterations,
        )
      },
    Keys.LOOP_ONLY_STEPS to traversal.loopOnlyPhaseIds.toList(),
    Keys.ENTRY_GATES to
      traversal.entryGates.map { gate ->
        mapOf(
          Keys.STEP to gate.phaseId,
          Keys.REQUIRED_STEP to gate.requiredPhaseId,
          Keys.REQUIRED_VERDICT to gate.requiredVerdict.wireValue,
        )
      },
    Keys.LOOP_ONLY_SUCCESSORS to
      traversal.loopOnlySuccessors.map { (step, successor) ->
        mapOf(Keys.STEP to step, Keys.SUCCESSOR to successor)
      },
  )

internal fun decodeExecutionPlanTraversal(payload: Map<String, Any?>): FeatureTaskRuntimeTransitionDeclaration =
  FeatureTaskRuntimeTransitionDeclaration(
    forwardPhaseIds = planStrings(payload, Keys.FORWARD_STEPS),
    backwardEdges =
      planObjects(payload, Keys.BACKWARD_EDGES).map { edge ->
        FeatureTaskRuntimeBackwardEdge(
          fromPhaseId = planString(edge, Keys.FROM_STEP),
          triggeringVerdict = FeatureTaskRuntimeVerdict(planString(edge, Keys.VERDICT)),
          destinationPhaseId = planString(edge, Keys.DESTINATION_STEP),
          loopId = planString(edge, Keys.LOOP_ID),
          perEdgeCap = (edge[Keys.PER_EDGE_CAP] as? Number)?.toInt(),
          capExhaustionBehavior =
            FeatureTaskRuntimeCapExhaustionBehavior.valueOf(
              planString(edge, Keys.CAP_EXHAUSTION_BEHAVIOR),
            ),
          capScope = FeatureTaskRuntimeBackwardEdgeCapScope.valueOf(planString(edge, Keys.CAP_SCOPE)),
          warnAfterIterations = (edge[Keys.WARN_AFTER_ITERATIONS] as? Number)?.toInt(),
        )
      },
    loopOnlyPhaseIds = planStrings(payload, Keys.LOOP_ONLY_STEPS).toSet(),
    entryGates =
      planObjects(payload, Keys.ENTRY_GATES).map { gate ->
        FeatureTaskRuntimePhaseEntryGate(
          planString(gate, Keys.STEP),
          planString(gate, Keys.REQUIRED_STEP),
          FeatureTaskRuntimeVerdict(planString(gate, Keys.REQUIRED_VERDICT)),
        )
      },
    loopOnlySuccessors =
      planObjects(payload, Keys.LOOP_ONLY_SUCCESSORS).associate {
        planString(it, Keys.STEP) to planString(it, Keys.SUCCESSOR)
      },
  )
