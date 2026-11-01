package skillbill.engine.featuretask.phase.core

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePhaseStatus
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries

public data class FeatureTaskRuntimeCurrentPhaseExecutionContext(
  public val currentPhaseId: String?,
  public val records: Map<String, FeatureTaskRuntimePhaseRecord>,
  public val phases: List<FeatureTaskRuntimePhaseStatus>,
  public val ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  public val gateRunCount: Int?,
  public val acceptanceCriterionRepair: AcceptanceCriterionRepairState? = null,
)

internal fun defaultPhaseExecution(
  phaseId: String,
  context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
): IdeStatusCurrentPhaseExecution? = edgePhaseExecution(phaseId, context) ?: attemptPhaseExecution(phaseId, context)

internal fun attemptPhaseExecution(
  phaseId: String,
  context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
): IdeStatusCurrentPhaseExecution? =
  context.phases
    .firstOrNull { it.phaseId == phaseId }
    ?.attemptCount
    ?.takeIf { it >= 1 }
    ?.let { count ->
      IdeStatusCurrentPhaseExecution(
        phaseId = phaseId,
        kind = IdeStatusCurrentPhaseExecutionKind.ATTEMPT,
        count = count,
      )
    }

private fun edgePhaseExecution(
  phaseId: String,
  context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
): IdeStatusCurrentPhaseExecution? {
  val (loopId, edgeIteration) = activeEdgeContext(phaseId, context.records[phaseId], context.ledger) ?: return null
  val edge = FeatureTaskRuntimePhaseWorkflowQueries.backwardEdgeForLoop(loopId) ?: return null
  return IdeStatusCurrentPhaseExecution(
    phaseId = phaseId,
    kind =
      if (edge.perEdgeCap == null) {
        IdeStatusCurrentPhaseExecutionKind.SEMANTIC_LOOP
      } else {
        IdeStatusCurrentPhaseExecutionKind.BOUNDED_EDGE
      },
    count = edgeIteration,
    total = edge.perEdgeCap,
  )
}

private fun activeEdgeContext(
  phaseId: String,
  record: FeatureTaskRuntimePhaseRecord?,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
): Pair<String, Int>? {
  val edgeEntry =
    ledger
      .filter {
        it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
          it.phaseId == phaseId &&
          it.loopId != null &&
          it.edgeIteration != null
      }
      .maxByOrNull { it.sequenceNumber }
  if (edgeEntry != null) return edgeEntry.loopId!! to edgeEntry.edgeIteration!!
  record?.loopId?.let { loopId ->
    record.edgeIteration?.let { return loopId to it }
  }
  return null
}
