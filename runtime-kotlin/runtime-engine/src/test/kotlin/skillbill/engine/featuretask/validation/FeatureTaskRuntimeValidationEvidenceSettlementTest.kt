package skillbill.engine.featuretask.validation

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.engine.featuretask.runloop.state.validationEvidenceFromEnvelope
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.decodeValidationGateExecutionEvidenceFromArtifact
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeValidationEvidenceSettlementTest {
  @Test
  fun `prose passed status cannot satisfy validate settlement when required command exited nonzero`() {
    val envelope =
      topLevelValidateEnvelope(
        validateEnvelope(
          command = "./gradlew check",
          exitCode = 1,
        ),
      )
    val evidence =
      requireNotNull(
        validationEvidenceFromEnvelope(envelope, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
      )
    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      evidence.requireSuccessfulCommand("./gradlew check", FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE)
    }
  }

  @Test
  fun `prose passed status cannot satisfy validate settlement when the required command is absent from evidence`() {
    val envelope =
      topLevelValidateEnvelope(
        validateEnvelope(
          command = "./gradlew compileKotlin",
          exitCode = 0,
        ),
      )
    val evidence =
      requireNotNull(
        validationEvidenceFromEnvelope(envelope, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
      )
    assertFailsWith<InvalidFeatureTaskRuntimeValidationEvidenceSchemaError> {
      evidence.requireSuccessfulCommand("./gradlew check", FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE)
    }
  }

  @Test
  fun `minimally shaped evidence with opaque metadata satisfies required command check`() {
    val envelope =
      topLevelValidateEnvelope(
        validateEnvelope(
          command = "./gradlew check",
          exitCode = 0,
          extraResultFields = mapOf("signal" to "BUILD SUCCESSFUL"),
        ),
      )
    val evidence =
      requireNotNull(
        validationEvidenceFromEnvelope(envelope, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
      )
    assertEquals(0, evidence.requireSuccessfulCommand("./gradlew check", "validate").exitCode)
  }

  @Test
  fun `runtime owned output preserves every gate command and exit code`() {
    val output =
      FeatureTaskRuntimeValidationGateCoordinator.runtimeOwnedValidationOutput(
        phaseId = "validate",
        repositoryCheckpoint = "checkpoint",
        measurements =
          listOf(
            FeatureTaskRuntimeValidationGateRunRecord(
              durationMs = 1,
              outcome = ValidationGateRunOutcome.FAILED,
              cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
              executedWorkUnits = 1,
              executedChecks = listOf("runtime-engine|compileKotlin"),
              command = "./gradlew check",
              exitCode = 1,
              repositoryCheckpoint = "checkpoint-before",
            ),
            FeatureTaskRuntimeValidationGateRunRecord(
              durationMs = 2,
              outcome = ValidationGateRunOutcome.PASSED,
              cacheMode = ValidationGateCacheMode.FORCED_FULL,
              executedWorkUnits = 1,
              executedChecks = listOf("runtime-engine|compileKotlin", "runtime-engine|test"),
              command = "./gradlew check --offline",
              exitCode = 0,
              repositoryCheckpoint = "checkpoint",
            ),
          ),
        requiredCommand = "./gradlew check --offline",
      )
    val envelope =
      requireNotNull(
        JsonCodec.parseObjectOrNull(output.payload)
          ?.let(JsonCodec::jsonElementToValue)
          ?.let(JsonCodec::anyToStringAnyMap),
      )
    val evidence =
      requireNotNull(
        validationEvidenceFromEnvelope(envelope, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
      )

    assertEquals(
      listOf("./gradlew check", "./gradlew check --offline"),
      evidence.results.map { it.command },
    )
    assertEquals(listOf(1, 0), evidence.results.map { it.exitCode })
    assertEquals(FeatureTaskRuntimeVerdict.SATISFIED.wireValue, envelope[SharedPayloadKeys.VERDICT])
    val validationResult =
      JsonCodec.anyToStringAnyMap(
        JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS])
          ?.get(ValidationEvidencePayloadKeys.VALIDATION_RESULT),
      ) ?: error("validation_result missing")
    val gateEvidence =
      requireNotNull(
        decodeValidationGateExecutionEvidenceFromArtifact(validationResult, "validate"),
      )
    assertEquals(
      listOf("runtime-engine|compileKotlin", "runtime-engine|test"),
      gateEvidence.checks,
    )
    assertEquals(2, gateEvidence.gateRunCount)
    assertEquals(ValidationGateCacheMode.CACHE_ELIGIBLE, gateEvidence.gateRuns.first().cacheMode)
    assertEquals(ValidationGateCacheMode.FORCED_FULL, gateEvidence.gateRuns.last().cacheMode)
  }

  private fun topLevelValidateEnvelope(validationResult: Map<String, Any?>): Map<String, Any?> =
    mapOf(
      SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
      SharedPayloadKeys.PHASE_ID to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      SharedPayloadKeys.STATUS to "completed",
      SharedPayloadKeys.SUMMARY to "Validate output.",
      SharedPayloadKeys.PRODUCED_OUTPUTS to
        mapOf(
          ValidationEvidencePayloadKeys.VALIDATION_RESULT to validationResult,
        ),
    )

  private fun validateEnvelope(
    command: String,
    exitCode: Int,
    extraResultFields: Map<String, Any?> = emptyMap(),
  ): Map<String, Any?> =
    mapOf(
      "validation_status" to "passed",
      "checks" to emptyList<Any?>(),
      "repository_checkpoint" to mapOf("fingerprint" to "fixture-checkpoint-1"),
      ValidationEvidencePayloadKeys.VALIDATION_EVIDENCE to
        mapOf(
          ValidationEvidencePayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION,
          ValidationEvidencePayloadKeys.RESULTS to
            listOf(
              buildMap {
                put(ValidationEvidencePayloadKeys.COMMAND, command)
                put(ValidationEvidencePayloadKeys.EXIT_CODE, exitCode)
                putAll(extraResultFields)
              },
            ),
        ),
    )
}
