package skillbill.infrastructure.contracts.workflow.goal

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.status.WORK_DETAIL_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkFailureCode
import skillbill.goalrunner.model.GoalRunnerSubtaskValidationEvidence
import skillbill.idestatus.model.IdeStatusFreshness
import skillbill.idestatus.model.IdeStatusLifecycleState
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
  fun `invalid JSON at the text boundary retains each contract failure code`() {
    val boardError =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validateBoard("{", "invalid-board-json")
      }
    val detailError =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validateDetail("{", "invalid-detail-json")
      }
    assertEquals(WorkFailureCode.INVALID_WORK_BOARD_SCHEMA, boardError.code)
    assertEquals(WorkFailureCode.INVALID_WORK_DETAIL_SCHEMA, detailError.code)
  }

  @Test
  fun `detail accepts the durable completed subtask validation projection`() {
    val gate =
      FeatureTaskRuntimeValidationGateExecutionEvidence(
        validationStatus = "passed",
        checks = listOf("unit tests"),
        gateRunCount = 1,
        gateRuns =
          listOf(
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
    val snapshot =
      IdeStatusSnapshot(
        repositoryIdentity = "repo-root-realpath-v1:/repo with spaces",
        lifecycleState = IdeStatusLifecycleState.IDLE,
        currentStep = IdeStatusStep("none", "No work"),
        updatedAt = Instant.EPOCH,
        freshness = IdeStatusFreshness.FRESH,
        summary = "No work.",
        title = "SKILL-1",
      )
    val payload =
      JsonCodec.mapToJsonString(
        mapOf(
          "contract_version" to "1.3",
          "page_size" to 20,
          "next_cursor" to null,
          "observed_at" to Instant.EPOCH.toString(),
          "item" to
            mapOf(
              "snapshot" to IdeStatusSchemaValidator().toWirePayload(snapshot).toPayload(),
              "repository_root" to "/repo with spaces",
              "step_pipeline" to emptyList<String>(),
              "completed_subtask_validation" to
                listOf(
                  GoalRunnerSubtaskValidationEvidence(1, gateExecutionEvidence = gate).toStatusWire().toMap(),
                ),
            ),
        ),
      )

    validator.validateDetail(payload, "completed-validation")
  }

  @Test
  fun `detail rejects a goal assessment finding with an unknown verification token`() {
    val finding =
      mapOf(
        "finding_ref" to "GA-002",
        "severity" to "major",
        "path" to "src/Main.kt",
        "line" to 12,
        "failure_scenario" to "The retry never stops.",
        "verification" to "confirmed",
        "state" to "open",
        "evidence" to "Reproduced on the second call.",
      )

    fun detail(finding: Map<String, Any?>) =
      goalAssessmentDetail(
        mapOf(
          "state" to "completed",
          "round" to 3,
          "exit_reason" to "persistent_finding",
          "fix_ran" to true,
          "open_finding_refs" to listOf("GA-002"),
          "findings" to listOf(finding),
        ),
      )

    validator.validateDetail(detail(finding), "valid-assessment")
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validateDetail(detail(finding + ("verification" to "probably")), "invalid-assessment")
      }
    assertEquals(WorkFailureCode.INVALID_WORK_DETAIL_SCHEMA, error.code)
  }

  private fun goalAssessmentDetail(assessment: Map<String, Any?>): String {
    val snapshot =
      IdeStatusSnapshot(
        repositoryIdentity = "repo-root-realpath-v1:/repo",
        lifecycleState = IdeStatusLifecycleState.IDLE,
        currentStep = IdeStatusStep("none", "No work"),
        updatedAt = Instant.EPOCH,
        freshness = IdeStatusFreshness.FRESH,
        summary = "No work.",
        title = "SKILL-1",
      )
    return JsonCodec.mapToJsonString(
      mapOf(
        "contract_version" to WORK_DETAIL_CONTRACT_VERSION,
        "page_size" to 20,
        "next_cursor" to null,
        "observed_at" to Instant.EPOCH.toString(),
        "item" to
          mapOf(
            "snapshot" to IdeStatusSchemaValidator().toWirePayload(snapshot).toPayload(),
            "repository_root" to "/repo",
            "step_pipeline" to emptyList<String>(),
            "goal_assessment" to assessment,
          ),
      ),
    )
  }

  @Test
  fun `malformed board envelope fails with board failure code`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validateBoard(JsonCodec.mapToJsonString(mapOf("contract_version" to "1.0")), "test-board")
      }
    assertEquals(WorkFailureCode.INVALID_WORK_BOARD_SCHEMA, error.code)
  }

  @Test
  fun `detail with missing required item fields fails with detail failure code`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validateDetail(
          JsonCodec.mapToJsonString(
            mapOf(
              "contract_version" to "1.3",
              "page_size" to 20,
              "next_cursor" to null,
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
