package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.ReasonBlock
import dev.skillbill.desktop.core.designsystem.detail.sbBoxSegment
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.designsystem.detail.section
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.list.TreeRow
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind

private const val TIME = "d MMM HH:mm"

/** The run this standalone item describes, from its execution history. */
private fun currentExecution(item: WorkItem, detail: Detail) =
  detail.executionHistory.firstOrNull { it.invocationId == item.control.invocationId }

/** HANDOFF 9.2 item 17: a standalone QA run's report, present when this run's own execution recorded report text. */
internal fun LazyListScope.qaReportSection(item: WorkItem, detail: Detail, onAction: (DetailAction) -> Unit) {
  if (item.kind == WorkKind.Goal) return
  val text = currentExecution(item, detail)?.qaReportText ?: return
  item(key = "qa-report") {
    Column(Modifier.padding(top = DocBlockGap)) {
      ReasonBlock(label = "QA report", text = text, onCopy = { onAction(DetailAction.Copy(it)) }, color = SkillBillTheme.colors.stDone)
    }
  }
}

/** HANDOFF 9.2 item 17: a standalone plan run's report. */
internal fun LazyListScope.planReportSection(item: WorkItem, detail: Detail) {
  if (item.kind == WorkKind.Goal) return
  val report = currentExecution(item, detail)?.planReport ?: return
  section("plan-report", "Plan report") {
    item(key = "plan-report-body") {
      val colors = SkillBillTheme.colors
      val type = SkillBillTheme.type
      // The box keeps the finding row's 12 inset; lines 6 apart.
      SbBox {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          BasicText(planSummary(report), style = type.tSmMedium.copy(color = colors.stDone))
          BasicText(report.featureName, style = type.tSm.copy(color = colors.fg))
          report.subtasks.forEach { subtask ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              BasicText(subtask.id.toString().padStart(2, '0'), style = type.monoSm.copy(color = colors.fgMuted))
              Column {
                BasicText(subtask.name, style = type.tSm.copy(color = colors.fg2))
                val after = subtask.dependsOn.joinToString(", ") { "#$it" }.takeIf { it.isNotEmpty() }?.let { " · after $it" }.orEmpty()
                BasicText(fileName(subtask.specPath) + after, style = type.monoSm.copy(color = colors.fgFaint))
              }
            }
          }
        }
      }
    }
  }
}

/** HANDOFF 9.2 item 18: every execution of this work in one box, paged; the header counts "n runs". */
internal fun LazyListScope.historySection(detail: Detail, state: DetailUiState, onAction: (DetailAction) -> Unit) {
  if (detail.executionHistory.isEmpty()) return
  val runs = detail.executionHistory.size
  section("history", "History", count = if (runs == 1) "1 run" else "$runs runs") {
    itemsIndexed(detail.executionHistory, key = { _, execution -> "execution-${execution.invocationId}-${execution.executionId}" }) { index, execution ->
      val end = formatInstant(execution.finishedAt ?: execution.updatedAt, TIME)
      BoxedFindingRow(
        index, runs,
        title = "${execution.lifecycleState} · ${formatInstant(execution.startedAt, TIME)} → $end",
        ref = execution.invocationId,
        meta = execution.result,
      )
    }
    pageFooter(DetailList.Executions, state, onAction)
  }
}

/** HANDOFF 9.2 item 19: a goal's spec bundle in one box; a file opens in its default app. The design's header has no count. */
internal fun LazyListScope.specBundleSection(detail: Detail, onAction: (DetailAction) -> Unit) {
  val paths = detail.goalSpecBundle?.let(::specBundlePaths).orEmpty()
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
