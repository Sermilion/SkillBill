package dev.skillbill.desktop.core.ui.status

import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlin.time.Instant

fun statusKind(lifecycle: Lifecycle, planningPrepared: Boolean, stalledSince: Instant?): StatusKind = when (lifecycle) {
  Lifecycle.Active -> when {
    !planningPrepared -> StatusKind.Planning
    stalledSince != null -> StatusKind.Stalled
    else -> StatusKind.Active
  }
  Lifecycle.Paused -> StatusKind.Paused
  Lifecycle.Blocked -> StatusKind.Blocked
  Lifecycle.Failed -> StatusKind.Failed
  Lifecycle.Terminal -> StatusKind.Completed
  Lifecycle.Canceled -> StatusKind.Canceled
  Lifecycle.Idle -> StatusKind.Pending
}

fun WorkItem.statusKind(): StatusKind = statusKind(lifecycle, planningPrepared, stalledSince)

fun subtaskStatusKind(status: String): StatusKind = when (status) {
  SubtaskStatusKeys.COMPLETE -> StatusKind.Completed
  SubtaskStatusKeys.COMPLETED_NO_CHANGE -> StatusKind.CompletedNoChange
  SubtaskStatusKeys.IN_PROGRESS -> StatusKind.Active
  SubtaskStatusKeys.PENDING -> StatusKind.Pending
  SubtaskStatusKeys.BLOCKED -> StatusKind.Blocked
  SubtaskStatusKeys.SKIPPED -> StatusKind.Skipped
  else -> StatusKind.Pending
}

private object SubtaskStatusKeys {
  const val COMPLETE = "complete"
  const val COMPLETED_NO_CHANGE = "completed_no_change"
  const val IN_PROGRESS = "in_progress"
  const val PENDING = "pending"
  const val BLOCKED = "blocked"
  const val SKIPPED = "skipped"
}
