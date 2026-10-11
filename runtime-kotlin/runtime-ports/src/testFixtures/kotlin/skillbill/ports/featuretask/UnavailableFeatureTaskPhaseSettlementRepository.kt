package skillbill.ports.featuretask

import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement

object UnavailableFeatureTaskPhaseSettlementRepository : FeatureTaskPhaseSettlementRepository {
  override fun upsert(settlement: FeatureTaskPhaseSettlement) {
    unavailable<Unit>()
  }

  override fun find(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): FeatureTaskPhaseSettlement? = unavailable()

  override fun history(
    workflowId: String,
    phaseId: String,
  ): List<FeatureTaskPhaseSettlement> = unavailable()

  override fun delete(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): Boolean = unavailable()

  private fun <T> unavailable(): T =
    throw UnsupportedOperationException("Feature task phase settlements are unavailable in this unit of work.")
}
