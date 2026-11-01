package skillbill.engine.featuretask.lifecycle.execution

import skillbill.error.featuretask.InvalidFeatureTaskRuntimeExecutionPlanSchemaError
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdgeCapScope
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeCrossSlotAllowance
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseEntryGate
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as Keys

internal fun encodeExecutionPlanTraversal(
  traversal: FeatureTaskRuntimeTransitionDeclaration,
  includeCrossSlotAllowance: Boolean,
): Map<String, Any?> =
  mapOf(
    Keys.FORWARD_STEPS to traversal.forwardPhaseIds,
    Keys.BACKWARD_EDGES to
      traversal.backwardEdges.map { edge ->
        linkedMapOf<String, Any?>(
          Keys.FROM_STEP to edge.fromPhaseId,
          Keys.VERDICT to edge.triggeringVerdict.wireValue,
          Keys.DESTINATION_STEP to edge.destinationPhaseId,
          Keys.LOOP_ID to edge.loopId,
          Keys.PER_EDGE_CAP to edge.perEdgeCap,
          Keys.CAP_EXHAUSTION_BEHAVIOR to edge.capExhaustionBehavior.name,
          Keys.CAP_SCOPE to edge.capScope.name,
          Keys.WARN_AFTER_ITERATIONS to edge.warnAfterIterations,
        ).apply {
          if (includeCrossSlotAllowance) put(Keys.CROSS_SLOT_ALLOWANCE, edge.crossSlotAllowance?.name)
        }
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

public fun decodeExecutionPlanTraversal(payload: Map<String, Any?>): FeatureTaskRuntimeTransitionDeclaration =
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
            FeatureTaskRuntimeCapExhaustionBehavior.entries.singleOrNull {
              it.name == planString(edge, Keys.CAP_EXHAUSTION_BEHAVIOR)
            } ?: invalidExecutionPlanTraversal(),
          capScope =
            FeatureTaskRuntimeBackwardEdgeCapScope.entries.singleOrNull { it.name == planString(edge, Keys.CAP_SCOPE) }
              ?: invalidExecutionPlanTraversal(),
          warnAfterIterations = (edge[Keys.WARN_AFTER_ITERATIONS] as? Number)?.toInt(),
          crossSlotAllowance =
            edge[Keys.CROSS_SLOT_ALLOWANCE]?.let { raw ->
              FeatureTaskRuntimeCrossSlotAllowance.entries.singleOrNull { it.name == raw }
                ?: invalidExecutionPlanTraversal()
            },
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

private fun invalidExecutionPlanTraversal(): Nothing =
  throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError("execution plan contains an invalid semantic value or digest")
