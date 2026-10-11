package skillbill.engine.featuretask.phase.core

import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import java.util.concurrent.ConcurrentHashMap

class InMemoryFeatureTaskPhaseSettlementRepository : FeatureTaskPhaseSettlementRepository {
  private val rows = ConcurrentHashMap<String, FeatureTaskPhaseSettlement>()

  override fun upsert(settlement: FeatureTaskPhaseSettlement) {
    rows[key(settlement.workflowId, settlement.phaseId, settlement.attempt)] = settlement
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
