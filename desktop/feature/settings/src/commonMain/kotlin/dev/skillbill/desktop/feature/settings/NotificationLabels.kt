package dev.skillbill.desktop.feature.settings

import dev.skillbill.desktop.core.domain.settings.NotificationKind

internal fun NotificationKind.label(): String = when (this) {
  NotificationKind.Blocked -> "A goal is blocked"
  NotificationKind.Failed -> "A goal or phase fails"
  NotificationKind.GoalCompleted -> "A goal completes"
  NotificationKind.PauseTookEffect -> "A pause takes effect"
  NotificationKind.StandalonePhaseFinished -> "A standalone phase finishes"
  NotificationKind.QaIncomplete -> "QA could not complete"
}
