package skillbill.mcp.workflow

import skillbill.application.workflow.persist.WorkflowWireProjections
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.ports.workflow.WorkflowArtifactWireEncoder
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.model.goalobservability.GoalObservabilityEvent

internal fun workflowSnapshotMcpMap(
  snapshot: WorkflowSnapshotView,
  encoder: WorkflowArtifactWireEncoder,
  goalObservability: GoalObservabilityEvent?,
): LinkedHashMap<String, Any?> =
  LinkedHashMap(WorkflowWireProjections.snapshotMap(snapshot, encoder).toPayload()).apply {
    goalObservabilitySummary(goalObservability)?.let { summary ->
      put(WorkflowWirePayloadKeys.GOAL_OBSERVABILITY, summary)
    }
    (DurableWorkflowArtifactFamily.GOAL_PROGRESS_LATEST_EVENT.value(snapshot.artifacts) as? Map<*, *>)?.let { event ->
      put(WorkflowWirePayloadKeys.GOAL_PROGRESS, event)
    }
    (DurableWorkflowArtifactFamily.GOAL_ATTEMPT_LEDGER.value(snapshot.artifacts) as? List<*>)?.lastOrNull()?.let {
        entry ->
      put(WorkflowWirePayloadKeys.GOAL_ATTEMPT_LEDGER_LATEST, entry)
    }
  }

private fun goalObservabilitySummary(goalObservability: GoalObservabilityEvent?): Map<String, Any?>? =
  goalObservability
    ?.toCompactSummaryWire()
    ?.let(JsonCodec::anyToStringAnyMap)
