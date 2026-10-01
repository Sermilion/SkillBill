package skillbill.workflow.taskruntime.model.validation

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeValidationEvidenceTest {
  @Test
  fun successfulDiscoveryCannotMaskFailedTerminalVerificationWithDifferentArgv() {
    val evidence =
      FeatureTaskRuntimeValidationEvidence(
        listOf(
          FeatureTaskRuntimeValidationCommandResult("./gradlew check", 0),
          FeatureTaskRuntimeValidationCommandResult("./gradlew check --rerun-tasks", 1),
        ),
      )

    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      evidence.requireSuccessfulCommand("./gradlew check", "validate")
    }
    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      evidence.requireSuccessfulResult("validate")
    }
  }

  @Test
  fun `valid evidence preserves command and exit code through wire round trip`() {
    val evidence =
      FeatureTaskRuntimeValidationEvidence(
        listOf(FeatureTaskRuntimeValidationCommandResult("./gradlew check", 0)),
      )

    val restored =
      FeatureTaskRuntimeValidationEvidence.fromArtifactMap(
        evidence.toArtifactMap(),
        "test",
      )

    assertEquals("./gradlew check", restored.results.single().command)
    assertEquals(0, restored.results.single().exitCode)
  }

  @Test
  fun `missing and malformed evidence fail with typed errors`() {
    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      FeatureTaskRuntimeValidationEvidence.fromArtifactMap(emptyMap(), "missing")
    }
    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      FeatureTaskRuntimeValidationEvidence.fromArtifactMap(
        mapOf(
          ValidationEvidencePayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION,
          ValidationEvidencePayloadKeys.RESULTS to
            listOf(
              mapOf(ValidationEvidencePayloadKeys.COMMAND to "./gradlew check"),
            ),
        ),
        "malformed",
      )
    }
    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      FeatureTaskRuntimeValidationEvidence.fromArtifactMap(
        mapOf(
          ValidationEvidencePayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION,
          ValidationEvidencePayloadKeys.RESULTS to
            listOf(
              mapOf(
                ValidationEvidencePayloadKeys.COMMAND to "./gradlew check",
                ValidationEvidencePayloadKeys.EXIT_CODE to 2.7,
              ),
            ),
        ),
        "fractional-exit-code",
      )
    }
  }

  @Test
  fun `non-zero final result cannot satisfy completion`() {
    val evidence =
      FeatureTaskRuntimeValidationEvidence(
        listOf(FeatureTaskRuntimeValidationCommandResult("./gradlew check", 1)),
      )

    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      evidence.requireSuccessfulResult("test")
    }
  }

  @Test
  fun `required command cannot be masked by a later unrelated success`() {
    val evidence =
      FeatureTaskRuntimeValidationEvidence(
        listOf(
          FeatureTaskRuntimeValidationCommandResult("./gradlew check", 1),
          FeatureTaskRuntimeValidationCommandResult("unrelated", 0),
        ),
      )

    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      evidence.requireSuccessfulCommand("./gradlew check", "test")
    }
  }

  @Test
  fun `provider extended result metadata stays admissible`() {
    val restored =
      FeatureTaskRuntimeValidationEvidence.fromArtifactMap(
        mapOf(
          ValidationEvidencePayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION,
          ValidationEvidencePayloadKeys.RESULTS to
            listOf(
              mapOf(
                ValidationEvidencePayloadKeys.COMMAND to "./gradlew check",
                ValidationEvidencePayloadKeys.EXIT_CODE to 0,
                "signal" to mapOf("provider" to listOf("opaque")),
                "provider_metadata" to "opaque",
              ),
            ),
        ),
        "provider-extended",
      )

    assertEquals(0, restored.results.single().exitCode)
    assertEquals("./gradlew check", restored.results.single().command)
  }

  @Test
  fun `multiple command results preserve each identity and exit code`() {
    val evidence =
      FeatureTaskRuntimeValidationEvidence(
        listOf(
          FeatureTaskRuntimeValidationCommandResult("./gradlew check", 1),
          FeatureTaskRuntimeValidationCommandResult("./gradlew check --offline", 0),
        ),
      )

    val restored =
      FeatureTaskRuntimeValidationEvidence.fromArtifactMap(
        evidence.toArtifactMap(),
        "multiple",
      )

    assertEquals(2, restored.results.size)
    assertEquals(1, restored.results.first().exitCode)
    assertEquals(0, restored.results.last().exitCode)
  }

  @Test
  fun `unsupported evidence version is actionable`() {
    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      FeatureTaskRuntimeValidationEvidence.fromArtifactMap(
        mapOf(
          ValidationEvidencePayloadKeys.CONTRACT_VERSION to "9.9",
          ValidationEvidencePayloadKeys.RESULTS to emptyList<Any?>(),
        ),
        "legacy",
      )
    }
  }
}
