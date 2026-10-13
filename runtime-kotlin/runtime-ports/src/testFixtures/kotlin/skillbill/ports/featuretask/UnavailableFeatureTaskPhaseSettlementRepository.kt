package skillbill.ports.featuretask

import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementReasonLookup
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementWriteResult
import java.time.Instant

object UnavailableFeatureTaskPhaseSettlementRepository : FeatureTaskPhaseSettlementRepository {
  override fun upsert(settlement: FeatureTaskPhaseSettlement): FeatureTaskPhaseSettlementWriteResult = unavailable()

  override fun find(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): FeatureTaskPhaseSettlement? = unavailable()

  override fun history(
    workflowId: String,
    phaseId: String,
  ): List<FeatureTaskPhaseSettlement> = unavailable()

  override fun findReasonAtOrBefore(
    workflowId: String,
    phaseId: String,
    startedAt: Instant,
    recordedAt: Instant,
    storedReason: String,
  ): FeatureTaskPhaseSettlementReasonLookup = unavailable()

  override fun delete(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): Boolean = unavailable()

  private fun <T> unavailable(): T =
    throw UnsupportedOperationException("Feature task phase settlements are unavailable in this unit of work.")
}
