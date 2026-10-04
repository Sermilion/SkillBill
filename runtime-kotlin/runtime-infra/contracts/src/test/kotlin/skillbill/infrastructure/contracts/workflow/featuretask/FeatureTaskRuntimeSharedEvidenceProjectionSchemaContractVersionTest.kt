package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeSharedEvidenceProjectionSchemaPaths

class FeatureTaskRuntimeSharedEvidenceProjectionSchemaContractVersionTest {
  @Test
  fun `schema contractVersion const matches FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION`() {
    val contractVersionNode = classpathSchema().path("properties").path("contract_version").path("const")

    assertTrue(
      !contractVersionNode.isMissingNode && contractVersionNode.isTextual,
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(
      FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION,
      contractVersionNode.asText(),
      "Schema properties.contract_version.const must equal " +
        "FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION.",
    )
  }

  @Test
  fun `schema id matches FeatureTaskRuntimeSharedEvidenceProjectionSchemaPaths EXPECTED_SCHEMA_ID`() {
    val idNode = classpathSchema().path("\$id")

    assertTrue(!idNode.isMissingNode && idNode.isTextual, "Schema must declare a textual `\$id`.")
    assertEquals(
      FeatureTaskRuntimeSharedEvidenceProjectionSchemaPaths.EXPECTED_SCHEMA_ID,
      idNode.asText(),
      "Schema `\$id` must equal FeatureTaskRuntimeSharedEvidenceProjectionSchemaPaths.EXPECTED_SCHEMA_ID.",
    )
  }

  @Test
  fun `schema resolves from the classpath resource path`() {
    assertNotNull(
      javaClass.classLoader.getResourceAsStream(
        FeatureTaskRuntimeSharedEvidenceProjectionSchemaPaths.CLASSPATH_RESOURCE,
      ),
      "Canonical shared-evidence projection schema is missing from the classpath at " +
        "'${FeatureTaskRuntimeSharedEvidenceProjectionSchemaPaths.CLASSPATH_RESOURCE}'.",
    )
  }

  private fun classpathSchema(): JsonNode {
    val resourceStream =
      FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator::class.java.classLoader
        .getResourceAsStream(FeatureTaskRuntimeSharedEvidenceProjectionSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical feature-task-runtime shared-evidence projection schema is missing from the classpath at " +
        "'${FeatureTaskRuntimeSharedEvidenceProjectionSchemaPaths.CLASSPATH_RESOURCE}'. " +
        "Ensure `copyFeatureTaskRuntimeSharedEvidenceProjectionSchema` ran before this test.",
    )
    return YAMLMapper().readTree(resourceStream.use { it.readBytes().toString(Charsets.UTF_8) })
  }
}

class FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidatorTest {
  @Test
  fun `a representative shared-evidence projection payload validates`() {
    FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator.validate(
      payload = representativeProjection(),
      sourceLabel = "shared-evidence#1",
    )
  }

  @Test
  fun `an unknown top-level property fails validation`() {
    val payload = representativeProjection() + ("diff_content" to "+++ a/file")
    val reason =
      assertNotNull(
        FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator.violation(payload, "shared-evidence#unknown"),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator.validate(
          payload = payload,
          sourceLabel = "shared-evidence#unknown",
        )
      }
    assertTrue(error.message.orEmpty().contains("diff_content"), error.message.orEmpty())
    assertEquals(
      "Feature-task-runtime shared evidence projection 'shared-evidence#unknown' fails schema validation: $reason",
      error.message,
    )
  }

  @Test
  fun `a missing checkpoint fingerprint fails validation`() {
    val payload =
      representativeProjection().toMutableMap().apply {
        remove("repository_checkpoint_fingerprint")
      }
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator.validate(
        payload = payload,
        sourceLabel = "shared-evidence#missing-fp",
      )
    }
  }

  @Test
  fun `a wrong contract_version fails validation`() {
    val payload =
      representativeProjection().toMutableMap().apply {
        put("contract_version", "9.9")
      }
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator.validate(
        payload = payload,
        sourceLabel = "shared-evidence#bad-version",
      )
    }
  }

  private fun representativeProjection(): Map<String, Any?> =
    linkedMapOf(
      "contract_version" to FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION,
      "workflow_id" to "wftr-1",
      "repository_checkpoint_fingerprint" to "fp-abc",
      "store_path" to ".skill-bill/run-evidence/wftr-1/fp-abc",
      "base_ref" to "main",
      "head_ref" to "HEAD",
      "changed_file_count" to 1,
      "changed_hunk_count" to 1,
      "file_hunk_index_digest" to "a".repeat(64),
    )
}
