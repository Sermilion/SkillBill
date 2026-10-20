package dev.skillbill.tray.feature.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.DesignLabel
import dev.skillbill.tray.core.designsystem.blue
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.border
import dev.skillbill.tray.core.designsystem.dim
import dev.skillbill.tray.core.designsystem.green
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.mono
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.designsystem.orange
import dev.skillbill.tray.core.designsystem.red
import dev.skillbill.tray.core.domain.model.GoalQaResult
import dev.skillbill.tray.core.domain.model.PhaseConclusion
import dev.skillbill.tray.core.domain.model.ReviewFinding
import dev.skillbill.tray.core.domain.model.ReviewPass
import dev.skillbill.tray.core.domain.model.SubtaskPhaseResults
import dev.skillbill.tray.core.ui.ReasonTooltip
import dev.skillbill.tray.core.ui.formatInstant

/** Review, audit and QA conclusions shown inside the goal activity panel. */
@Composable
internal fun PhaseResultsSection(results: List<SubtaskPhaseResults>, qa: GoalQaResult?, subtaskName: (Int) -> String?) {
  val shown = results.filter { it.reviewPasses.isNotEmpty() || it.audit != null }
  if (shown.isEmpty() && qa == null) return
  HorizontalDivider(color = border)
  DesignLabel("Phase results")
  shown.forEach { result ->
    Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        "#${result.subtaskId}${subtaskName(result.subtaskId)?.let { " · $it" }.orEmpty()}",
        color = ink, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 17.sp,
      )
      result.reviewPasses.forEach { ReviewPassRow(it) }
      result.audit?.let { AuditRow(it) }
    }
  }
  qa?.let { QaRow(it) }
}

@Composable
private fun ReviewPassRow(pass: ReviewPass) {
  val unresolved = if (pass.unresolvedFindingCount > 0) "${pass.unresolvedFindingCount} unresolved" else null
  PhaseHeading(
    "Review",
    listOfNotNull("pass ${pass.passNumber}", wireLabel(pass.verdict), unresolved).joinToString(" · "),
    verdictColor(pass.verdict),
  )
  if (pass.findings.isNotEmpty()) Column(Modifier.padding(start = PHASE_INDENT), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    pass.findings.forEach { FindingRow(it) }
  }
}

@Composable
private fun FindingRow(finding: ReviewFinding) {
  Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
    SeverityChip(finding.severity)
    SelectionContainer(Modifier.weight(1f)) {
      Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(finding.label, color = ink, fontFamily = body, fontSize = 12.sp, lineHeight = 17.sp)
        if (finding.text != finding.label) Text(finding.text, color = muted, fontFamily = body, fontSize = 11.5.sp, lineHeight = 17.sp)
      }
    }
  }
}

@Composable
private fun AuditRow(audit: PhaseConclusion) {
  val attempts = if (audit.attemptCount > 1) "${audit.attemptCount} attempts" else null
  PhaseHeading(
    "Audit",
    listOfNotNull(audit.verdict?.let(::wireLabel), attempts).joinToString(" · ").ifEmpty { "completed" },
    verdictColor(audit.verdict),
  )
  PhaseBody(audit.summary, audit.detail)
}

@Composable
private fun QaRow(qa: GoalQaResult) {
  val fixes = when (qa.fixAttempts) { 0 -> null; 1 -> "1 fix attempt"; else -> "${qa.fixAttempts} fix attempts" }
  Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Goal QA", color = ink, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 17.sp)
    PhaseHeading(
      "QA",
      listOfNotNull(wireLabel(qa.outcome), fixes, formatInstant(qa.recordedAt, "d MMM HH:mm")).joinToString(" · "),
      verdictColor(qa.outcome),
    )
    PhaseBody(qa.reason, null)
  }
}

@Composable
private fun PhaseHeading(phase: String, outcome: String, color: Color) {
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
    Text(phase, Modifier.width(PHASE_INDENT - 12.dp), color = muted, fontFamily = body, fontSize = 11.5.sp, lineHeight = 16.sp)
    Text(outcome, Modifier.weight(1f), color = color, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.sp)
  }
}

/** A short line of prose, with longer detail truncated to three lines and readable in full on hover. */
@Composable
private fun PhaseBody(summary: String?, detail: String?) {
  if (summary == null && detail == null) return
  Column(Modifier.padding(start = PHASE_INDENT), verticalArrangement = Arrangement.spacedBy(5.dp)) {
    summary?.let { SelectionContainer { Text(it, color = ink, fontFamily = body, fontSize = 12.sp, lineHeight = 18.sp) } }
    detail?.takeIf { it != summary }?.let { ReasonTooltip(it, fontSize = 11.5.sp, lineHeight = 17.sp) }
  }
}

@Composable
private fun SeverityChip(severity: String) {
  val color = severityColor(severity)
  Text(
    severity.uppercase(),
    Modifier.width(62.dp).border(1.dp, color.copy(alpha = .45f), RoundedCornerShape(4.dp)).padding(vertical = 2.dp),
    color = color, fontFamily = mono, fontWeight = FontWeight.SemiBold, fontSize = 9.5.sp, lineHeight = 12.sp,
    letterSpacing = .6.sp, textAlign = TextAlign.Center,
  )
}

private val PHASE_INDENT = 64.dp

private fun wireLabel(value: String): String = value.replace('_', ' ')

private fun severityColor(severity: String): Color = when (severity.lowercase()) {
  "blocker", "critical" -> red
  "major", "high" -> orange
  "minor", "medium" -> blue
  else -> dim
}

private fun verdictColor(verdict: String?): Color = when (verdict) {
  "approved", "satisfied", "passed", "no_findings_verified" -> green
  "changes_requested", "gaps_found", "findings", "qa_findings", "review_cap_reached" -> orange
  "incomplete", "attempted" -> blue
  else -> muted
}
