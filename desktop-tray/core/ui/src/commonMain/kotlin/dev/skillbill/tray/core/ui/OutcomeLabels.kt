package dev.skillbill.tray.core.ui

import dev.skillbill.tray.core.domain.model.RunOutcome
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.outcome

/** The word every surface shows for an outcome; no surface prints a lifecycle enum name. */
fun RunOutcome.label(): String = when (this) {
  RunOutcome.ACTIVE -> "Active"
  RunOutcome.STALLED -> "Stalled"
  RunOutcome.PAUSED -> "Paused"
  RunOutcome.BLOCKED -> "Blocked"
  RunOutcome.IDLE -> "Idle"
  RunOutcome.COMPLETE -> "Complete"
  RunOutcome.PASSED -> "Passed"
  RunOutcome.FINDINGS -> "Findings"
  RunOutcome.SKIPPED -> "Skipped"
  RunOutcome.INCOMPLETE -> "Incomplete"
  RunOutcome.FAILED -> "Failed"
  RunOutcome.CANCELLED -> "Cancelled"
}

fun WorkItem.outcomeLabel(): String = outcome().label()
