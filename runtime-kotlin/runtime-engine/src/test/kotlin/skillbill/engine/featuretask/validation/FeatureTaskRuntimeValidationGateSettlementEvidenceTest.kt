package skillbill.engine.featuretask.validation

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeValidationEvidenceSchemaValidator
import skillbill.workflow.taskruntime.artifact.decodeValidationGateExecutionEvidenceFromArtifact
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimeValidationGateSettlementEvidenceTest {
  @Test
  fun `gradle compile and test tasks with zero executed work units still record check identities`() {
    val output =
      FeatureTaskRuntimeValidationGateCoordinator.runtimeOwnedValidationOutput(
        phaseId = "validate",
        repositoryCheckpoint = "checkpoint",
        measurements =
          listOf(
            FeatureTaskRuntimeValidationGateRunRecord(
              durationMs = 10,
              outcome = ValidationGateRunOutcome.PASSED,
              cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
              executedWorkUnits = 0,
              executedChecks =
                listOf(
                  "runtime-engine|compileKotlin",
                  "runtime-engine|compileTestKotlin",
                  "runtime-engine|test",
                ),
              command = "./gradlew check --continue",
              exitCode = 0,
              repositoryCheckpoint = "checkpoint",
            ),
          ),
        requiredCommand = "./gradlew check --continue",
      )
    val validationResult = validationResultFrom(output.payload)
    val gateEvidence =
      requireNotNull(
        decodeValidationGateExecutionEvidenceFromArtifact(validationResult, "validate"),
      )
    NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(output.payload, "validate")
    assertEquals(0, gateEvidence.gateRuns.single().executedWorkUnits)
    assertFalse(gateEvidence.zeroWork)
    assertTrue(gateEvidence.evidenceRecorded)
    assertEquals(
      listOf(
        "runtime-engine|compileKotlin",
        "runtime-engine|compileTestKotlin",
        "runtime-engine|test",
      ),
      gateEvidence.checks,
    )
  }

  @Test
  fun `settlement preserves cache-eligible failure and forced-full pass evidence independently`() {
    val output =
      FeatureTaskRuntimeValidationGateCoordinator.runtimeOwnedValidationOutput(
        phaseId = "validate",
        repositoryCheckpoint = "checkpoint",
        measurements =
          listOf(
            gateRun(
              GateRunFixture(
                outcome = ValidationGateRunOutcome.FAILED,
                cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
                execution = GateExecution(3, listOf("runtime-engine|compileKotlin")),
                command = "./gradlew check --continue",
                exitCode = 1,
              ),
            ),
            gateRun(
              GateRunFixture(
                outcome = ValidationGateRunOutcome.PASSED,
                cacheMode = ValidationGateCacheMode.FORCED_FULL,
                execution =
                  GateExecution(
                    3,
                    listOf("runtime-engine|compileKotlin", "runtime-engine|test"),
                  ),
                command = "./gradlew check --continue --rerun-tasks",
                exitCode = 0,
              ),
            ),
          ),
        requiredCommand = "./gradlew check --continue --rerun-tasks",
      )
    val validationResult = validationResultFrom(output.payload)
    val gateEvidence =
      requireNotNull(
        decodeValidationGateExecutionEvidenceFromArtifact(
          validationResult,
          "validate",
        ),
      )
    assertEquals(2, gateEvidence.gateRunCount)
    assertEquals(ValidationGateCacheMode.CACHE_ELIGIBLE, gateEvidence.gateRuns.first().cacheMode)
    assertEquals(ValidationGateRunOutcome.FAILED, gateEvidence.gateRuns.first().outcome)
    assertEquals(ValidationGateCacheMode.FORCED_FULL, gateEvidence.gateRuns.last().cacheMode)
    assertEquals(
      listOf("runtime-engine|compileKotlin", "runtime-engine|test"),
      gateEvidence.checks,
    )
    assertEquals(
      listOf(3, 3),
      gateEvidence.gateRuns.map { it.executedWorkUnits },
    )
    assertEquals(
      listOf(ValidationGateRunOutcome.FAILED, ValidationGateRunOutcome.PASSED),
      gateEvidence.gateRuns.map { it.outcome },
    )
    assertFalse(gateEvidence.zeroWork)
    FeatureTaskRuntimeValidationEvidenceSchemaValidator.validate(
      JsonCodec.anyToStringAnyMap(validationResult[ValidationEvidencePayloadKeys.VALIDATION_EVIDENCE])
        ?: error("validation_evidence missing"),
      "validate",
    )
  }

  private fun gateRun(fixture: GateRunFixture): FeatureTaskRuntimeValidationGateRunRecord =
    FeatureTaskRuntimeValidationGateRunRecord(
      durationMs = 1,
      outcome = fixture.outcome,
      cacheMode = fixture.cacheMode,
      executedWorkUnits = fixture.execution.workUnits,
      executedChecks = fixture.execution.checks,
      command = fixture.command,
      exitCode = fixture.exitCode,
      repositoryCheckpoint = "checkpoint",
    )

  private data class GateRunFixture(
    val outcome: ValidationGateRunOutcome,
    val cacheMode: ValidationGateCacheMode,
    val execution: GateExecution,
    val command: String,
    val exitCode: Int,
  )

  private data class GateExecution(
    val workUnits: Int,
    val checks: List<String>,
  )

  private fun validationResultFrom(payload: String): Map<String, Any?> =
    JsonCodec.anyToStringAnyMap(
      JsonCodec.anyToStringAnyMap(
        JsonCodec.parseObjectOrNull(payload)
          ?.let(JsonCodec::jsonElementToValue)
          ?.let(JsonCodec::anyToStringAnyMap)
          ?.get(SharedPayloadKeys.PRODUCED_OUTPUTS),
      )?.get(ValidationEvidencePayloadKeys.VALIDATION_RESULT),
    ) ?: error("validation_result missing")
}
