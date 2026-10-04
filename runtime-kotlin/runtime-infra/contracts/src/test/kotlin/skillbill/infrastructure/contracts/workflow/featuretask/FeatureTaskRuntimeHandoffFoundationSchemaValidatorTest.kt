package skillbill.infrastructure.contracts.workflow.featuretask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.infrastructure.contracts.FeatureTaskRuntimeWireArtifactValidator
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

class FeatureTaskRuntimeHandoffFoundationSchemaValidatorTest {
  @Test
  fun `persistence violations return a reason without repeating the diagnostic prefix`() {
    val validator = FeatureTaskRuntimeWireArtifactValidator()
    val kind = FeatureTaskRuntimeWireArtifactKind.HANDOFF_PERSISTENCE_RECORD
    val payload = FeatureTaskRuntimeWorkflowArtifactMap.from(emptyMap<String, Any?>())
    val reason = assertNotNull(validator.violation(kind, payload, "stored-delivery"))
    val error = assertFailsWith<SkillBillRuntimeException> { validator.validate(kind, payload, "stored-delivery") }
    assertEquals(FeatureTaskRuntimeFailureCode.INVALID_PERSISTENCE_SCHEMA, error.code)
    assertEquals(
      "Feature-task-runtime persistence record 'stored-delivery' fails schema validation: $reason",
      error.message,
    )
  }

  @Test
  fun `phase handoff validator rejects the legacy flat source shape`() {
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimePhaseHandoffSchemaValidator.validate(
        mapOf("contract_version" to "0.2", "source_ref" to "upstream_phase_output:plan"),
        "implement.plan_receipt",
      )
    }
  }

  @Test
  fun `persistence validator rejects consumer delivery count posing as producer iteration`() {
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimePersistenceSchemaValidator.validate(
        mapOf(
          "contract_version" to "0.2",
          "record_kind" to "delivered_projection",
          "workflow_id" to "wftr-1",
          "consumer_phase_id" to "audit",
          "producer_iteration" to 4,
          "repository_checkpoint" to mapOf("fingerprint" to "checkpoint"),
          "handoff_envelope" to emptyMap<String, Any?>(),
        ),
        "audit.delivery",
      )
    }
  }
}
