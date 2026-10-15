package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_ADOPTION_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_HANDOFF_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.contracts.locator.AcceptanceCriterionAuditResultSchemaPaths
import skillbill.infrastructure.contracts.locator.AcceptanceCriterionRepairAdoptionSchemaPaths
import skillbill.infrastructure.contracts.locator.AcceptanceCriterionRepairHandoffSchemaPaths
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

class AcceptanceCriterionRepairAuxiliaryContractTest {
  @Test
  fun `all auxiliary artifacts package strict versioned canonical schemas`() {
    listOf(
      Triple(
        AcceptanceCriterionAuditResultSchemaPaths.CLASSPATH_RESOURCE,
        AcceptanceCriterionAuditResultSchemaPaths.EXPECTED_SCHEMA_ID,
        ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION,
      ),
      Triple(
        AcceptanceCriterionRepairHandoffSchemaPaths.CLASSPATH_RESOURCE,
        AcceptanceCriterionRepairHandoffSchemaPaths.EXPECTED_SCHEMA_ID,
        ACCEPTANCE_CRITERION_REPAIR_HANDOFF_CONTRACT_VERSION,
      ),
      Triple(
        AcceptanceCriterionRepairAdoptionSchemaPaths.CLASSPATH_RESOURCE,
        AcceptanceCriterionRepairAdoptionSchemaPaths.EXPECTED_SCHEMA_ID,
        ACCEPTANCE_CRITERION_REPAIR_ADOPTION_CONTRACT_VERSION,
      ),
    ).forEach { (resource, id, version) ->
      val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(resource))
      val schema = stream.use { YAMLMapper().readTree(it) }
      assertEquals(id, schema.path("\$id").asText())
      assertEquals(version, schema.path("properties").path(Keys.CONTRACT_VERSION).path("const").asText())
      assertFalse(schema.path("additionalProperties").asBoolean(true))
    }
  }

  @Test
  fun `targeted audit refuses duplicate identities missing reservations and wrong versions`() {
    val valid = audit()
    AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(valid, "valid")
    listOf(
      valid + (Keys.CONTRACT_VERSION to "9.0"),
      valid - Keys.RESERVATION_ID,
      valid + (Keys.CRITERIA to listOf(criterion(), criterion())),
      valid + (Keys.CURRENT_CRITERION_REF to "AC-002"),
    ).forEach { bad ->
      assertFailsWith<SkillBillRuntimeException> {
        AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(bad, "bad")
      }
    }
  }

  @Test
  fun `handoff refuses invented remaining budget and awaiting verification without reservation`() {
    val valid =
      identity() +
        mapOf(
          Keys.REVISION to 2, Keys.CRITERION_REF to "AC-001",
          Keys
            .STAGE to "repair_completed_awaiting_targeted_verification",
          Keys.RESERVATION_ID to "reservation", Keys.UNSUCCESSFUL_USED to 1, Keys.UNSUCCESSFUL_REMAINING to 2,
          Keys.QUEUE_POSITION to 1, Keys.QUEUE_SIZE to 1, Keys.ACTIONABLE_GAP to "repair missing transaction fence",
          Keys.EVIDENCE_REF to "source:1", Keys.DEPENDENCIES to emptyList<Any>(),
          Keys
            .PREREQUISITE_FACTS to emptyList<Any>(),
        )
    AcceptanceCriterionRepairAuxiliarySchemaValidator.validateRepairHandoff(valid, "valid")
    listOf(
      valid + (Keys.CONTRACT_VERSION to "9.0"),
      valid + (Keys.UNSUCCESSFUL_REMAINING to 3),
      valid + (Keys.RESERVATION_ID to null),
    ).forEach { bad ->
      assertFailsWith<SkillBillRuntimeException> {
        AcceptanceCriterionRepairAuxiliarySchemaValidator
          .validateRepairHandoff(bad, "bad")
      }
    }
  }

  @Test
  fun `adoption refuses altered retained history and unsupported historical revisions`() {
    val descriptor = mapOf(Keys.CONTRACT_VERSION to "0.1")
    val original = mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(descriptor))
    val valid =
      identity() +
        mapOf(
          Keys.COMPOSITION to "standalone", Keys.SEMANTIC_REVISION to 3, Keys.DESCRIPTOR_DIGEST to digest(descriptor),
          Keys.POLICY_DIGEST to "a".repeat(64), Keys.SKELETON_DIGEST to "a".repeat(64),
          Keys.DECLARED_CONTRACT_VERSIONS to mapOf(Keys.CONTRACT_VERSION to "0.1"), Keys.STOPPED_OWNER_GENERATION to 0,
          Keys.STOPPED_OWNER_EVIDENCE_REF to "ownership_absent:proof", Keys.PRISTINE_HISTORY_DIGEST to digest(original),
          Keys.RETAINED_EVIDENCE_REFS to listOf("original:proof"), Keys.PROSPECTIVE_BUDGET to true,
          Keys.STRUCTURED_DISCOVERY_REQUIRED to true, Keys.ORIGINAL_ARTIFACTS to original,
        )
    AcceptanceCriterionRepairAuxiliarySchemaValidator.validateRepairAdoption(valid, "valid")
    listOf(
      valid + (Keys.CONTRACT_VERSION to "9.0"),
      valid + (Keys.SEMANTIC_REVISION to 4),
      valid + (Keys.PRISTINE_HISTORY_DIGEST to "f".repeat(64)),
    ).forEach { bad ->
      assertFailsWith<SkillBillRuntimeException> {
        AcceptanceCriterionRepairAuxiliarySchemaValidator
          .validateRepairAdoption(bad, "bad")
      }
    }
  }

  private fun audit(): Map<String, Any?> =
    identity() +
      mapOf(
        Keys.MODE to "current_criterion_targeted", Keys.CURRENT_CRITERION_REF to "AC-001",
        Keys
          .RESERVATION_ID to "reservation",
        Keys.EXPECTED_REVISION to 2, Keys.PRODUCING_OUTPUT_ID to "output:1",
        Keys.PRODUCING_OUTPUT_DIGEST to
          "b".repeat(
            64,
          ),
        Keys.CRITERIA to listOf(criterion()), Keys.DEPENDENCIES to emptyList<Any>(),
        Keys
          .REGRESSED_REFS to emptyList<Any>(),
        Keys.EDGE_CHANGES to emptyList<Any>(),
      )

  private fun criterion(): Map<String, Any?> =
    mapOf(
      Keys.CANONICAL_REF to "AC-001",
      Keys.SOURCE_DISPOSITION to "source_closed",
      Keys
        .VALIDATION_OBLIGATION to "deferred",
      Keys.VALIDATION_OWNER_AVAILABLE to true,
      Keys.EVIDENCE_REF to "source:1",
    )

  private fun identity(): Map<String, Any?> =
    mapOf(
      Keys.CONTRACT_VERSION to "0.1",
      Keys.CATALOG_ID to "catalog",
      Keys.SPEC_DIGEST to
        "a".repeat(
          64,
        ),
      Keys.EXECUTION_ID to "execution",
      Keys.TREE_DIGEST to "d".repeat(64),
      Keys.CHECKPOINT_ID to "checkpoint",
    )

  private fun digest(value: Any): String =
    MessageDigest.getInstance("SHA-256")
      .digest(JsonCodec.valueToJsonString(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
