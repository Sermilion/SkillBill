package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.rememberRuntimeText
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.domain.model.VerifyCompletenessAudit
import dev.skillbill.desktop.core.domain.model.VerifyFlagAudit
import dev.skillbill.desktop.core.domain.model.VerifyReviewSection
import dev.skillbill.desktop.core.domain.model.VerifyTestValueRow
import dev.skillbill.desktop.core.domain.model.VerifyUnitTestValue

@Composable
internal fun VerifyFlagAuditReceipt(receipt: VerifyFlagAudit) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    receipt.skipReason?.takeIf { it.isNotBlank() }?.let { VerifyReceiptField("Skipped", it) }
    receipt.flagName?.takeIf { it.isNotBlank() }?.let { VerifyReceiptField("Flag", it) }
    receipt.pattern?.takeIf { it.isNotBlank() }?.let { VerifyReceiptField("Pattern", it) }
    receipt.issues?.takeIf { it.isNotBlank() }?.let { VerifyReceiptField("Issues", it) }
    receipt.checks.forEach { check ->
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SbLabel(if (check.passed) "PASS" else "FAIL", SbLabelStyle.Tint(if (check.passed) SkillBillTheme.colors.stDone else SkillBillTheme.colors.stFailed))
        BasicText(check.text, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2))
      }
    }
    VerifyRawFindings(receipt.findings)
  }
}

@Composable
internal fun VerifyReviewReceipt(receipt: VerifyReviewSection, leading: @Composable (dev.skillbill.desktop.core.domain.model.VerifyReviewFinding) -> Unit = {}) {
  if (receipt.items.isNotEmpty()) receipt.items.forEach { finding -> VerifyFindingRow(finding) { leading(finding) } }
  VerifyRawFindings(receipt.findings)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerifyFindingRow(finding: dev.skillbill.desktop.core.domain.model.VerifyReviewFinding, leading: @Composable () -> Unit = {}) {
  var open by rememberSaveable(finding.ref, finding.location) { mutableStateOf(false) }
  Column(Modifier.fillMaxWidth().hairline(Edge.Top, SkillBillTheme.colors.borderSubtle).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    leading()
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Toggle ${finding.ref}", onClick = { open = !open }), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SbLabel(finding.severity, SbLabelStyle.Tint(when (finding.severity.lowercase()) {
          "blocker", "major" -> SkillBillTheme.colors.stFailed
          "minor" -> SkillBillTheme.colors.stBlocked
          else -> SkillBillTheme.colors.fgMuted
        }))
        BasicText(finding.ref, style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fg))
        SbTooltip(finding.location) { SbLabel(finding.location.substringAfterLast('/')) }
        BasicText(if (open) "⌄" else "›", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint))
      }
      BasicText(rememberRuntimeText(finding.description), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2), maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
      if (open) BasicText((finding.specialists + "${finding.confidence} confidence").joinToString(" · "), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted))
    }
  }
}

@Composable
internal fun VerifyTestValueReceipt(receipt: VerifyUnitTestValue, leading: @Composable (Int, VerifyTestValueRow) -> Unit = { _, _ -> }) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    receipt.skipReason?.takeIf { it.isNotBlank() }?.let { VerifyReceiptField("Skipped", it) }
    receipt.overall?.takeIf { it.isNotBlank() }?.let { VerifyReceiptField("Overall", it) }
    receipt.summary?.takeIf { it.isNotBlank() }?.let { VerifyReceiptField("Summary", it) }
    receipt.rows.forEachIndexed { index, row -> VerifyTestRow(row) { leading(index, row) } }
    VerifyTestPlanGroup("Keep", receipt.keep)
    VerifyTestPlanGroup("Rewrite", receipt.rewrite)
    VerifyTestPlanGroup("Delete", receipt.delete)
    VerifyTestPlanGroup("Add (missing cases)", receipt.missingCases)
    VerifyRawFindings(receipt.findings)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerifyTestRow(row: VerifyTestValueRow, leading: @Composable () -> Unit = {}) {
  Column(Modifier.fillMaxWidth().hairline(Edge.Top, SkillBillTheme.colors.borderSubtle).padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    leading()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      SbLabel(row.verdict, SbLabelStyle.Tint(when (row.verdict.lowercase()) {
        "valuable" -> SkillBillTheme.colors.stDone
        "weak" -> SkillBillTheme.colors.stBlocked
        "harmful" -> SkillBillTheme.colors.stFailed
        else -> SkillBillTheme.colors.fgMuted
      }))
      BasicText(row.area, style = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fg))
      BasicText(row.confidence, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted))
    }
    if (row.files.isNotEmpty()) BasicText(row.files.joinToString(", "), style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fgMuted))
    if (row.locations.isNotEmpty()) BasicText(row.locations.joinToString(", "), style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fgMuted))
    BasicText(rememberRuntimeText(row.why), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2))
    BasicText(rememberRuntimeText(row.action), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
  }
}

@Composable
internal fun VerifyCompletenessReceipt(receipt: VerifyCompletenessAudit, onScrollToSection: (String) -> Unit) {
  receipt.verdict?.takeIf { it.isNotBlank() }?.let { VerifyReceiptField("Verdict", it) }
  VerifyRawFindings(receipt.findings)
  BasicText(
    "View criteria",
    Modifier.clickable(role = Role.Button, onClickLabel = "View criteria", onClick = { onScrollToSection(VERIFY_CRITERIA_KEY) }).padding(vertical = 4.dp),
    style = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.stActive),
  )
}

@Composable
private fun VerifyTestPlanGroup(title: String, values: List<String>) {
  if (values.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
    BasicText(title, style = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fg))
    values.forEach { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2)) }
  }
}

@Composable
private fun VerifyReceiptField(label: String, value: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    BasicText("$label:", style = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fgMuted))
    BasicText(value, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2))
  }
}

@Composable
private fun VerifyRawFindings(findings: List<String>) {
  val visibleFindings = findings.filter { it.isNotBlank() }
  visibleFindings.forEach { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2)) }
}
