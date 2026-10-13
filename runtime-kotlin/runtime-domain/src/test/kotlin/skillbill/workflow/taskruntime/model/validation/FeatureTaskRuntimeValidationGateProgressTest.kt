package skillbill.workflow.taskruntime.model.validation

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimeValidationGateProgressTest {
  @Test
  fun `gate runs persisted before evidence fields existed decode as legacy unverified history`() {
    val raw = progressArtifact(listOf(legacyRun("failed"), legacyRun("passed")))

    val progress = FeatureTaskRuntimeValidationGateProgress.fromArtifactMap(raw)

    assertEquals(2, progress.gateRuns.size)
    assertTrue(progress.gateRuns.all { it.legacyUnverified })
    assertEquals(raw, progress.toArtifactMap())
  }

  @Test
  fun `gate runs that recorded executed checks but no command evidence also decode as legacy history`() {
    val withChecks =
      legacyRun("failed") + mapOf(ValidationEvidencePayloadKeys.EXECUTED_CHECKS to listOf("runtime-core|test"))
    val raw = progressArtifact(listOf(withChecks, legacyRun("passed")))

    val progress = FeatureTaskRuntimeValidationGateProgress.fromArtifactMap(raw)

    assertTrue(progress.gateRuns.all { it.legacyUnverified })
    assertEquals(listOf("runtime-core|test"), progress.gateRuns.first().executedChecks)
    assertEquals(raw, progress.toArtifactMap())
  }

  @Test
  fun `legacy unverified gate runs never back passing validation evidence`() {
    val progress =
      FeatureTaskRuntimeValidationGateProgress.fromArtifactMap(progressArtifact(listOf(legacyRun("passed"))))

    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeValidationGateExecutionEvidence.fromGateMeasurements(progress.gateRuns)
    }
  }

  @Test
  fun `gate runs with partial evidence are still rejected`() {
    val partial =
      legacyRun("passed") +
        mapOf(
          ValidationEvidencePayloadKeys.EXECUTED_CHECKS to emptyList<String>(),
          ValidationEvidencePayloadKeys.COMMAND to "./gradlew check",
          ValidationEvidencePayloadKeys.EXIT_CODE to 0,
        )

    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        FeatureTaskRuntimeValidationGateProgress.fromArtifactMap(progressArtifact(listOf(partial)))
      }

    assertTrue(failure.message.orEmpty().contains("must retain command, exit code, and repository checkpoint"))
  }

  @Test
  fun `new gate runs cannot be created as legacy records with evidence`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeValidationGateRunRecord(
        durationMs = 10,
        outcome = ValidationGateRunOutcome.PASSED,
        cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
        executedWorkUnits = 0,
        command = "./gradlew check",
        exitCode = 0,
        repositoryCheckpoint = "checkpoint",
        legacyUnverified = true,
      )
    }
    assertFalse(
      FeatureTaskRuntimeValidationGateRunRecord(
        durationMs = 10,
        outcome = ValidationGateRunOutcome.PASSED,
        cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
        executedWorkUnits = 0,
        command = "./gradlew check",
        exitCode = 0,
        repositoryCheckpoint = "checkpoint",
      ).legacyUnverified,
    )
  }

  private fun legacyRun(outcome: String): Map<String, Any?> =
    linkedMapOf(
      ValidationEvidencePayloadKeys.DURATION_MS to 3391L,
      ValidationEvidencePayloadKeys.OUTCOME to outcome,
      ValidationEvidencePayloadKeys.CACHE_MODE to "cache_eligible",
      ValidationEvidencePayloadKeys.EXECUTED_WORK_UNITS to 6,
    )

  private fun progressArtifact(gateRuns: List<Map<String, Any?>>): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
      ValidationEvidencePayloadKeys.GATE_RUN_COUNT to gateRuns.size,
      ValidationEvidencePayloadKeys.GATE_RUNS to gateRuns,
      "remaining_findings" to emptyList<Map<String, String?>>(),
      "complete_findings" to emptyList<Map<String, String?>>(),
      "repair_window_phase" to "none",
      "repairs_used" to 0,
      "captured_triage_plan" to null,
      ValidationEvidencePayloadKeys.LAST_AGENT_UNFIXED_CRITERIA to emptyList<String>(),
    )
}
