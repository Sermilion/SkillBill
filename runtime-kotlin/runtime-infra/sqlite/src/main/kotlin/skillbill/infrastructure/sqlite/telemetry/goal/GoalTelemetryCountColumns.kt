package skillbill.infrastructure.sqlite.telemetry.goal

import skillbill.infrastructure.sqlite.telemetry.GoalTelemetryPayloadKeys
import skillbill.infrastructure.sqlite.telemetry.lifecycle.intOrZero
import skillbill.telemetry.model.GoalAssessmentTelemetryCounts
import skillbill.telemetry.model.GoalSubtaskTraceProbeCounts

internal val GOAL_SUBTASK_COUNT_COLUMNS: List<String> =
  listOf(
    GoalTelemetryPayloadKeys.INTERACTION_SCENARIOS_TRACED,
    GoalTelemetryPayloadKeys.INTERACTION_SCENARIOS_BROKEN,
    GoalTelemetryPayloadKeys.INTERACTION_SCENARIOS_REPAIRED,
    GoalTelemetryPayloadKeys.INTERACTION_SCENARIOS_UNKNOWN,
    GoalTelemetryPayloadKeys.TEST_PROBES_KILLED,
    GoalTelemetryPayloadKeys.TEST_PROBES_SURVIVED,
    GoalTelemetryPayloadKeys.TEST_PROBES_REPAIRED,
    GoalTelemetryPayloadKeys.TEST_PROBES_NOT_RUN,
  )

internal val GOAL_ASSESSMENT_COUNT_COLUMNS: List<String> =
  listOf(
    GoalTelemetryPayloadKeys.GOAL_ASSESSMENT_BLOCKER_FINDINGS,
    GoalTelemetryPayloadKeys.GOAL_ASSESSMENT_MAJOR_FINDINGS,
    GoalTelemetryPayloadKeys.GOAL_ASSESSMENT_MINOR_FINDINGS,
    GoalTelemetryPayloadKeys.GOAL_ASSESSMENT_CONFIRMED,
    GoalTelemetryPayloadKeys.GOAL_ASSESSMENT_REFUTED,
    GoalTelemetryPayloadKeys.GOAL_ASSESSMENT_ROUNDS,
    GoalTelemetryPayloadKeys.GOAL_ASSESSMENT_OPEN_FINDINGS,
  )

internal const val GOAL_ASSESSMENT_EXIT_REASON_COLUMN: String = GoalTelemetryPayloadKeys.GOAL_ASSESSMENT_EXIT_REASON

internal fun GoalSubtaskTraceProbeCounts.columnValues(): List<Int> =
  listOf(
    scenariosTraced,
    scenariosBroken,
    scenariosRepaired,
    scenariosUnknown,
    probesKilled,
    probesSurvived,
    probesRepaired,
    probesNotRun,
  )

internal fun GoalAssessmentTelemetryCounts.columnValues(): List<Int> =
  listOf(blockerFindings, majorFindings, minorFindings, confirmed, refuted, rounds, openFindings)

internal fun Map<String, Any?>.subtaskCountPayload(): Map<String, Any?> =
  GOAL_SUBTASK_COUNT_COLUMNS.associateWith { intOrZero(it) }

internal fun Map<String, Any?>.assessmentCountPayload(): Map<String, Any?> {
  val counts = GOAL_ASSESSMENT_COUNT_COLUMNS.associateWith<String, Any?> { intOrZero(it) }
  val exitReason = this[GoalTelemetryPayloadKeys.GOAL_ASSESSMENT_EXIT_REASON]?.toString()
  return counts + (GoalTelemetryPayloadKeys.GOAL_ASSESSMENT_EXIT_REASON to exitReason)
}
