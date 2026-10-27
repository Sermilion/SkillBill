package skillbill.engine.featuretask.phase.core

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.engine.diagnostics.block.testBlockIncidentCapture
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.PhaseStrategyRegistry
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.persistence.UnitOfWork
import java.time.Clock

fun testPhaseSettlementService(
  repository: FeatureTaskPhaseSettlementRepository,
  clock: Clock,
  database: DatabaseSessionFactory? = null,
): FeatureTaskPhaseSettlementService {
  val base = FakeDatabaseSessionFactory(InMemoryWorkflowStates())
  val selected =
    database ?: object : DatabaseSessionFactory by base {
      override fun <T> transaction(block: (UnitOfWork) -> T): T =
        base.transaction { unit ->
          block(
            object : UnitOfWork by unit {
              override val featureTaskPhaseSettlements = repository
            },
          )
        }
    }
  return FeatureTaskPhaseSettlementService(
    repository,
    clock,
    selected,
    testBlockIncidentCapture(selected, clock),
    PhaseStrategyRegistry(listOf<PhaseStrategy>(AcceptanceAuditStrategy())),
  )
}
