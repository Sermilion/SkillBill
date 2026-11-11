package skillbill.engine.goalrunner.execution.core

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.engine.featuretask.phase.core.InMemoryFeatureTaskPhaseSettlementRepository
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork

class GoalRunnerStatusTestDatabase(
  private val base: DatabaseSessionFactory = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
) : DatabaseSessionFactory by base {
  private val settlements = InMemoryFeatureTaskPhaseSettlementRepository()

  override fun <T> read(block: (UnitOfWork) -> T): T =
    base.read { unit ->
      block(
        object : UnitOfWork by unit {
          override val featureTaskPhaseSettlements = settlements
        },
      )
    }
}
