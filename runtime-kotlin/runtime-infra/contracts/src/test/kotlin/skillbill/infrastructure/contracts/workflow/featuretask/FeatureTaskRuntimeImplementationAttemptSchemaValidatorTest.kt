package skillbill.infrastructure.contracts.workflow.featuretask

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPT_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimeImplementationAttemptSchemaValidatorTest {
  @Test
  fun `accepts a well-formed attempt history`() {
    FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(record(attempt()), SOURCE)
  }

  @Test
  fun `accepts an absent history as zero attempts`() {
    FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(record(), SOURCE)
  }

  @Test
  fun `accepts optional prompt on a stuffed value segment`() {
    FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(
      record(attempt() + mapOf("prompt" to "optional directive")),
      SOURCE,
    )
  }

  @Test
  fun `rejects an unknown top-level property`() {
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(
        record(attempt()) + mapOf("attempt_count" to 1),
        SOURCE,
      )
    }
  }

  @Test
  fun `rejects a wrong contract version`() {
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(
        record(attempt()) + mapOf("contract_version" to "0.1"),
        SOURCE,
      )
    }
  }

  @Test
  fun `rejects legacy receipt fields on an attempt entry`() {
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(
        record(
          attempt() +
            mapOf(
              "completed_task_ids" to listOf("task-1"),
              "changed_paths" to listOf("src/Foo.kt"),
            ),
        ),
        SOURCE,
      )
    }
  }

  @Test
  fun `rejects an unknown attempt status`() {
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(
        record(attempt(status = "partially_completed")),
        SOURCE,
      )
    }
  }

  @Test
  fun `rejects a blank stuffed value`() {
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(
        record(attempt() + mapOf("value" to "   ")),
        SOURCE,
      )
    }
  }

  @Test
  fun `accepts a stuffed value longer than 4096 characters`() {
    FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(
      record(attempt() + mapOf("value" to "x".repeat(4097))),
      SOURCE,
    )
  }

  @Test
  fun `rejects a missing required attempt field`() {
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(
        record(attempt() - "sequence_number"),
        SOURCE,
      )
    }
  }

  @Test
  fun `failure names the source label so the offending record is identifiable`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(record(attempt(status = "nope")), SOURCE)
      }

    assertTrue(error.message.orEmpty().contains(SOURCE), "Error must name '$SOURCE'; got: ${error.message}")
  }

  private fun record(vararg attempts: Map<String, Any?>): Map<String, Any?> =
    mapOf(
      "contract_version" to FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPT_CONTRACT_VERSION,
      "attempts" to attempts.toList(),
    )

  private fun attempt(status: String = "incomplete"): Map<String, Any?> =
    mapOf(
      "sequence_number" to 1,
      "phase_id" to "implement",
      "attempt_number" to 1,
      "agent_id" to "claude",
      "status" to status,
      "recorded_at" to "2026-08-04T10:00:00Z",
      "value" to """{"projection_kind":"implementation_receipt","completed_task_ids":["task-1"]}""",
    )

  private companion object {
    const val SOURCE = "implement.attempt_history"
  }
}
