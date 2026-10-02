package skillbill.infrastructure.contracts.workflow.decomposition

import skillbill.error.shellcontent.InvalidDecompositionManifestSchemaError
import skillbill.workflow.decomposition.model.DecompositionManifestValidationFailureCode
import skillbill.workflow.decomposition.model.DecompositionManifestValidationResult
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairOperation
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecompositionManifestValidationRepairTest {
  private val validator = DecompositionManifestSchemaValidator()

  @Test
  fun `valid YAML is accepted unchanged without normalization evidence`() {
    val result = validator.validateYamlTextResult(validManifestJson(), "manifest.yaml")

    val accepted = assertIs<DecompositionManifestValidationResult.AcceptedUnchanged>(result)
    assertEquals(validManifestJson(), accepted.yamlText)
  }

  @Test
  fun `one missing closing delimiter is repaired and fully validated`() {
    val malformed = validManifestJson().dropLast(1)

    val result = validator.validateYamlTextResult(malformed, "manifest.yaml")

    val repaired = assertIs<DecompositionManifestValidationResult.AcceptedAfterRepair>(result)
    assertEquals(sha256(malformed), repaired.evidence.originalDigest)
    assertEquals(sha256(validManifestJson()), repaired.evidence.repairedDigest)
    assertEquals("manifest.yaml", repaired.evidence.sourceLocation.sourceLabel)
    assertEquals(
      FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER.wireValue,
      repaired.evidence.operation.wireValue,
    )
    assertEquals(malformed.length, repaired.evidence.sourceLocation.offset)
  }

  @Test
  fun `duplicate keys are rejected before repair`() {
    val duplicate =
      validManifestJson().replace(
        "\"issue_key\":\"SKILL-153\",",
        "\"issue_key\":\"SKILL-153\",\"issue_key\":\"SKILL-153\",",
      )

    val result = validator.validateYamlTextResult(duplicate, "manifest.yaml")

    val rejected = assertIs<DecompositionManifestValidationResult.Rejected>(result)
    assertEquals(DecompositionManifestValidationFailureCode.DUPLICATE_KEY, rejected.code)
    assertTrue("issue_key\":\"SKILL-153" !in rejected.reason)
  }

  @Test
  fun `malformed YAML whose snippet contains the word duplicate is classified malformed not duplicate`() {
    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        validator.validateYamlTextMap("issue_key: [duplicate\nfeature_name: x\n", "manifest.yaml")
      }

    assertEquals(DecompositionManifestValidationFailureCode.MALFORMED.wireValue, error.failureCode)
  }

  @Test
  fun `repaired syntax that fails the schema remains rejected without raw YAML`() {
    val malformed =
      validManifestJson()
        .replace("\"issue_key\":\"SKILL-153\",", "")
        .dropLast(1)

    val result = validator.validateYamlTextResult(malformed, "manifest.yaml")

    val rejected = assertIs<DecompositionManifestValidationResult.Rejected>(result)
    assertEquals(DecompositionManifestValidationFailureCode.SCHEMA_INVALID, rejected.code)
    assertTrue(malformed !in rejected.reason)
  }

  @Test
  fun `shape and coherence failures stay typed rejections`() {
    val shape =
      validator.validateYamlTextResult(
        validManifestJson()
          .replace("\"subtasks\":[{\"id\"", "\"subtasks\":{\"id\"")
          .replace("\"dependencies\":[]}]}", "\"dependencies\":[]}}"),
        "shape.yaml",
      )
    val coherence =
      validator.validateYamlTextResult(
        validManifestJson().replace(
          "\"subtask_id\":1,\"action\":\"start\"",
          "\"subtask_id\":2,\"action\":\"start\"",
        ),
        "coherence.yaml",
      )

    val shapeRejection = assertIs<DecompositionManifestValidationResult.Rejected>(shape)
    assertEquals(DecompositionManifestValidationFailureCode.SCHEMA_INVALID, shapeRejection.code)
    val coherenceRejection = assertIs<DecompositionManifestValidationResult.Rejected>(coherence)
    assertEquals(DecompositionManifestValidationFailureCode.COHERENCE_INVALID, coherenceRejection.code)
    assertNull(coherenceRejection.sourceLocation)
  }

  private fun validManifestJson(): String =
    "{" +
      "\"contract_version\":\"0.5\",\"issue_key\":\"SKILL-153\",\"feature_name\":\"manifest\"," +
      "\"parent_spec_path\":\".feature-specs/SKILL-153-manifest/spec.md\"," +
      "\"execution_model\":\"same_branch_commit_per_subtask\",\"base_branch\":\"main\"," +
      "\"feature_branch\":\"feat/SKILL-153-manifest\",\"stack_branches\":[]," +
      "\"current_subtask_intent\":{\"subtask_id\":1,\"action\":\"start\"}," +
      "\"subtasks\":[{\"id\":1,\"name\":\"Manifest\"," +
      "\"spec_path\":\".feature-specs/SKILL-153-manifest/spec_subtask_1.md\"," +
      "\"status\":\"pending\",\"branch\":null,\"commit_sha\":null,\"workflow_id\":null," +
      "\"blocked_reason\":null,\"last_resumable_step\":null,\"dependencies\":[]}]" +
      "}"

  private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray(Charsets.UTF_8))
      .joinToString("") { byte -> "%02x".format(byte) }
}
