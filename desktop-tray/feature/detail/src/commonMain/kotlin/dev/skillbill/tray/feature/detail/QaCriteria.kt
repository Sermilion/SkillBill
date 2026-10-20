package dev.skillbill.tray.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.green
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.mono
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.designsystem.orange
import dev.skillbill.tray.core.designsystem.red
import dev.skillbill.tray.core.domain.model.QaCriterionFinding
import dev.skillbill.tray.core.domain.model.QaCriterionResult
import dev.skillbill.tray.core.domain.model.QaRunReport

/** Every criterion a standalone QA run checked, with what it checked and, for a failed one, what QA expected and saw. */
@Composable
internal fun QaCriteriaSection(report: QaRunReport) {
  if (report.criteria.isEmpty()) return
  val failed = report.criteria.count { !it.passed }
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      qaCriteriaSummary(report),
      color = if (failed == 0) green else orange,
      fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 17.sp,
    )
    report.criteria.forEach { QaCriterionRow(it) }
  }
}

internal fun qaCriteriaSummary(report: QaRunReport): String {
  val linked = report.criteria.filter { it.id != null }
  val failed = linked.filterNot { it.passed }.mapNotNull { it.id }
  val passed = linked.size - failed.size
  return when {
    linked.isEmpty() -> "QA did not link its checks to acceptance criteria."
    failed.isEmpty() -> "All ${criteriaCount(linked.size)} passed."
    else -> "${failed.joinToString(", ")} failed; $passed of ${criteriaCount(linked.size)} passed."
  }
}

private fun criteriaCount(count: Int): String = if (count == 1) "1 criterion" else "$count criteria"

@Composable
private fun QaCriterionRow(criterion: QaCriterionResult) {
  Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
    Text(
      if (criterion.passed) "PASS" else "FAIL", Modifier.width(34.dp),
      color = if (criterion.passed) green else red, fontFamily = mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 17.sp,
    )
    SelectionContainer(Modifier.weight(1f)) {
      Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(criterion.id ?: "Not linked to a criterion", color = ink, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 17.sp)
        criterion.checks.forEach { check ->
          Text(
            "${if (check.passed) "✓" else "✗"} ${check.description}",
            color = if (check.passed) muted else ink, fontFamily = body, fontSize = 11.5.sp, lineHeight = 16.sp,
          )
        }
        criterion.findings.forEach { QaCriterionFindingBlock(it) }
      }
    }
  }
}

@Composable
private fun QaCriterionFindingBlock(finding: QaCriterionFinding) {
  Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(finding.id, color = red, fontFamily = mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp)
    if (finding.steps.isNotEmpty()) {
      Text("Steps", color = muted, fontFamily = body, fontSize = 11.sp, lineHeight = 16.sp)
      finding.steps.forEachIndexed { index, step ->
        Text("${index + 1}. $step", Modifier.padding(start = 8.dp), color = ink, fontFamily = body, fontSize = 11.5.sp, lineHeight = 16.sp)
      }
    }
    QaFindingField("Expected", finding.expected)
    QaFindingField("Observed", finding.observed)
  }
}

@Composable
private fun QaFindingField(label: String, value: String) {
  if (value.isBlank()) return
  Text(label, color = muted, fontFamily = body, fontSize = 11.sp, lineHeight = 16.sp)
  Text(value, Modifier.padding(start = 8.dp), color = ink, fontFamily = body, fontSize = 11.5.sp, lineHeight = 16.sp)
}
