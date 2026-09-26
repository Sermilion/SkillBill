package skillbill.engine.featuretask.phase.core

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePhaseStatus
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.featuretask.slot.PhaseStrategyStatusProjection
import skillbill.engine.work.model.IdeStatusCurrentPhaseExecution
import skillbill.engine.work.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.workflow.taskruntime.model.core.PhaseSlot
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries

internal data class FeatureTaskRuntimeCurrentPhaseExecutionContext(
  val currentPhaseId: String?,
  val records: Map<String, FeatureTaskRuntimePhaseRecord>,
  val phases: List<FeatureTaskRuntimePhaseStatus>,
  val ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  val gateRunCount: Int?,
)

class FeatureTaskRuntimeCurrentPhaseExecutionDeriver(
  private val strategies: PhaseStrategyLookup,
) {
  internal fun derive(
    context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
    facts: PhaseStrategySelectionFacts,
  ): IdeStatusCurrentPhaseExecution? {
    val phaseId = context.currentPhaseId?.takeIf(String::isNotBlank) ?: return null
    if (context.phases.none { it.phaseId == phaseId }) return null
    if (PhaseSlot.entries.none { phaseId in it.steps }) return defaultPhaseExecution(phaseId, context)
    val projection = strategies.strategyOrNull(phaseId, facts) as? PhaseStrategyStatusProjection
    return if (projection == null) {
      defaultPhaseExecution(phaseId, context)
    } else {
      projection.currentExecution(phaseId, context)
    }
  }

  internal fun loopOnlyStepIds(facts: PhaseStrategySelectionFacts): Set<String> {
    val traversal = strategies.traversal(facts)
    val unselectedLoopOnly = facts.definition.declaration().loopOnlyPhaseIds - traversal.forwardPhaseIds.toSet()
    return traversal.loopOnlyPhaseIds + unselectedLoopOnly
  }
}

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
