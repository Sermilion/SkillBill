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
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.border
import dev.skillbill.tray.core.designsystem.green
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.mono
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.designsystem.orange
import dev.skillbill.tray.core.domain.model.GoalQaFinding
import dev.skillbill.tray.core.domain.model.GoalQaResult

/** Findings goal QA reported, split into the ones a later QA run no longer saw and the ones left for the operator. */
@Composable
internal fun QaFindingsSection(qa: GoalQaResult?) {
  val findings = qa?.findings.orEmpty()
  if (qa == null || findings.isEmpty()) return
  val (fixed, notFixed) = findings.partition { it.fixed }
  HorizontalDivider(color = border)
  DesignLabel("QA findings")
  Text(
    qaFindingsSummary(findings.size, notFixed.size, qa.fixAttempts),
    color = if (notFixed.isEmpty()) green else orange,
    fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 17.sp,
  )
  QaFindingGroup("Not fixed", notFixed, ink, showEvidence = true)
  QaFindingGroup("Fixed", fixed, muted, showEvidence = false)
}

internal fun qaFindingsSummary(total: Int, notFixed: Int, attempts: Int): String {
  val after = when (attempts) { 0 -> ""; 1 -> " after 1 attempt"; else -> " after $attempts attempts" }
  return if (notFixed == 0) {
    "QA found ${findingCount(total)}; all fixed$after."
  } else {
    "QA could not fix ${findingCount(notFixed)}$after. Review them yourself."
  }
}

private fun findingCount(count: Int): String = if (count == 1) "1 finding" else "$count findings"

@Composable
private fun QaFindingGroup(label: String, findings: List<GoalQaFinding>, titleColor: Color, showEvidence: Boolean) {
  if (findings.isEmpty()) return
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(label, color = muted, fontFamily = body, fontSize = 11.5.sp, lineHeight = 16.sp)
    findings.forEach { finding -> QaFindingRow(finding, titleColor, showEvidence) }
  }
}

@Composable
private fun QaFindingRow(finding: GoalQaFinding, titleColor: Color, showEvidence: Boolean) {
  Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
    Text(finding.id, Modifier.width(52.dp), color = titleColor, fontFamily = mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 17.sp)
    SelectionContainer(Modifier.weight(1f)) {
      Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(finding.title.ifBlank { finding.id }, color = titleColor, fontFamily = body, fontSize = 12.sp, lineHeight = 17.sp)
        if (showEvidence) finding.evidencePath?.let { Text(it, color = muted, fontFamily = mono, fontSize = 10.5.sp, lineHeight = 15.sp) }
      }
    }
  }
}
