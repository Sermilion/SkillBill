package skillbill.cli.workflow

import skillbill.application.workflow.persist.WorkflowWireProjections
import skillbill.contracts.JsonCodec
import skillbill.ports.workflow.WorkflowArtifactWireEncoder
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.model.goalobservability.GoalObservabilityEvent

internal fun workflowSnapshotCliMap(
  snapshot: WorkflowSnapshotView,
  encoder: WorkflowArtifactWireEncoder,
  goalObservability: GoalObservabilityEvent?,
): LinkedHashMap<String, Any?> =
  LinkedHashMap(WorkflowWireProjections.snapshotMap(snapshot, encoder).toPayload()).apply {
    goalObservabilitySummary(goalObservability)?.let { summary ->
      put("goal_observability", summary)
    }
  }

private fun goalObservabilitySummary(goalObservability: GoalObservabilityEvent?): Map<String, Any?>? =
  goalObservability
    ?.toCompactSummaryWire()
    ?.let(JsonCodec::anyToStringAnyMap)
