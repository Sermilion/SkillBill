package dev.skillbill.desktop.app.notifications

import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.outcomeLabel
import dev.skillbill.desktop.core.domain.settings.NotificationKind

internal fun notificationTitle(item: WorkItem): String = item.summary

internal fun notificationBody(item: WorkItem, kind: NotificationKind): String = when {
  kind == NotificationKind.QaIncomplete -> item.workflowQaOutcome?.reason?.let { "QA could not complete: $it" } ?: "QA could not complete"
  item.lifecycle in setOf(Lifecycle.Terminal, Lifecycle.Canceled, Lifecycle.Failed) -> listOfNotNull(item.control.phaseId ?: item.issueKey, item.outcomeLabel()).joinToString(" · ")
  else -> item.stepLabel
}
