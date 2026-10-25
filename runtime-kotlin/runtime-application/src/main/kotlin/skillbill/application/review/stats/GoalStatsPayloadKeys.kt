package skillbill.application.review.stats

internal object GoalStatsPayloadKeys {
  const val INTERACTION_SCENARIOS: String = "interaction_scenarios"
  const val TEST_PROBES: String = "test_probes"
  const val GOAL_ASSESSMENT: String = "goal_assessment"
  const val TRACED: String = "traced"
  const val BROKEN: String = "broken"
  const val REPAIRED: String = "repaired"
  const val UNKNOWN: String = "unknown"
  const val KILLED: String = "killed"
  const val SURVIVED: String = "survived"
  const val NOT_RUN: String = "not_run"
  const val FINDINGS_BY_SEVERITY: String = "findings_by_severity"
  const val BLOCKER: String = "blocker"
  const val MAJOR: String = "major"
  const val MINOR: String = "minor"
  const val CONFIRMED: String = "confirmed"
  const val REFUTED: String = "refuted"
  const val ROUNDS: String = "rounds"
  const val OPEN_FINDINGS: String = "open_findings"
  const val EXIT_REASONS: String = "exit_reasons"
}
