package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind

internal data class QaPreviewSelection(val invocationId: String, val directory: String?, val active: Boolean)

internal fun selectedQaPreview(item: WorkItem, detail: Detail, selectedRun: String?, subtask: SubtaskKey?): QaPreviewSelection? {
  if (isStandaloneQa(item)) {
    val run = qaDisplayedRun(item, detail, selectedRun) ?: return null
    val checklist = run.qaChecklist ?: return null
    if (checklist.targets.none { it.name.equals("desktop", ignoreCase = true) }) return null
    return QaPreviewSelection(run.invocationId, checklist.invocationDirectory, qaRunLive(item, run))
  }
  if (item.kind != WorkKind.Goal) return null
  val preview = detail.goalQaPreview ?: return null
  if (preview.targets.none { it.equals("desktop", ignoreCase = true) }) return null
  val selected = selectedSubtask(item, detail, subtask) ?: SubtaskKey.GoalFinish
  val owner = preview.subtaskId?.let { SubtaskKey.Subtask(it) } ?: SubtaskKey.GoalFinish
  if (selected != owner) return null
  return QaPreviewSelection(
    preview.invocationId,
    preview.invocationDirectory,
    preview.active && item.running && item.lifecycle == Lifecycle.Active && detail.currentPhase == "qa",
  )
}
