package dev.skillbill.tray.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.DesignLabel
import dev.skillbill.tray.core.designsystem.blue
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.border
import dev.skillbill.tray.core.designsystem.green
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.mono
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.designsystem.orange
import dev.skillbill.tray.core.domain.model.GoalQaResult
import dev.skillbill.tray.core.domain.model.PhaseConclusion
import dev.skillbill.tray.core.domain.model.ReviewFinding
import dev.skillbill.tray.core.domain.model.ReviewPass
import dev.skillbill.tray.core.domain.model.SubtaskPhaseResults
import dev.skillbill.tray.core.ui.ExpandableReason
import dev.skillbill.tray.core.ui.formatInstant

/** Review, audit and QA conclusions shown inside the goal activity panel. */
@Composable
internal fun PhaseResultsSection(results: List<SubtaskPhaseResults>, qa: GoalQaResult?, subtaskName: (Int) -> String?) {
  val shown = results.filter { it.reviewPasses.isNotEmpty() || it.review != null || it.audit != null }
  if (shown.isEmpty() && qa == null) return
  HorizontalDivider(color = border)
  DesignLabel("Phase results")
  shown.forEach { result ->
    Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        "#${result.subtaskId}${subtaskName(result.subtaskId)?.let { " · $it" }.orEmpty()}",
        color = ink, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 17.sp,
      )
      if (result.reviewPasses.isNotEmpty()) {
        result.reviewPasses.forEachIndexed { index, pass ->
          val last = index == result.reviewPasses.lastIndex
          ReviewPassRow(pass, result.review.takeIf { last && pass.findings.isEmpty() })
        }
      } else {
        result.review?.let { ConclusionRow("Review", it) }
      }
      result.audit?.let { ConclusionRow("Audit", it) }
    }
  }
  qa?.let { QaRow(it) }
}

@Composable
private fun ReviewPassRow(pass: ReviewPass, conclusion: PhaseConclusion?) {
  val unresolved = if (pass.unresolvedFindingCount > 0) "${pass.unresolvedFindingCount} unresolved" else null
  PhaseHeading(
    "Review",
    listOfNotNull(wireLabel(pass.verdict), unresolved).joinToString(" · "),
    verdictColor(pass.verdict),
  )
  if (pass.findings.isNotEmpty()) {
    pass.findings.forEach { FindingRow(it) }
  } else {
    PhaseBody("Review", conclusion?.summary, conclusion?.detail)
  }
}

@Composable
private fun FindingRow(finding: ReviewFinding) {
  PhaseBody(
    "Review finding",
    listOf(finding.severity, finding.label).joinToString(" · "),
    finding.text.takeIf { it != finding.label },
  )
}

@Composable
private fun ConclusionRow(phase: String, conclusion: PhaseConclusion) {
  PhaseHeading(
    phase,
    conclusion.verdict?.let(::wireLabel).orEmpty().ifEmpty { "completed" },
    verdictColor(conclusion.verdict),
  )
  PhaseBody(phase, conclusion.summary, conclusion.detail)
}

@Composable
private fun QaRow(qa: GoalQaResult) {
  Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Goal QA", color = ink, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 17.sp)
    PhaseHeading(
      "QA",
      listOf(wireLabel(qa.outcome), formatInstant(qa.recordedAt, "d MMM HH:mm")).joinToString(" · "),
      verdictColor(qa.outcome),
    )
    PhaseBody("Goal QA", qa.reason.takeUnless { qa.reportText != null && isBareQaReasonToken(it) }, null)
  }
}

@Composable
private fun PhaseHeading(phase: String, outcome: String, color: Color) {
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
    Text(phase, Modifier.width(PHASE_INDENT - 12.dp), color = muted, fontFamily = body, fontSize = 11.5.sp, lineHeight = 16.sp)
    Text(outcome, Modifier.weight(1f), color = color, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.sp)
  }
}

/** A short line of prose, with longer detail truncated to three lines and readable in full through "Show more". */
@Composable
private fun PhaseBody(title: String, summary: String?, detail: String?) {
  if (summary == null && detail == null) return
  Column(Modifier.padding(start = PHASE_INDENT), verticalArrangement = Arrangement.spacedBy(5.dp)) {
    val compact = detail ?: summary
    val overlapping =
      summary != null && (detail == null || detail == summary || detail.startsWith(summary))
    if (overlapping && compact != null) {
      ExpandableReason(compact, title, color = ink, fontSize = 12.sp, lineHeight = 18.sp)
    } else {
      summary?.let { SelectionContainer { Text(it, color = ink, fontFamily = body, fontSize = 12.sp, lineHeight = 18.sp) } }
      detail?.let { ExpandableReason(it, title, fontSize = 11.5.sp, lineHeight = 17.sp) }
    }
  }
}

private val PHASE_INDENT = 64.dp

private fun wireLabel(value: String): String = value.replace('_', ' ')

private val BARE_QA_REASON_TOKEN = Regex("^[a-z]+(?:_[a-z]+)*$")

private fun isBareQaReasonToken(reason: String?): Boolean = reason != null && BARE_QA_REASON_TOKEN.matches(reason)

private fun verdictColor(verdict: String?): Color = when (verdict) {
  "approved", "satisfied", "passed", "no_findings_verified" -> green
  "changes_requested", "gaps_found", "findings", "qa_findings", "review_cap_reached" -> orange
  "incomplete", "attempted" -> blue
  else -> muted
}
