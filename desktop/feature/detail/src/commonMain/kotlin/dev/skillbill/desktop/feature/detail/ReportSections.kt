package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.ReasonBlock
import dev.skillbill.desktop.core.designsystem.detail.sbBoxSegment
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.list.TreeRow
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind

/** The run this standalone item describes, from its execution history. */
internal fun currentExecution(item: WorkItem, detail: Detail) =
  detail.executionHistory.firstOrNull { it.invocationId == item.control.invocationId }

/** HANDOFF 9.2 item 17: a standalone QA run's report, present when this run's own execution recorded report text. */
internal fun DetailSections.qaReportSection(item: WorkItem, detail: Detail, onAction: (DetailAction) -> Unit) {
  if (item.kind == WorkKind.Goal) return
  val text = currentExecution(item, detail)?.qaReportText ?: return
  item(key = "qa-report") {
    Column(Modifier.padding(top = DocBlockGap)) {
      ReasonBlock(label = "QA report", text = text, onCopy = { onAction(DetailAction.Copy(it)) }, color = SkillBillTheme.colors.stDone)
    }
  }
}

/** A standalone run's saved phase briefings, one folded block per phase, in pipeline order. */
internal fun DetailSections.briefingSection(item: WorkItem, detail: Detail, onAction: (DetailAction) -> Unit) {
  if (item.kind == WorkKind.Goal) return
  val briefings = detail.phases.mapNotNull { phase -> phase.briefingText?.takeIf { it.isNotBlank() }?.let { phase.id to it } }
  if (briefings.isEmpty()) return
  section("briefings", if (briefings.size == 1) "Briefing" else "Briefings") {
    itemsIndexed(briefings, key = { _, (phaseId, _) -> "briefing-$phaseId" }) { index, (phaseId, text) ->
      Column(Modifier.padding(top = if (index > 0) 8.dp else 0.dp)) {
        ReasonBlock(label = "$phaseId briefing", text = text, onCopy = { onAction(DetailAction.Copy(it)) })
      }
    }
  }
}

/**
 * HANDOFF 9.2 item 15: a standalone run's spec bundle in one box; a file opens in its default app. The design's header has no
 * count. A goal's files are in the details pane.
 */
internal fun DetailSections.specBundleSection(item: WorkItem, detail: Detail, onAction: (DetailAction) -> Unit) {
  if (item.kind == WorkKind.Goal) return
  val report = detail.goalSpecBundle ?: currentExecution(item, detail)?.planReport
  val paths = report?.let(::specBundlePaths).orEmpty()
  if (paths.isEmpty()) return
  section("spec-bundle", "Spec bundle") {
    itemsIndexed(paths, key = { _, path -> "spec-file-$path" }) { index, path ->
      val name = fileName(path)
      TreeRow(
        title = name,
        onClick = { onAction(DetailAction.OpenSpecFile(path)) },
        modifier = Modifier.sbBoxSegment(index, paths.size),
        file = true,
        // The design gives YAML the plain file icon and every other file the text icon.
        lead = { SbIcon(if (name.endsWith(".yaml") || name.endsWith(".yml")) LucideIcons.file else LucideIcons.fileText, SbIconSize.Sm) },
      )
    }
  }
}
