package skillbill.engine.featuretask.persist.acceptance

import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairStatusRead
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.contracts.FeatureTaskRuntimeWireArtifactValidator
import skillbill.infrastructure.contracts.workflow.WorkflowStateSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.ContractFeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

class AcceptanceCriterionRepairPreContractStatusTest {
  @Test
  fun `status reads unversioned repair state as pre-contract while resume still refuses it`() {
    SerialRepairSqliteFixture().use { fixture ->
      var discovered = fixture.initial
      fixture.owned { worker -> discovered = fixture.discover(worker) }
      val workflowPersistence =
        FeatureTaskRuntimeWorkflowPersistence(
          fixture.database,
          WorkflowStateSchemaValidator(),
          FeatureTaskRuntimeWireArtifactValidator(
            fixture.execution.validator,
            ContractFeatureTaskRuntimePhaseOutputMigration(),
          ),
        )
      assertEquals(
        AcceptanceCriterionRepairStatusRead.Current(discovered),
        workflowPersistence.readAcceptanceCriterionRepairStatus(fixture.workflowId),
      )

      storePreContractShape(fixture, discovered.asRepairWireMap())

      assertEquals(
        AcceptanceCriterionRepairStatusRead.PreContract,
        workflowPersistence.readAcceptanceCriterionRepairStatus(fixture.workflowId),
      )
      fixture.owned { worker ->
        assertFailsWith<SkillBillRuntimeException> {
          fixture.persistence.inspectExisting(worker, fixture.binding.authoredCriteria, fixture.binding.specDigest)
        }
      }
    }
  }

  private fun storePreContractShape(
    fixture: SerialRepairSqliteFixture,
    current: Map<String, Any?>,
  ) {
    val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR
    val legacy = current - Keys.CONTRACT_VERSION + ("clarification_follow_ups" to 0)
    val snapshot = fixture.snapshot()
    assertIs<Map<*, *>>(snapshot.artifacts[family.label()])
    fixture.database.transaction {
      it.workflowStates.save(
        WorkflowFamily.TASK_RUNTIME,
        snapshot.copy(artifacts = DurableWorkflowArtifacts.fromMap(snapshot.artifacts + (family.label() to legacy))),
      )
    }
  }
}
