package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem

internal fun WorkItem.isStandalonePlan(): Boolean = standaloneExecution && control.phaseId == "plan"

internal fun planImplementationEnabled(item: WorkItem, detail: Detail): Boolean {
  return item.isStandalonePlan() && !item.issueKey.isNullOrBlank() && planFinished(item, detail)
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
