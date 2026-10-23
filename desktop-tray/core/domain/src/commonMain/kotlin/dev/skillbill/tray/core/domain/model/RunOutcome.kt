package dev.skillbill.tray.core.domain.model

/** The verdict of a standalone QA run: [ATTEMPTED] until the run settles a report, then the report's verdict. */
enum class QaVerdict { ATTEMPTED, PASSED, FINDINGS, SKIPPED, INCOMPLETE }

/** What a run is doing now or how it ended, the one status every surface names. */
enum class RunOutcome { ACTIVE, STALLED, PAUSED, BLOCKED, IDLE, COMPLETE, PASSED, FINDINGS, SKIPPED, INCOMPLETE, FAILED, CANCELLED }

/**
 * The run's outcome from its lifecycle and, for a finished QA phase, its recorded verdict. A finished QA run that never
 * settled a report verdict reads as incomplete.
 */
fun WorkItem.outcome(): RunOutcome = when (lifecycle) {
  Lifecycle.ACTIVE -> if (stalled()) RunOutcome.STALLED else RunOutcome.ACTIVE
  Lifecycle.PAUSED -> RunOutcome.PAUSED
  Lifecycle.BLOCKED -> RunOutcome.BLOCKED
  Lifecycle.IDLE -> RunOutcome.IDLE
  Lifecycle.FAILED -> RunOutcome.FAILED
  Lifecycle.CANCELED -> RunOutcome.CANCELLED
  Lifecycle.TERMINAL -> terminalOutcome(qaVerdict)
}

private fun terminalOutcome(qaVerdict: QaVerdict?): RunOutcome = when (qaVerdict) {
  QaVerdict.PASSED -> RunOutcome.PASSED
  QaVerdict.FINDINGS -> RunOutcome.FINDINGS
  QaVerdict.SKIPPED -> RunOutcome.SKIPPED
  QaVerdict.INCOMPLETE, QaVerdict.ATTEMPTED -> RunOutcome.INCOMPLETE
  null -> RunOutcome.COMPLETE
}

/** Whether the run has ended, so its outcome replaces the step it last reported. */
fun WorkItem.finished(): Boolean = lifecycle == Lifecycle.TERMINAL || lifecycle == Lifecycle.CANCELED || lifecycle == Lifecycle.FAILED

/**
 * The outcome of one recorded standalone execution, read from the lifecycle state and, for a QA run, the verdict the
 * runtime stored for that execution.
 */
fun standaloneExecutionOutcome(lifecycleState: String, qaVerdict: QaVerdict? = null): RunOutcome = when (lifecycleState) {
  "active", "running" -> RunOutcome.ACTIVE
  "paused", "runner_interrupted" -> RunOutcome.PAUSED
  "blocked" -> RunOutcome.BLOCKED
  "canceled" -> RunOutcome.CANCELLED
  "terminal", "completed", "success" -> terminalOutcome(qaVerdict)
  else -> RunOutcome.FAILED
}
