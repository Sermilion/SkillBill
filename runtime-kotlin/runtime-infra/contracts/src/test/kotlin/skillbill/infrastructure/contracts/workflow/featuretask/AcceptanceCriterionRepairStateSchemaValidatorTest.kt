package skillbill.infrastructure.contracts.workflow.featuretask

import kotlin.test.Test
import kotlin.test.assertFailsWith
import skillbill.error.core.SkillBillRuntimeException

class AcceptanceCriterionRepairStateSchemaValidatorTest {
  @Test
  fun `wrong version and unknown field are rejected for a valid repair state`() {
    assertFailsWith<SkillBillRuntimeException> {
      AcceptanceCriterionRepairStateSchemaValidator.validate(validPayload() + ("contract_version" to "0.2"), "test")
    }
    assertFailsWith<SkillBillRuntimeException> {
      AcceptanceCriterionRepairStateSchemaValidator.validate(validPayload() + ("legacy_unknown" to true), "test")
    }
  }

  private fun validPayload() =
    mapOf(
        "contract_version" to "0.1",
        "catalog_id" to "catalog",
        "execution_id" to "execution",
        "spec_digest" to "a".repeat(64),
        "criteria" to
          listOf(
            mapOf(
              "canonical_ref" to "AC-001",
              "original_alias" to null,
              "original_text" to "first",
              "ordinal" to 1,
              "source_disposition" to "source_unresolved",
              "validation_obligation" to "required",
              "validation_owner_available" to true,
              "evidence_ref" to "audit:AC-001",
            ),
          ),
        "dependencies" to emptyList<Any>(),
        "unresolved_queue" to listOf("AC-001"),
        "current_criterion_ref" to "AC-001",
        "reservation" to null,
        "outcomes" to emptyList<Any>(),
        "unsuccessful_totals" to emptyMap<String, Int>(),
        "final_audit_required" to false,
        "final_audit_digest" to null,
        "revision" to 0,
      )
}
