package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.domain.model.VerifyTestValueRow
import dev.skillbill.desktop.core.domain.model.VerifyUnitTestValue

@Composable
internal fun VerifyTestValueReceipt(receipt: VerifyUnitTestValue, head: @Composable () -> Unit = {}, selectable: Boolean = false, trailing: @Composable (Int, VerifyTestValueRow) -> Unit = { _, _ -> }, extra: @Composable (Int, VerifyTestValueRow) -> Unit = { _, _ -> }, leading: @Composable (Int, VerifyTestValueRow) -> Unit = { _, _ -> }) {
  val colors = SkillBillTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    receipt.skipReason?.takeIf(String::isNotBlank)?.let { VerifyReceiptField("Skipped", it) }
    receipt.summary?.takeIf(String::isNotBlank)?.let { VerifyReportText(it) }
    if (receipt.rows.isNotEmpty()) Column {
      head()
      receipt.rows.forEachIndexed { index, row -> VerifyTestRow(row, selectable, { trailing(index, row) }, { extra(index, row) }) { leading(index, row) } }
    }
    val plan = listOf("Keep" to receipt.keep, "Rewrite" to receipt.rewrite, "Delete" to receipt.delete, "Add" to receipt.missingCases)
      .filter { (_, values) -> values.any { it.isNotBlank() && !it.trim().equals("None.", true) } }
    if (plan.isNotEmpty()) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText("Plan", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal))
        Spacer(Modifier.weight(1f).height(1.dp).background(colors.borderSubtle))
      }
      plan.forEach { (title, values) -> VerifyTestPlanGroup(title, values) }
    }
    VerifyRawFindings(receipt.findings)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerifyTestRow(row: VerifyTestValueRow, selectable: Boolean = false, trailing: @Composable () -> Unit = {}, extra: @Composable () -> Unit = {}, leading: @Composable () -> Unit = {}) {
  val colors = SkillBillTheme.colors
  val tone = when (row.verdict.lowercase()) {
    "valuable" -> colors.stDone
    "weak" -> colors.stBlocked
    "harmful" -> colors.stFailed
    else -> colors.fgFaint
  }
  Row(Modifier.fillMaxWidth().hairline(Edge.Top, colors.borderSubtle).padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    if (selectable) Column(Modifier.width(22.dp)) { leading() }
    Column(Modifier.width(74.dp)) { VerifyBadge(row.verdict, tone) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        BasicText(verifyText(row.area), style = SkillBillTheme.type.tBaseMedium.copy(color = colors.fg))
        val files = (row.files + row.locations).map { it.trim().trim('`', '\'') }.filter(String::isNotBlank).distinct()
        if (files.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          files.take(2).forEach { VerifyFileChip(it) }
          if (files.size > 2) SbTooltip(files.drop(2).joinToString("\n")) {
            BasicText("+${files.size - 2}", style = SkillBillTheme.type.t2xs.copy(color = colors.fgFaint))
          }
        }
        trailing()
      }
      if (row.why.isNotBlank()) BasicText(verifyText(row.why), style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted, fontWeight = FontWeight.Normal))
      if (row.action.isNotBlank()) BasicText(verifyText("→ ${row.action}"), style = SkillBillTheme.type.tXs.copy(color = colors.fg2, fontWeight = FontWeight.Normal))
      extra()
    }
  }
}

@Composable
private fun VerifyTestPlanGroup(title: String, values: List<String>) {
  val colors = SkillBillTheme.colors
  val tone = when (title) { "Keep" -> colors.stDone; "Rewrite" -> colors.stBlocked; "Delete" -> colors.stFailed; else -> colors.accentText }
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Column(Modifier.width(74.dp)) { VerifyBadge(title, tone) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      values.filter { it.isNotBlank() && !it.trim().equals("None.", true) }.forEach { text ->
        VerifyReportText(text)
      }
    }
  }
}
