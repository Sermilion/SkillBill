package skillbill.infrastructure.sqlite

import me.tatarka.inject.annotations.Inject
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementReasonLookup
import java.time.Instant

@Inject
class SqliteFeatureTaskPhaseSettlementRepository(
  private val databaseSessionFactory: DatabaseSessionFactory,
) : FeatureTaskPhaseSettlementRepository {
  override fun upsert(settlement: FeatureTaskPhaseSettlement) {
    databaseSessionFactory.transaction { it.featureTaskPhaseSettlements.upsert(settlement) }
  }

  override fun find(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): FeatureTaskPhaseSettlement? =
    databaseSessionFactory.read { it.featureTaskPhaseSettlements.find(workflowId, phaseId, attempt) }

  override fun history(
    workflowId: String,
    phaseId: String,
  ): List<FeatureTaskPhaseSettlement> =
    databaseSessionFactory.read { it.featureTaskPhaseSettlements.history(workflowId, phaseId) }

  override fun findReasonAtOrBefore(
    workflowId: String,
    phaseId: String,
    startedAt: Instant,
    recordedAt: Instant,
    storedReason: String,
  ): FeatureTaskPhaseSettlementReasonLookup =
    databaseSessionFactory.read {
      it.featureTaskPhaseSettlements.findReasonAtOrBefore(workflowId, phaseId, startedAt, recordedAt, storedReason)
    }

  override fun delete(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): Boolean =
    databaseSessionFactory.transaction { it.featureTaskPhaseSettlements.delete(workflowId, phaseId, attempt) }
}
