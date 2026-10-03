package skillbill.mcp.telemetry

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode

class TelemetryEventSchemaViolationsTest {
  private fun validVerifyStartedEnvelope(): MutableMap<String, Any?> =
    linkedMapOf(
      "event_name" to "feature_verify_started",
      "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION,
      "acceptance_criteria_count" to 1,
      "rollout_relevant" to false,
      "spec_summary" to "summary",
    )

  private fun validVerifyFinishedEnvelope(): MutableMap<String, Any?> =
    linkedMapOf(
      "event_name" to "feature_verify_finished",
      "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION,
      "feature_flag_audit_performed" to true,
      "review_iterations" to 1,
      "audit_result" to "all_pass",
      "completion_status" to "completed",
      "session_id" to "fvs-1",
      "gaps_found" to emptyList<String>(),
      "orchestrated" to false,
      "acceptance_criteria_count" to 1,
      "rollout_relevant" to false,
      "spec_summary" to "summary",
      "duration_seconds" to 120,
    )

  private fun validQualityCheckFinishedEnvelope(): MutableMap<String, Any?> =
    linkedMapOf(
      "event_name" to "quality_check_finished",
      "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION,
      "final_failure_count" to 0,
      "iterations" to 1,
      "result" to "pass",
      "session_id" to "qck-1",
      "failing_check_names" to emptyList<String>(),
      "unsupported_reason" to "",
      "orchestrated" to false,
      "routed_skill" to "bill-kotlin-code-check",
      "detected_stack" to "kotlin",
      "fallback" to false,
      "scope_type" to "branch_diff",
      "initial_failure_count" to 2,
      "duration_seconds" to 30,
    )

  @Test
  fun `valid base envelope passes validation`() {
    TelemetryEventSchemaValidator.validate(validVerifyStartedEnvelope())
    TelemetryEventSchemaValidator.validate(validVerifyFinishedEnvelope())
  }

  @Test
  fun `unknown event_name fails validation with event_name in reason`() {
    val envelope = validVerifyStartedEnvelope()
    envelope["event_name"] = "this_event_does_not_exist"

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        TelemetryEventSchemaValidator.validate(envelope)
      }.also { assertEquals(InstallFailureCode.INVALID_TELEMETRY_EVENT_SCHEMA, it.code) }

    assertContains(error.message.orEmpty(), "this_event_does_not_exist")

    val combined = error.message.orEmpty().lowercase()
    val signals = listOf("oneof", "event_name", "anyof", "schema")
    val hits = signals.count { it in combined }
    assertEquals(
      hits > 0,
      true,
      "Unknown event_name violation reason should mention oneOf/event_name signal — got '${error.message.orEmpty()}'.",
    )
  }

  @Test
  fun `missing required field fails validation`() {
    val envelope = validVerifyStartedEnvelope()
    envelope.remove("spec_summary")

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        TelemetryEventSchemaValidator.validate(envelope)
      }.also { assertEquals(InstallFailureCode.INVALID_TELEMETRY_EVENT_SCHEMA, it.code) }

    assertContains(error.message.orEmpty().lowercase(), "spec_summary")
    assertContains(error.message.orEmpty(), "feature_verify_started")
  }

  @Test
  fun `wrong contract_version fails validation with contract_version field path`() {
    val envelope = validVerifyStartedEnvelope()
    envelope["contract_version"] = "9.99"

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        TelemetryEventSchemaValidator.validate(envelope)
      }.also { assertEquals(InstallFailureCode.INVALID_TELEMETRY_EVENT_SCHEMA, it.code) }
    assertContains(error.message.orEmpty().lowercase(), "contract_version")
    assertContains(error.message.orEmpty(), "feature_verify_started")
  }

  @Test
  fun `unknown additional property fails strict event validation`() {
    val envelope = validVerifyStartedEnvelope()
    envelope["bogus_extra"] = true

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        TelemetryEventSchemaValidator.validate(envelope)
      }.also { assertEquals(InstallFailureCode.INVALID_TELEMETRY_EVENT_SCHEMA, it.code) }
    assertContains(error.message.orEmpty(), "bogus_extra")
    assertContains(error.message.orEmpty(), "feature_verify_started")
  }

  @Test
  fun `type mismatch on a typed field fails validation`() {
    val envelope = validVerifyStartedEnvelope()

    envelope["acceptance_criteria_count"] = "not-a-number"

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        TelemetryEventSchemaValidator.validate(envelope)
      }.also { assertEquals(InstallFailureCode.INVALID_TELEMETRY_EVENT_SCHEMA, it.code) }
    assertContains(error.message.orEmpty().lowercase(), "acceptance_criteria_count")
    assertContains(error.message.orEmpty(), "feature_verify_started")
  }

  @Test
  fun `discriminator mismatch a finished payload tagged as started fails validation`() {
    val finishedShapedButStartedTagged = validVerifyFinishedEnvelope()
    finishedShapedButStartedTagged["event_name"] = "feature_verify_started"

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        TelemetryEventSchemaValidator.validate(finishedShapedButStartedTagged)
      }.also { assertEquals(InstallFailureCode.INVALID_TELEMETRY_EVENT_SCHEMA, it.code) }

    assertContains(error.message.orEmpty(), "feature_verify_started")
  }

  @Test
  fun `a null final failure count is rejected unless the reconciler wrote the terminal`() {
    val callerReported = validQualityCheckFinishedEnvelope()
    callerReported["final_failure_count"] = null

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        TelemetryEventSchemaValidator.validate(callerReported)
      }.also { assertEquals(InstallFailureCode.INVALID_TELEMETRY_EVENT_SCHEMA, it.code) }
    assertContains(error.message.orEmpty(), "final_failure_count")
    assertContains(error.message.orEmpty(), "quality_check_finished")

    val reconcilerClosed = validQualityCheckFinishedEnvelope()
    reconcilerClosed["final_failure_count"] = null
    reconcilerClosed["final_failure_count_availability"] = "unavailable_incomplete"
    reconcilerClosed["completion"] = "reconciler_stale"
    reconcilerClosed["result"] = "stale"
    TelemetryEventSchemaValidator.validate(reconcilerClosed)
  }

  @Test
  fun `SKILL-175 retired prose events are rejected as unknown event names`() {
    listOf(
      "feature_task_prose_started",
      "feature_task_prose_finished",
      "feature_task_prose_stats",
      "feature_implement_started",
      "goal_prose_started",
      "goal_prose_subtask_finished",
      "goal_prose_finished",
    ).forEach { retired ->
      val envelope =
        linkedMapOf<String, Any?>(
          "event_name" to retired,
          "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION,
        )

      val error =
        assertFailsWith<SkillBillRuntimeException>(message = retired) {
          TelemetryEventSchemaValidator.validate(envelope)
        }.also { assertEquals(InstallFailureCode.INVALID_TELEMETRY_EVENT_SCHEMA, it.code) }
      assertContains(error.message.orEmpty(), retired)
    }
  }
}
