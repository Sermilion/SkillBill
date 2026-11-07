package skillbill.infrastructure.contracts.workflow.goal

import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.workflow.goal.GOAL_QUALITY_EXECUTION_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GoalQualityExecutionPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.goalrunner.model.quality.GoalQualityValidationDisposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalQualityExecutionSchemaContractTest {
  private val keys = GoalQualityExecutionPayloadKeys

  @Test
  fun `retiring terminal validation preserves its source evidence`() {
    val completed = executionPayload(GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT)
    val retired = completed + (keys.STATUS to GoalQualityExecutionStatus.RETIRED.wireValue)

    GoalQualityExecutionSchemaValidator.validate(recordPayload(completed), "completed validation")
    GoalQualityExecutionSchemaValidator.validate(recordPayload(retired), "retired validation")
  }

  @Test
  fun `malformed carried repair receipts fail before decoding`() {
    val validReceipt =
      mapOf(
        ValidationEvidencePayloadKeys.REPAIR_TURN to 1,
        ValidationEvidencePayloadKeys.REPAIR_OUTPUT to "Accepted repair",
      )
    GoalQualityExecutionSchemaValidator.validate(
      recordPayload(executionPayload(GoalQualityExecutionStatus.RETIRED, listOf(validReceipt))),
      "valid repair receipt",
    )

    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        GoalQualityExecutionSchemaValidator.validate(
          recordPayload(executionPayload(GoalQualityExecutionStatus.RETIRED, listOf(emptyMap()))),
          "missing repair identity",
        )
      }
    assertEquals(WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION, failure.code)
  }

  @Test
  fun `disabled QA persists its skip while unknown reasons fail before decoding`() {
    val execution = executionPayload(GoalQualityExecutionStatus.SETTLED_SKIPPED)
    GoalQualityExecutionSchemaValidator.validate(
      recordPayload(execution + (keys.SKIP_REASON to "disabled_policy")),
      "disabled QA",
    )

    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        GoalQualityExecutionSchemaValidator.validate(
          recordPayload(execution + (keys.SKIP_REASON to "unknown_policy")),
          "unknown QA policy",
        )
      }
    assertEquals(WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION, failure.code)
  }

  @Test
  fun `bundled quality schema loads current readiness and rejects an unsupported version`() {
    val payload =
      mapOf(
        GoalQualityExecutionPayloadKeys.CONTRACT_VERSION to GOAL_QUALITY_EXECUTION_CONTRACT_VERSION,
        GoalQualityExecutionPayloadKeys.READINESS to GoalQualityReadiness.CLEAN_UNSTARTED.wireValue,
        GoalQualityExecutionPayloadKeys.EXECUTION to null,
      )

    GoalQualityExecutionSchemaValidator.validate(payload, "current readiness")

    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        GoalQualityExecutionSchemaValidator.validate(
          payload + (GoalQualityExecutionPayloadKeys.CONTRACT_VERSION to "999.0"),
          "unsupported readiness",
        )
      }
    assertEquals(WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION, failure.code)
  }

  @Test
  fun `QA source provenance requires source and accepted repair identity before decoding`() {
    val witness =
      mapOf(
        keys.ADMITTED_QA_PLAN to "qa-plan",
        keys.PHASE_ID to "build",
        keys.ATTEMPT to 1,
        keys.ACCEPTED_OUTPUT_FINGERPRINT to "a".repeat(64),
        keys.NESTED_PROGRESS_KEY to "parent:invocation:goal-qa:build:1:build",
        ValidationEvidencePayloadKeys.REPAIR_TURN to 1,
        ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT to "checkpoint",
        keys.CONTENT_FINGERPRINT to "source",
        keys.PROGRESS_REVISION to 1,
      )
    GoalQualityExecutionSchemaValidator.validate(
      recordPayload(executionPayload(GoalQualityExecutionStatus.RETIRED, witnesses = listOf(witness))),
      "QA source provenance",
    )
    listOf(keys.CONTENT_FINGERPRINT, keys.ACCEPTED_OUTPUT_FINGERPRINT, keys.ADMITTED_QA_PLAN).forEach { missing ->
      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          GoalQualityExecutionSchemaValidator.validate(
            recordPayload(executionPayload(GoalQualityExecutionStatus.RETIRED, witnesses = listOf(witness - missing))),
            "incomplete QA source provenance",
          )
        }
      assertEquals(WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION, failure.code)
    }
  }

  private fun recordPayload(execution: Map<String, Any?>): Map<String, Any?> =
    mapOf(
      keys.CONTRACT_VERSION to GOAL_QUALITY_EXECUTION_CONTRACT_VERSION,
      keys.READINESS to GoalQualityReadiness.ADMITTED.wireValue,
      keys.EXECUTION to execution,
    )

  private fun executionPayload(
    status: GoalQualityExecutionStatus,
    receipts: List<Map<String, Any?>> = emptyList(),
    witnesses: List<Map<String, Any?>> = emptyList(),
  ): Map<String, Any?> {
    val progress =
      mapOf(
        keys.PHASE_RECORDS to emptyMap<String, Any?>(),
        keys.PHASE_LEDGER to emptyList<Any>(),
        keys.ACCEPTED_OUTPUTS to emptyList<Any>(),
        keys.ACCEPTED_ENVELOPES to emptyList<Any>(),
        keys.BUILD_GATE_PROGRESS_KEY to null,
        keys.VALIDATION_GATE_PROGRESS_KEY to null,
        keys.TERMINAL_COMPLETED to true,
        keys.REVISION to 1,
        keys.CARRIED_VALIDATION_REPAIR_OUTPUTS to receipts,
        keys.ACCEPTED_QA_SOURCE_WITNESSES to witnesses,
      )
    return mapOf(
      keys.PARENT_WORKFLOW_ID to "parent",
      keys.INVOCATION_ID to "invocation",
      keys.BASELINE_CONTENT_FINGERPRINT to "baseline",
      keys.GOVERNING_INPUT_FINGERPRINT to "inputs",
      keys.GOVERNING_INPUTS to null,
      keys.EXECUTION_PLANS to
        mapOf(
          keys.BUILD_PLAN to "build",
          keys.QA_PLAN to "qa",
          keys.VALIDATION_PLAN to "validation",
          keys.PLAN_FINGERPRINT to "a".repeat(64),
        ),
      keys.PROGRESS to
        mapOf(
          keys.BUILD_PROGRESS to progress,
          keys.QA_PROGRESS to progress,
          keys.VALIDATION_PROGRESS to progress,
        ),
      keys.PRE_QA_BUILD to "pending",
      keys.PRE_QA_BUILD_CONTENT_FINGERPRINT to null,
      keys.STATUS to status.wireValue,
      keys.VALIDATION to GoalQualityValidationDisposition.COMPLETED.wireValue,
      keys.WARNING to null,
      keys.FINAL_VALIDATION_CONTENT_FINGERPRINT to "validated",
    )
  }
}
