package skillbill.ports.featuretask

import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementReasonLookup
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementWriteResult
import java.time.Instant

interface FeatureTaskPhaseSettlementRepository {
  fun upsert(settlement: FeatureTaskPhaseSettlement): FeatureTaskPhaseSettlementWriteResult

  fun find(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): FeatureTaskPhaseSettlement?

  fun history(
    workflowId: String,
    phaseId: String,
  ): List<FeatureTaskPhaseSettlement>

  fun findReasonAtOrBefore(
    workflowId: String,
    phaseId: String,
    startedAt: Instant,
    recordedAt: Instant,
    storedReason: String,
  ): FeatureTaskPhaseSettlementReasonLookup

  fun delete(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): Boolean
}
