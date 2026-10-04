package skillbill.cli.workflow

import skillbill.application.workflow.persist.WorkflowWireProjections
import skillbill.contracts.JsonCodec
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.model.goalobservability.GoalObservabilityEvent

internal fun workflowSnapshotCliMap(
  snapshot: WorkflowSnapshotView,
  goalObservability: GoalObservabilityEvent?,
): LinkedHashMap<String, Any?> =
  LinkedHashMap(WorkflowWireProjections.snapshotMap(snapshot).toPayload()).apply {
    goalObservabilitySummary(goalObservability)?.let { summary ->
      put("goal_observability", summary)
    }
  }

private fun goalObservabilitySummary(goalObservability: GoalObservabilityEvent?): Map<String, Any?>? =
  goalObservability
    ?.toCompactSummaryWire()
    ?.let(JsonCodec::anyToStringAnyMap)
