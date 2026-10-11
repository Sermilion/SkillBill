package skillbill.infrastructure.contracts.workflow.goal

import skillbill.contracts.JsonCodec
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkFailureCode
import skillbill.goalrunner.model.GoalRunnerSubtaskValidationEvidence
import skillbill.ports.idestatus.model.IdeStatusFreshness
import skillbill.ports.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateExecutionEvidence
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkPayloadSchemaValidatorTest {
  private val validator = WorkPayloadSchemaValidator()

  @Test
  fun `detail accepts the durable completed subtask validation projection`() {
    val gate = FeatureTaskRuntimeValidationGateExecutionEvidence(
      validationStatus = "passed",
      checks = listOf("unit tests"),
      gateRunCount = 1,
      gateRuns = listOf(
        FeatureTaskRuntimeValidationGateRunRecord(
          durationMs = 1,
          outcome = ValidationGateRunOutcome.PASSED,
          cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
          executedWorkUnits = 1,
          executedChecks = listOf("unit tests"),
          command = "./gradlew test",
          exitCode = 0,
          repositoryCheckpoint = "checkpoint",
        ),
      ),
    )
    val snapshot = IdeStatusSnapshot(
      repositoryIdentity = "repo-root-realpath-v1:/repo with spaces",
      lifecycleState = IdeStatusLifecycleState.IDLE,
      currentStep = IdeStatusStep("none", "No work"),
      updatedAt = Instant.EPOCH,
      freshness = IdeStatusFreshness.FRESH,
      summary = "No work.",
    )
    val payload = JsonCodec.mapToJsonObject(
      mapOf(
        "contract_version" to "1.0",
        "observed_at" to Instant.EPOCH.toString(),
        "item" to mapOf(
          "snapshot" to IdeStatusSchemaValidator().toWirePayload(snapshot).toPayload(),
          "repository_root" to "/repo with spaces",
          "step_pipeline" to emptyList<String>(),
          "completed_subtask_validation" to listOf(
            GoalRunnerSubtaskValidationEvidence(1, gateExecutionEvidence = gate).toStatusWire().toMap(),
          ),
        ),
      ),
    )

    validator.validateDetail(payload, "completed-validation")
  }

  @Test
  fun `malformed board envelope fails with board failure code`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validateBoard(JsonCodec.mapToJsonObject(mapOf("contract_version" to "1.0")), "test-board")
      }
    assertEquals(WorkFailureCode.INVALID_WORK_BOARD_SCHEMA, error.code)
  }

  @Test
  fun `detail with missing required item fields fails with detail failure code`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validateDetail(
          JsonCodec.mapToJsonObject(
            mapOf(
              "contract_version" to "1.0",
              "observed_at" to Instant.EPOCH.toString(),
              "item" to mapOf("snapshot" to emptyMap<String, Any?>()),
            ),
          ),
          "test-detail",
        )
      }
    assertEquals(WorkFailureCode.INVALID_WORK_DETAIL_SCHEMA, error.code)
  }
}
