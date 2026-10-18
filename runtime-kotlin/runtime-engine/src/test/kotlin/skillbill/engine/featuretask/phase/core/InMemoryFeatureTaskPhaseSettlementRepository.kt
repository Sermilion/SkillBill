package skillbill.engine.featuretask.phase.core

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementReasonLookup
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementWriteResult
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryFeatureTaskPhaseSettlementRepository : FeatureTaskPhaseSettlementRepository {
  private val rows = ConcurrentHashMap<String, FeatureTaskPhaseSettlement>()

  override fun upsert(settlement: FeatureTaskPhaseSettlement): FeatureTaskPhaseSettlementWriteResult {
    val key = key(settlement.workflowId, settlement.phaseId, settlement.attempt)
    val previous = rows.putIfAbsent(key, settlement)
    return when {
      previous == null -> FeatureTaskPhaseSettlementWriteResult.Accepted
      previous.kind == settlement.kind && previous.envelopeJson == settlement.envelopeJson ->
        FeatureTaskPhaseSettlementWriteResult.IdenticalReplay
      else -> FeatureTaskPhaseSettlementWriteResult.Conflicting
    }
  }

  override fun find(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): FeatureTaskPhaseSettlement? = rows[key(workflowId, phaseId, attempt)]

  override fun history(
    workflowId: String,
    phaseId: String,
  ): List<FeatureTaskPhaseSettlement> =
    rows.values.filter { it.workflowId == workflowId && it.phaseId == phaseId }.sortedByDescending { it.attempt }

  override fun findReasonAtOrBefore(
    workflowId: String,
    phaseId: String,
    startedAt: Instant,
    recordedAt: Instant,
    storedReason: String,
  ): FeatureTaskPhaseSettlementReasonLookup {
    val matches =
      rows.values
        .asSequence()
        .filter {
          it.workflowId == workflowId && it.phaseId == phaseId &&
            Instant.parse(it.recordedAt) in startedAt..recordedAt
        }
        .filter { settlement ->
          val envelope =
            JsonCodec.parseObjectOrNull(settlement.envelopeJson)
              ?.let(JsonCodec::jsonElementToValue)?.let(JsonCodec::anyToStringAnyMap)
          val summary = envelope?.get(SharedPayloadKeys.SUMMARY) as? String
          val full =
            JsonCodec.anyToStringAnyMap(
              envelope?.get(SharedPayloadKeys.PRODUCED_OUTPUTS),
            )?.get(SharedPayloadKeys.VALUE) as? String
          envelope == null || summary != null && full != null && storedReason.endsWith(summary) &&
            (storedReason.removeSuffix(summary) + full).startsWith(storedReason.removeSuffix("..."))
        }
        .toList()
    return when (matches.size) {
      0 -> FeatureTaskPhaseSettlementReasonLookup.Missing
      1 -> FeatureTaskPhaseSettlementReasonLookup.Unique(matches.single())
      else -> FeatureTaskPhaseSettlementReasonLookup.Ambiguous
    }
  }

  override fun delete(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): Boolean = rows.remove(key(workflowId, phaseId, attempt)) != null

  private fun key(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): String = "$workflowId::$phaseId::$attempt"
}
