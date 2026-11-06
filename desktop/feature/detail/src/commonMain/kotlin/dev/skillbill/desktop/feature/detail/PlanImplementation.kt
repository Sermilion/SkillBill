package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind

internal fun WorkItem.isStandalonePlan(): Boolean = standaloneExecution && control.phaseId == "plan"

/** A goal's own plan step hands off to implementation by itself, so only a plan launched on its own offers Implement. */
internal fun WorkItem.offersPlanImplementation(): Boolean = isStandalonePlan() && kind != WorkKind.Goal

internal fun planImplementationEnabled(item: WorkItem, detail: Detail): Boolean {
  return item.offersPlanImplementation() && !item.issueKey.isNullOrBlank() && planFinished(item, detail)
}

internal fun planFinished(item: WorkItem, detail: Detail?): Boolean {
  if (detail == null) return false
  val run = currentExecution(item, detail)
  val plan = detail.standalonePlan
  val unitsSaved = plan != null && plan.totalSubtaskCount > 0 &&
    plan.units.size == plan.totalSubtaskCount && plan.units.all { it.state == "saved" }
  return !item.running && item.lifecycle == Lifecycle.Terminal &&
    run?.finishedAt != null && run.planReport != null && unitsSaved
}
