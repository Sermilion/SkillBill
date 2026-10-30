package dev.skillbill.desktop.core.domain.model

fun WorkItem.outcomeLabel(): String = when (lifecycle) {
  Lifecycle.Active -> if (stalledSince != null) "Stalled" else "Active"
  Lifecycle.Paused -> "Paused"
  Lifecycle.Blocked -> "Blocked"
  Lifecycle.Idle -> "Idle"
  Lifecycle.Failed -> "Failed"
  Lifecycle.Canceled -> "Cancelled"
  Lifecycle.Terminal -> when (qaVerdict) {
    QaVerdict.Passed -> "Passed"
    QaVerdict.Findings -> "Findings"
    QaVerdict.Skipped -> "Skipped"
    QaVerdict.Attempted, QaVerdict.Incomplete -> "Incomplete"
    null -> "Complete"
  }
}
