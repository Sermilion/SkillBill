package skillbill.infrastructure.contracts.workflow.featuretask

import skillbill.error.core.SkillBillRuntimeException
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.audit.recordProseAudit
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

class AcceptanceCriterionRepairStateSchemaValidatorTest {
  @Test
  fun `focused audit retains prose evidence longer than 512 characters through validation and reload`() {
    val prose = "Source inspection confirms the criterion against its production consumers. ".repeat(32)
    val initial =
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
    val audit =
      AcceptanceCriterionProseAudit(
        "audit:discovery",
        initial.revision + 1,
        null,
        false,
        "b".repeat(64),
        "Source inspection requires a focused audit.",
        "c".repeat(64),
        "checkpoint:discovery",
      )
    val focused = assertNotNull(initial.recordProseAudit(audit))
    val closed =
      assertNotNull(
        focused.recordProseAudit(
          audit.copy(
            id = "audit:focused",
            revision = focused.revision + 1,
            criterionRef = "AC-001",
            satisfied = true,
            digest = "d".repeat(64),
            prose = prose,
          ),
        ),
      )
    val payload = closed.asRepairWireMap()

    AcceptanceCriterionRepairStateSchemaValidator.validate(payload, "long-focused-audit")

    val reloaded =
      FeatureTaskRuntimeWorkflowArtifactMap.from(payload)
        .toAcceptanceCriterionRepairState("long-focused-audit")
    assertEquals(closed, reloaded)
    assertEquals(prose, reloaded.criteria.single().evidenceRef)
    assertEquals(prose, reloaded.criteria.single().closureEvidenceRef)
    assertEquals(prose, reloaded.sourceTransitions.single().evidenceRef)
  }

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
