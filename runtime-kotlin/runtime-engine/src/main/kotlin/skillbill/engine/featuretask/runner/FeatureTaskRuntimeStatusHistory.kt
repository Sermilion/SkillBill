package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseQuery
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeContinuationKind
import skillbill.engine.featuretask.slot.state.PhaseHistoricalInterpreter
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

public fun statusPhaseHistory(
  query: FeatureTaskRuntimePhaseQuery,
  workflowId: String,
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  history: PhaseHistoricalInterpreter,
): List<FeatureTaskRuntimePhaseLedgerEntry> {
  val actions = mutableMapOf<Pair<String, FeatureTaskRuntimePhaseLedgerAction>, FeatureTaskRuntimePhaseLedgerEntry>()
  val edges = mutableMapOf<String, FeatureTaskRuntimePhaseLedgerEntry>()
  val continuations = mutableMapOf<String, FeatureTaskRuntimePhaseLedgerEntry>()
  val declaredLoops = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.backwardEdges.map { it.loopId }.toSet()
  val keepEntry = history.ledgerEntryFilter(records)
  query.visitPhaseLedger(workflowId) { entry ->
    if (keepEntry(entry)) {
      actions.retainLatest(entry.phaseId to entry.action, entry)
      val loopId = entry.loopId
      if (entry.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && loopId in declaredLoops) {
        edges.retainLatest(requireNotNull(loopId), entry)
      }
      if (entry.action in CONTINUATION_KIND_ACTIONS &&
        FeatureTaskRuntimeContinuationKind.fromLedgerDetail(entry.blockedReason) != null
      ) {
        continuations.retainLatest(entry.phaseId, entry)
      }
    }
  }
  return (actions.values + edges.values + continuations.values).distinctBy { it.sequenceNumber }
    .sortedBy { it.sequenceNumber }
}

public fun <K> MutableMap<K, FeatureTaskRuntimePhaseLedgerEntry>.retainLatest(
  key: K,
  entry: FeatureTaskRuntimePhaseLedgerEntry,
) {
  if (get(key)?.sequenceNumber?.let { it > entry.sequenceNumber } != true) put(key, entry)
}
