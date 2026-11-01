package skillbill.engine.goalrunner.persist

import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary

object NoopGoalRunnerAttemptLedgerStore : GoalRunnerAttemptLedgerStore {
  override fun readAttemptLedgerSummary(issueKey: String): GoalRunnerAttemptLedgerSummary {
    return GoalRunnerAttemptLedgerSummary()
  }
}
