package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem

internal fun WorkItem.isStandalonePlan(): Boolean = standaloneExecution && control.phaseId == "plan"

internal fun planImplementationEnabled(item: WorkItem, detail: Detail): Boolean {
  val run = currentExecution(item, detail)
  return item.isStandalonePlan() && !item.running && item.lifecycle == Lifecycle.Terminal &&
    !item.issueKey.isNullOrBlank() && run?.finishedAt != null && run.planReport != null
}
