package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.ExecutionInstance
import dev.skillbill.desktop.core.domain.model.ReviewReport
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind

/** Report blocks a folded summary shows before "Show full report". */
private const val FOLDED_REPORT_BLOCKS = 1

private val SETTLED_STATES = setOf("terminal", "completed", "complete")

/** The runtime's verdict token as the section words it: `changes_requested` reads "Changes requested". */
internal fun reviewVerdictLabel(verdict: String): String = verdict.replace('_', ' ').replaceFirstChar(Char::uppercase)

/** A lane as its chip reads: `bill-kmp-code-review-ui` becomes "kmp · ui"; a name off the convention stays whole. */
internal fun laneChipLabel(lane: String): String {
  val name = lane.removePrefix("bill-")
  val platform = name.substringBefore("-code-review-", missingDelimiterValue = "")
  return if (platform.isEmpty()) lane else "$platform · ${name.substringAfter("-code-review-")}"
}

/** "Approved · 3 findings · 2 fixed · 1 open", or just the tally when the report declared no verdict. */
internal fun reviewReportCaption(report: ReviewReport): String =
  listOfNotNull(report.verdict?.let(::reviewVerdictLabel), reviewFindingsTally(report.findings)).joinToString(" · ")

/**
 * Right after Activity, a standalone run's result: a review's findings as one boxed table under its verdict, with the rest of
 * the report folded below it; any other finished phase its result report. QA keeps its own report section.
 */
internal fun DetailSections.standaloneResultSection(item: WorkItem, detail: Detail) {
  if (item.kind == WorkKind.Goal) return
  val run = currentExecution(item, detail) ?: return
  val review = run.reviewReport
  when {
    review != null -> reviewResultSection(run, review)
    run.qaReportText != null -> Unit
    else -> resultSection(run)
  }
}

private fun DetailSections.reviewResultSection(run: ExecutionInstance, report: ReviewReport) {
  section("review-result", "Review findings", count = report.findings.size.toString()) {
    item(key = "review-result-caption") {
      val colors = SkillBillTheme.colors
      val verdictColor = when (report.verdict) {
        "approved" -> colors.stDone
        "changes_requested" -> colors.stBlocked
        else -> colors.fgFaint
      }
      Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(reviewReportCaption(report), style = SkillBillTheme.type.tSm.copy(color = verdictColor))
        if (report.lanes.isNotEmpty()) ReviewLanes(report.lanes, report.findings)
      }
    }
    itemsIndexed(report.findings, key = { index, finding -> "review-finding-$index-${finding.ref}" }) { index, finding ->
      ReviewFindingRow(index, report.findings.size, finding)
    }
    report.summary?.let { summary ->
      item(key = "review-result-summary-${run.invocationId}") {
        FoldedReport(summary, Modifier.padding(top = if (report.findings.isEmpty()) 0.dp else 16.dp))
      }
    }
  }
}

private fun DetailSections.resultSection(run: ExecutionInstance) {
  val text = run.result?.takeIf { it.isNotBlank() } ?: return
  if (run.finishedAt == null || run.lifecycleState.lowercase() !in SETTLED_STATES) return
  section("phase-result", "Result") {
    item(key = "phase-result-${run.invocationId}") { FoldedReport(text, openByDefault = true) }
  }
}

/**
 * A report in a box. With more than one block it carries a fold toggle, starting folded to the first block unless [openByDefault];
 * a phase's own result opens in full, a review's summary sits folded under its findings.
 */
@Composable
private fun FoldedReport(text: String, modifier: Modifier = Modifier, openByDefault: Boolean = false) {
  val blocks = remember(text) { reportBlocks(text) }
  var open by rememberSaveable(text) { mutableStateOf(openByDefault) }
  val folds = blocks.size > FOLDED_REPORT_BLOCKS
  SbBox(modifier) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      ReportText(text, blocks = if (open || !folds) blocks else blocks.take(FOLDED_REPORT_BLOCKS))
      if (folds) SbButton(if (open) "Show less" else "Show full report", { open = !open }, variant = SbButtonVariant.Ghost)
    }
  }
}
