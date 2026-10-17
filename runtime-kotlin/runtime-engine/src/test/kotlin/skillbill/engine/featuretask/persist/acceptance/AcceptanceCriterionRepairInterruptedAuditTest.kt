package skillbill.engine.featuretask.persist.acceptance

import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairHydration
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import kotlin.test.Test
import kotlin.test.assertEquals

class AcceptanceCriterionRepairInterruptedAuditTest {
  @Test
  fun `an audit stopped before its first attempt settled starts fresh discovery on resume`() {
    assertEquals(
      AcceptanceCriterionRepairHydration.FreshDiscoveryRequired,
      hydrationAfterAuditLedger(FeatureTaskRuntimePhaseLedgerAction.START),
    )
  }

  @Test
  fun `a settled audit attempt without attribution still requires legacy adoption`() {
    assertEquals(
      AcceptanceCriterionRepairHydration.LegacyAdoptionRequired,
      hydrationAfterAuditLedger(
        FeatureTaskRuntimePhaseLedgerAction.START,
        FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
      ),
    )
  }

  private fun hydrationAfterAuditLedger(
    vararg actions: FeatureTaskRuntimePhaseLedgerAction,
  ): AcceptanceCriterionRepairHydration =
    SerialRepairSqliteFixture().use { f ->
      val seeded = f.snapshot()
      val ledger =
        actions.mapIndexed { index, action ->
          mapOf(
            "action" to action.wireValue,
            "sequence_number" to index,
            "timestamp" to "2026-10-16T17:37:22Z",
            "phase_id" to "audit",
            "attempt_count" to 1,
          )
        }
      f.database.transaction {
        it.workflowStates.save(
          WorkflowFamily.TASK_RUNTIME,
          seeded.copy(
            artifacts =
              DurableWorkflowArtifacts.fromMap(
                seeded.artifacts + DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.entry(ledger),
              ),
          ),
        )
      }
      var hydration: AcceptanceCriterionRepairHydration? = null
      f.owned { worker ->
        hydration = f.persistence.inspectExisting(worker, f.binding.authoredCriteria, f.binding.specDigest)
      }
      requireNotNull(hydration)
    }
}
