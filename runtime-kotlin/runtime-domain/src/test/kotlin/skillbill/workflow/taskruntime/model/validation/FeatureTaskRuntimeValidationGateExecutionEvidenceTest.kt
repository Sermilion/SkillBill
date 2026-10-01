package skillbill.workflow.taskruntime.model.validation

import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimeValidationGateExecutionEvidenceTest {
  @Test
  fun `malformed gate execution evidence is rejected`() {
    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      FeatureTaskRuntimeValidationGateExecutionEvidence.fromArtifactMap(
        mapOf(ValidationEvidencePayloadKeys.VALIDATION_STATUS to "passed"),
        "validate",
      )
    }
    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      FeatureTaskRuntimeValidationGateExecutionEvidence.fromArtifactMap(
        evidenceArtifact(
          checks = emptyList(),
          gateRuns = listOf(gateRun().toArtifactMap()),
        ).toMutableMap().apply {
          put(ValidationEvidencePayloadKeys.GATE_RUN_COUNT, 1.7)
        },
        "fractional-gate-count",
      )
    }
  }

  @Test
  fun `populated multiple-check and zero-work evidence round-trip`() {
    val populated =
      evidenceArtifact(
        checks = listOf("runtime-engine|compileKotlin", "runtime-engine|test"),
        gateRuns =
          listOf(
            gateRun(
              cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
              outcome = ValidationGateRunOutcome.FAILED,
              executedWorkUnits = 2,
              executedChecks = listOf("runtime-engine|compileKotlin", "runtime-engine|test"),
            ),
            gateRun(
              cacheMode = ValidationGateCacheMode.FORCED_FULL,
              outcome = ValidationGateRunOutcome.PASSED,
              executedWorkUnits = 2,
              executedChecks = listOf("runtime-engine|compileKotlin", "runtime-engine|test"),
            ),
          ).map { it.toArtifactMap() },
      )
    val decoded = FeatureTaskRuntimeValidationGateExecutionEvidence.fromArtifactMap(populated, "validate")
    assertEquals(listOf("runtime-engine|compileKotlin", "runtime-engine|test"), decoded.checks)
    assertEquals(2, decoded.gateRunCount)
    assertEquals(ValidationGateCacheMode.FORCED_FULL, decoded.gateRuns.last().cacheMode)

    val zeroWork =
      evidenceArtifact(
        checks = emptyList(),
        gateRuns =
          listOf(
            gateRun(
              executedWorkUnits = 0,
              executedChecks = emptyList(),
            ).toArtifactMap(),
          ),
      )
    val zeroDecoded = FeatureTaskRuntimeValidationGateExecutionEvidence.fromArtifactMap(zeroWork, "validate")
    assertTrue(zeroDecoded.zeroWork)
    assertTrue(zeroDecoded.evidenceRecorded)
    assertEquals(emptyList(), zeroDecoded.checks)
  }

  @Test
  fun `passed evidence without complete command execution facts is rejected`() {
    val legacy =
      evidenceArtifact(
        checks = emptyList(),
        gateRuns =
          listOf(
            linkedMapOf(
              ValidationEvidencePayloadKeys.DURATION_MS to 1L,
              ValidationEvidencePayloadKeys.OUTCOME to ValidationGateRunOutcome.PASSED.wireValue,
              ValidationEvidencePayloadKeys.CACHE_MODE to ValidationGateCacheMode.CACHE_ELIGIBLE.wireValue,
              ValidationEvidencePayloadKeys.EXECUTED_WORK_UNITS to 1,
            ),
          ),
      )
    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      FeatureTaskRuntimeValidationGateExecutionEvidence.fromArtifactMap(legacy, "validate")
    }
  }

  @Test
  fun `inconsistent aggregate checks are rejected instead of becoming zero-work evidence`() {
    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      FeatureTaskRuntimeValidationGateExecutionEvidence.fromArtifactMap(
        evidenceArtifact(
          checks = emptyList(),
          gateRuns =
            listOf(
              gateRun(
                executedWorkUnits = 1,
                executedChecks = listOf("runtime-engine|test"),
              ).toArtifactMap(),
            ),
        ),
        "validate",
      )
    }
  }

  @Test
  fun `zero gate runs and failed terminal verification cannot prove success`() {
    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      FeatureTaskRuntimeValidationGateExecutionEvidence.fromArtifactMap(
        evidenceArtifact(checks = emptyList(), gateRuns = emptyList()),
        "empty-gate-success",
      )
    }
    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      FeatureTaskRuntimeValidationGateExecutionEvidence.fromArtifactMap(
        evidenceArtifact(
          checks = listOf("runtime-engine|compileKotlin"),
          gateRuns =
            listOf(
              gateRun(outcome = ValidationGateRunOutcome.PASSED).toArtifactMap(),
              gateRun(
                cacheMode = ValidationGateCacheMode.FORCED_FULL,
                outcome = ValidationGateRunOutcome.FAILED,
              ).toArtifactMap(),
            ),
        ),
        "failed-terminal-verification",
      )
    }
  }

  @Test
  fun `negative duration and work counts are rejected before artifact encoding`() {
    assertFailsWith<IllegalArgumentException> {
      gateRun(durationMs = -1)
    }
    assertFailsWith<IllegalArgumentException> {
      gateRun(executedWorkUnits = -1)
    }
  }

  @Test
  fun `missing repository checkpoint is rejected at the artifact boundary`() {
    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      FeatureTaskRuntimeValidationGateExecutionEvidence.fromArtifactMap(
        evidenceArtifact(
          checks = emptyList(),
          gateRuns = emptyList(),
        ).toMutableMap().apply {
          remove(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT)
        },
        "validate",
      )
    }
  }

  @Test
  fun eachMissingExecutionFactAndUnknownOutcomeFailsAtItsTypedBoundary() {
    val original = gateRun().toArtifactMap()
    val keys =
      listOf(
        ValidationEvidencePayloadKeys.COMMAND,
        ValidationEvidencePayloadKeys.EXIT_CODE,
        ValidationEvidencePayloadKeys.EXECUTED_CHECKS,
        ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT,
        ValidationEvidencePayloadKeys.OUTCOME,
        ValidationEvidencePayloadKeys.CACHE_MODE,
      )
    val invalidRuns =
      keys.map { key -> original - key } +
        listOf(
          original + (ValidationEvidencePayloadKeys.OUTCOME to "unknown"),
          original + (ValidationEvidencePayloadKeys.EXIT_CODE to 1),
        )
    invalidRuns.forEach { run ->
      assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
        FeatureTaskRuntimeValidationGateExecutionEvidence.fromArtifactMap(
          evidenceArtifact(listOf("runtime-engine|compileKotlin"), listOf(run)),
          "validate",
        )
      }
    }
    val valid = evidenceArtifact(listOf("runtime-engine|compileKotlin"), listOf(original))
    listOf(
      valid + (ValidationEvidencePayloadKeys.GATE_RUN_COUNT to 2),
      valid + (
        ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT to
          mapOf(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT to "unrelated")
      ),
    ).forEach { invalid ->
      assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
        FeatureTaskRuntimeValidationGateExecutionEvidence.fromArtifactMap(invalid, "validate")
      }
    }
  }

  private fun evidenceArtifact(
    checks: List<String>,
    gateRuns: List<Any>,
  ): Map<String, Any?> =
    linkedMapOf(
      ValidationEvidencePayloadKeys.VALIDATION_STATUS to "passed",
      ValidationEvidencePayloadKeys.CHECKS to checks,
      ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT to
        mapOf(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT to "checkpoint"),
      ValidationEvidencePayloadKeys.GATE_RUN_COUNT to gateRuns.size,
      ValidationEvidencePayloadKeys.GATE_RUNS to gateRuns,
    )

  private fun gateRun(
    cacheMode: ValidationGateCacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
    outcome: ValidationGateRunOutcome = ValidationGateRunOutcome.PASSED,
    durationMs: Long = 1,
    executedWorkUnits: Int = 1,
    executedChecks: List<String> = listOf("runtime-engine|compileKotlin"),
  ): FeatureTaskRuntimeValidationGateRunRecord =
    FeatureTaskRuntimeValidationGateRunRecord(
      durationMs = durationMs,
      outcome = outcome,
      cacheMode = cacheMode,
      executedWorkUnits = executedWorkUnits,
      executedChecks = executedChecks,
      command = "./gradlew check",
      exitCode = if (outcome == ValidationGateRunOutcome.PASSED) 0 else 1,
      repositoryCheckpoint = "checkpoint",
    )
}
