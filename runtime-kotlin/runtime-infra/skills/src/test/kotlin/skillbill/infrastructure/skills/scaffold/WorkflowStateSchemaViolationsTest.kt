package skillbill.infrastructure.skills.scaffold

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.infrastructure.contracts.workflow.WorkflowStateSchemaValidator
import skillbill.infrastructure.contracts.workflow.extractOffendingValueFromInstance
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkflowStateSchemaViolationsTest {
  private val validator = WorkflowStateSchemaValidator()

  @Test
  fun `unknown step status enum value loud-fails`() {
    val snapshot =
      baseTaskRuntimeSnapshot().toMutableMap().apply {
        put(
          "steps",
          listOf(
            linkedMapOf<String, Any?>(
              "step_id" to "assess",
              "status" to "frobnicated",
              "attempt_count" to 1,
            ),
          ),
        )
      }
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(snapshot, "bill-feature-task")
      }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
    val message = error.message.orEmpty()

    assertContains(message, "frobnicated")
  }

  @Test
  fun `missing required field loud-fails`() {
    val snapshot =
      baseTaskRuntimeSnapshot().toMutableMap().apply {
        remove("current_step_id")
      }
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(snapshot, "bill-feature-task")
      }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }

    assertContains(error.message.orEmpty(), "current_step_id")
  }

  @Test
  fun `additional unknown top-level property loud-fails with the offending key`() {
    val snapshot =
      baseTaskRuntimeSnapshot().toMutableMap().apply {
        put("extra_field", "x")
      }
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(snapshot, "bill-feature-task")
      }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
    val message = error.message.orEmpty()
    assertContains(message, "extra_field")
  }

  @Test
  fun `wrong contract_version loud-fails with contract_version path`() {
    val snapshot =
      baseTaskRuntimeSnapshot().toMutableMap().apply {
        put("contract_version", "999")
      }
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(snapshot, "bill-feature-task")
      }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
    assertContains(error.message.orEmpty(), "contract_version")
  }

  @Test
  fun `extractOffendingValueFromInstance reads array index from JSON-Pointer-format instanceLocation`() {
    val snapshot =
      baseTaskRuntimeSnapshot().toMutableMap().apply {
        put(
          "steps",
          listOf(
            linkedMapOf<String, Any?>(
              "step_id" to "assess",
              "status" to "frobnicated",
              "attempt_count" to 1,
            ),
          ),
        )
      }
    val instance = ObjectMapper().valueToTree<JsonNode>(snapshot)
    val offendingValue = extractOffendingValueFromInstance(instance, "/steps/0/status")
    assertEquals("frobnicated", offendingValue)
  }

  @Test
  fun `per-skill workflow_status enum mismatch loud-fails`() {
    val snapshot =
      baseVerifySnapshot().toMutableMap().apply {
        put("workflow_status", "blocked")
      }
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(snapshot, "bill-feature-verify")
      }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
    val message = error.message.orEmpty()

    assertContains(message, "workflow_status")
  }

  @Test
  fun `unknown top-level property on a feature-task-runtime snapshot loud-fails with the offending key`() {
    val snapshot =
      baseTaskRuntimeSnapshot().toMutableMap().apply {
        put("rogue_field", "x")
      }
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(snapshot, "bill-feature-task")
      }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
    assertContains(error.message.orEmpty(), "rogue_field")
  }

  @Test
  fun `unknown step field on a feature-task-runtime snapshot loud-fails`() {
    val snapshot =
      baseTaskRuntimeSnapshot().toMutableMap().apply {
        put(
          "steps",
          listOf(
            linkedMapOf<String, Any?>(
              "step_id" to "plan",
              "status" to "running",
              "attempt_count" to 1,
              "rogue_step_field" to "x",
            ),
          ),
        )
      }
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(snapshot, "bill-feature-task")
      }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
    assertContains(error.message.orEmpty(), "rogue_step_field")
  }

  @Test
  fun `paused validates on the runtime branch and loud-fails on the verify branch`() {
    validator.validate(
      baseTaskRuntimeSnapshot().toMutableMap().apply { put("workflow_status", "paused") },
      "bill-feature-task",
    )

    val verifyError =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(
          baseVerifySnapshot().toMutableMap().apply { put("workflow_status", "paused") },
          "bill-feature-verify",
        )
      }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
    assertContains(verifyError.message.orEmpty(), "workflow_status")
  }

  @Test
  fun `plan_fix step id loud-fails on feature-task-runtime snapshots`() {
    val currentStep =
      baseTaskRuntimeSnapshot().toMutableMap().apply {
        put("current_step_id", "plan_fix")
      }
    val currentStepError =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(currentStep, "bill-feature-task")
      }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
    assertContains(currentStepError.message.orEmpty(), "plan_fix")

    val stepsSnapshot =
      baseTaskRuntimeSnapshot().toMutableMap().apply {
        put(
          "steps",
          listOf(
            linkedMapOf<String, Any?>(
              "step_id" to "plan_fix",
              "status" to "pending",
              "attempt_count" to 0,
            ),
          ),
        )
      }
    val stepsError =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(stepsSnapshot, "bill-feature-task")
      }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
    assertContains(stepsError.message.orEmpty(), "plan_fix")
  }

  private fun baseTaskRuntimeSnapshot(): Map<String, Any?> =
    linkedMapOf(
      "workflow_id" to "wftr-19700101-000000-aaaa",
      "session_id" to "",
      "workflow_name" to "bill-feature-task",
      "mode" to "runtime",
      "contract_version" to "0.3",
      "workflow_status" to "running",
      "current_step_id" to "plan",
      "steps" to
        listOf(
          linkedMapOf<String, Any?>(
            "step_id" to "plan",
            "status" to "running",
            "attempt_count" to 1,
          ),
        ),
      "artifacts" to emptyMap<String, Any?>(),
      "started_at" to "",
      "updated_at" to "",
      "finished_at" to "",
    )

  private fun baseVerifySnapshot(): Map<String, Any?> =
    linkedMapOf(
      "workflow_id" to "wfv-19700101-000000-aaaa",
      "session_id" to "",
      "workflow_name" to "bill-feature-verify",
      "contract_version" to "0.3",
      "workflow_status" to "running",
      "current_step_id" to "gather_diff",
      "steps" to
        listOf(
          linkedMapOf<String, Any?>(
            "step_id" to "gather_diff",
            "status" to "running",
            "attempt_count" to 1,
          ),
        ),
      "artifacts" to emptyMap<String, Any?>(),
      "started_at" to "",
      "updated_at" to "",
      "finished_at" to "",
    )
}
