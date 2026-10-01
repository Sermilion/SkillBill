package skillbill.infrastructure.contracts.workflow.goal

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.goal.GOAL_PROGRESS_EVENT_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidGoalProgressEventSchemaError
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GoalProgressEventSchemaValidatorTest {
  @Test
  fun `valid phase event artifact map passes`() {
    val event =
      GoalProgressEvent(
        eventKind = GoalProgressEventKind.PHASE_STARTED,
        workflowId = "wfl-child",
        workflowPhase = "implement",
        processAlive = true,
        sequenceNumber = 1,
        timestamp = "2026-06-02T10:00:00Z",
      )
    GoalProgressEventSchemaValidator.validate(
      JsonCodec.anyToStringAnyMap(event.toPersistenceWire())!!,
      "test-phase",
    )
  }

  @Test
  fun `valid operation event artifact map passes`() {
    val event =
      GoalProgressEvent(
        eventKind = GoalProgressEventKind.OPERATION_STARTED,
        workflowId = "wfl-child",
        workflowPhase = "validate",
        processAlive = true,
        sequenceNumber = 2,
        timestamp = "2026-06-02T10:01:00Z",
        operationName = "gradlew check",
        operationKind = "build",
        expectedLong = true,
        outcome = GoalProgressOutcome.NONE,
      )
    GoalProgressEventSchemaValidator.validate(
      JsonCodec.anyToStringAnyMap(event.toPersistenceWire())!!,
      "test-operation",
    )
  }

  @Test
  fun `unknown event kind fails loudly with typed error`() {
    val malformed =
      linkedMapOf<String, Any?>(
        "contract_version" to GOAL_PROGRESS_EVENT_CONTRACT_VERSION,
        "event_kind" to "not_a_kind",
        "workflow_id" to "wfl-child",
        "workflow_phase" to "implement",
        "process_alive" to true,
        "sequence_number" to 1,
        "timestamp" to "2026-06-02T10:00:00Z",
      )
    assertFailsWith<InvalidGoalProgressEventSchemaError> {
      GoalProgressEventSchemaValidator.validate(malformed, "test-malformed")
    }
  }

  @Test
  fun `missing required workflow id fails loudly with typed error`() {
    val malformed =
      linkedMapOf<String, Any?>(
        "contract_version" to GOAL_PROGRESS_EVENT_CONTRACT_VERSION,
        "event_kind" to "phase_started",
        "workflow_phase" to "implement",
        "process_alive" to true,
        "sequence_number" to 1,
        "timestamp" to "2026-06-02T10:00:00Z",
      )
    assertFailsWith<InvalidGoalProgressEventSchemaError> {
      GoalProgressEventSchemaValidator.validate(malformed, "test-missing")
    }
  }

  @Test
  fun `operation event missing operation name fails loudly with typed error`() {
    val malformed =
      linkedMapOf<String, Any?>(
        "contract_version" to GOAL_PROGRESS_EVENT_CONTRACT_VERSION,
        "event_kind" to "operation_started",
        "workflow_id" to "wfl-child",
        "workflow_phase" to "validate",
        "process_alive" to true,
        "sequence_number" to 2,
        "timestamp" to "2026-06-02T10:01:00Z",
      )
    assertFailsWith<InvalidGoalProgressEventSchemaError> {
      GoalProgressEventSchemaValidator.validate(malformed, "test-missing-operation-name")
    }
  }

  @Test
  fun `unknown additional property fails loudly with typed error`() {
    val malformed =
      linkedMapOf<String, Any?>(
        "contract_version" to GOAL_PROGRESS_EVENT_CONTRACT_VERSION,
        "event_kind" to "phase_started",
        "workflow_id" to "wfl-child",
        "workflow_phase" to "implement",
        "process_alive" to true,
        "sequence_number" to 1,
        "timestamp" to "2026-06-02T10:00:00Z",
        "unexpected_field" to "nope",
      )
    assertFailsWith<InvalidGoalProgressEventSchemaError> {
      GoalProgressEventSchemaValidator.validate(malformed, "test-additional-property")
    }
  }

  @Test
  fun `negative sequence number fails loudly with typed error`() {
    val malformed =
      linkedMapOf<String, Any?>(
        "contract_version" to GOAL_PROGRESS_EVENT_CONTRACT_VERSION,
        "event_kind" to "phase_started",
        "workflow_id" to "wfl-child",
        "workflow_phase" to "implement",
        "process_alive" to true,
        "sequence_number" to -1,
        "timestamp" to "2026-06-02T10:00:00Z",
      )
    assertFailsWith<InvalidGoalProgressEventSchemaError> {
      GoalProgressEventSchemaValidator.validate(malformed, "test-negative-sequence")
    }
  }

  @Test
  fun `non-integer sequence number fails loudly with typed error`() {
    val malformed =
      linkedMapOf<String, Any?>(
        "contract_version" to GOAL_PROGRESS_EVENT_CONTRACT_VERSION,
        "event_kind" to "phase_started",
        "workflow_id" to "wfl-child",
        "workflow_phase" to "implement",
        "process_alive" to true,
        "sequence_number" to "not-a-number",
        "timestamp" to "2026-06-02T10:00:00Z",
      )
    assertFailsWith<InvalidGoalProgressEventSchemaError> {
      GoalProgressEventSchemaValidator.validate(malformed, "test-non-integer-sequence")
    }
  }

  @Test
  fun `unknown outcome enum value fails loudly with typed error`() {
    val malformed =
      linkedMapOf<String, Any?>(
        "contract_version" to GOAL_PROGRESS_EVENT_CONTRACT_VERSION,
        "event_kind" to "phase_completed",
        "workflow_id" to "wfl-child",
        "workflow_phase" to "implement",
        "process_alive" to true,
        "sequence_number" to 3,
        "timestamp" to "2026-06-02T10:02:00Z",
        "outcome" to "exploded",
      )
    assertFailsWith<InvalidGoalProgressEventSchemaError> {
      GoalProgressEventSchemaValidator.validate(malformed, "test-bad-outcome")
    }
  }
}
