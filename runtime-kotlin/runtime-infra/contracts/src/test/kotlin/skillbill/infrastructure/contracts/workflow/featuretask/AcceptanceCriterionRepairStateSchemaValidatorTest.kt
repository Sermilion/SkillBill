package skillbill.infrastructure.contracts.workflow.featuretask

import skillbill.error.core.SkillBillRuntimeException
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

class AcceptanceCriterionRepairStateSchemaValidatorTest {
  @Test
  fun `valid admitted state round trips while version counter and identity corruption fail`() {
    val state =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            listOf("first"),
            listOf(
              AcceptanceCriterionRepairCriterion(
                "AC-001",
                null,
                "first",
                1,
                AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
                AcceptanceCriterionValidationObligation.DEFERRED,
                true,
                "audit:1",
              ),
            ),
            emptyList(),
          ),
        ),
      )
    val valid = state.asRepairWireMap()
    AcceptanceCriterionRepairStateSchemaValidator.validate(valid, "valid")
    listOf(
      valid + (Keys.CONTRACT_VERSION to "0.2"),
      valid + (Keys.UNSUCCESSFUL_TOTALS to mapOf("AC-001" to 1)),
      valid + (Keys.CURRENT_CRITERION_REF to "AC-002"),
      valid + (Keys.CRITERIA to emptyList<Any>()),
      valid - Keys.STAGE,
      valid + ("legacy_unknown" to true),
    ).forEach { corrupt ->
      assertFailsWith<SkillBillRuntimeException> {
        AcceptanceCriterionRepairStateSchemaValidator
          .validate(corrupt, "corrupt")
      }
    }
  }
}
