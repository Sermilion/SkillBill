package dev.skillbill.tray.core.domain.model

/** The verdict of a standalone QA run: [ATTEMPTED] until the run settles a receipt, then the receipt's verdict. */
enum class QaVerdict { ATTEMPTED, PASSED, FINDINGS, SKIPPED, INCOMPLETE }

/** What a run is doing now or how it ended, the one status every surface names. */
enum class RunOutcome { ACTIVE, PAUSED, BLOCKED, IDLE, COMPLETE, PASSED, FINDINGS, SKIPPED, INCOMPLETE, FAILED, CANCELLED }

/**
 * The run's outcome from its lifecycle and, for a finished QA phase, its recorded verdict. A finished QA run that never
 * settled a verdict reads as complete, the most that its record states.
 */
fun WorkItem.outcome(): RunOutcome = when (lifecycle) {
  Lifecycle.ACTIVE -> RunOutcome.ACTIVE
  Lifecycle.PAUSED -> RunOutcome.PAUSED
  Lifecycle.BLOCKED -> RunOutcome.BLOCKED
  Lifecycle.IDLE -> RunOutcome.IDLE
  Lifecycle.FAILED -> RunOutcome.FAILED
  Lifecycle.CANCELED -> RunOutcome.CANCELLED
  Lifecycle.TERMINAL -> when (qaVerdict) {
    QaVerdict.PASSED -> RunOutcome.PASSED
    QaVerdict.FINDINGS -> RunOutcome.FINDINGS
    QaVerdict.SKIPPED -> RunOutcome.SKIPPED
    QaVerdict.INCOMPLETE -> RunOutcome.INCOMPLETE
    QaVerdict.ATTEMPTED, null -> RunOutcome.COMPLETE
  }
}

/** Whether the run has ended, so its outcome replaces the step it last reported. */
fun WorkItem.finished(): Boolean = lifecycle == Lifecycle.TERMINAL || lifecycle == Lifecycle.CANCELED || lifecycle == Lifecycle.FAILED

/** The outcome of one recorded standalone execution, read from the lifecycle state the runtime stored for it. */
fun standaloneExecutionOutcome(lifecycleState: String): RunOutcome = when (lifecycleState) {
  "active", "running" -> RunOutcome.ACTIVE
  "paused", "runner_interrupted" -> RunOutcome.PAUSED
  "blocked" -> RunOutcome.BLOCKED
  "canceled" -> RunOutcome.CANCELLED
  "terminal", "completed", "success" -> RunOutcome.COMPLETE
  else -> RunOutcome.FAILED
}
