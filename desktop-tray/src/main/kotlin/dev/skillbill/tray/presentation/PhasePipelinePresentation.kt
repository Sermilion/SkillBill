package dev.skillbill.tray.presentation

import dev.skillbill.tray.domain.Detail
import dev.skillbill.tray.domain.WorkPhase

internal fun phasePipeline(detail: Detail): List<WorkPhase> {
  val loaded = detail.phases.associateBy { it.id }
  val current = detail.item.currentPhaseId ?: detail.currentPhase
  val ids = (detail.stepPipeline + loaded.keys + listOfNotNull(current)).distinct()
  return ids.map { id ->
    loaded[id] ?: WorkPhase(id, if (id == current) "in_progress" else "pending",
      detail.item.phaseExecution?.takeIf { it.phaseId == id }?.count ?: 0, false)
  }
}
