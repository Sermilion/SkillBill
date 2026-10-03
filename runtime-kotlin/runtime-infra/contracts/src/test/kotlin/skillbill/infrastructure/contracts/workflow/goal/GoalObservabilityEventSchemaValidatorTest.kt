package skillbill.infrastructure.contracts.workflow.goal

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import skillbill.contracts.workflow.goal.GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode

class GoalObservabilityEventSchemaValidatorTest {
  @Test
  fun `missing required event fields fail with typed schema error`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        GoalObservabilityEventSchemaValidator.validate(
          mapOf(
            "contract_version" to GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION,
            "issue_key" to "SKILL-61",
          ),
          "goal_observability_latest_event",
        )
      }.also { assertEquals(InstallFailureCode.INVALID_GOAL_OBSERVABILITY_EVENT_SCHEMA, it.code) }

    assertContains(error.message.orEmpty(), "Goal observability event")
    assertContains(error.message.orEmpty(), "required")
  }

  @Test
  fun `valid compact event passes without heavy fields`() {
    GoalObservabilityEventSchemaValidator.validate(
      mapOf(
        "contract_version" to GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION,
        "issue_key" to "SKILL-61",
        "subtask_id" to 1,
        "workflow_phase" to "implement",
        "worker_role" to "phase_subagent",
        "liveness_class" to "durable_progress",
        "activity_summary" to "edited files",
        "timestamp" to "2026-06-01T00:00:00Z",
        "sequence_number" to 1,
      ),
      "goal_observability_latest_event",
    )
  }
}
