package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.VerifyCompletenessAudit
import dev.skillbill.desktop.core.domain.model.VerifyFlagAudit
import dev.skillbill.desktop.core.domain.model.VerifyReviewSection

@Composable
internal fun VerifyFlagAuditReceipt(receipt: VerifyFlagAudit) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    receipt.skipReason?.takeIf { it.isNotBlank() }?.let { VerifyReceiptField("Skipped", it) }
    receipt.flagName?.takeIf { it.isNotBlank() }?.let { VerifyReceiptField("Flag", it, mono = true) }
    receipt.pattern?.takeIf { it.isNotBlank() }?.let { VerifyReceiptField("Pattern", it) }
    receipt.issues?.takeIf { it.isNotBlank() }?.let { VerifyReceiptField("Issues", it) }
    receipt.checks.forEach { check ->
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CompositionLocalProvider(LocalContentColor provides if (check.passed) SkillBillTheme.colors.stDone else SkillBillTheme.colors.stFailed) { SbIcon(if (check.passed) LucideIcons.check else LucideIcons.x, 12.dp) }
        BasicText(check.text, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2))
      }
    }
    VerifyRawFindings(receipt.findings)
  }
}

@Composable
internal fun VerifyReviewReceipt(receipt: VerifyReviewSection, selectable: Boolean = false, trailing: @Composable (dev.skillbill.desktop.core.domain.model.VerifyReviewFinding) -> Unit = {}, extra: @Composable (dev.skillbill.desktop.core.domain.model.VerifyReviewFinding) -> Unit = {}, leading: @Composable (dev.skillbill.desktop.core.domain.model.VerifyReviewFinding) -> Unit = {}) {
  receipt.items.forEach { finding -> VerifyFindingRow(finding, selectable, { trailing(finding) }, { extra(finding) }) { leading(finding) } }
  VerifyRawFindings(receipt.findings)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerifyFindingRow(finding: dev.skillbill.desktop.core.domain.model.VerifyReviewFinding, selectable: Boolean = false, trailing: @Composable () -> Unit = {}, extra: @Composable () -> Unit = {}, leading: @Composable () -> Unit = {}) {
  var open by rememberSaveable(finding.ref, finding.location) { mutableStateOf(false) }
  val colors = SkillBillTheme.colors
  Row(Modifier.fillMaxWidth().hairline(Edge.Top, colors.borderSubtle).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    if (selectable) Column(Modifier.width(22.dp)) { leading() }
    Column { VerifyBadge(finding.severity, when (finding.severity.lowercase()) { "blocker", "major" -> colors.stFailed; "minor" -> colors.stBlocked; else -> colors.fgFaint }) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        BasicText(finding.ref, style = SkillBillTheme.type.t2xs.copy(color = colors.fg2))
        VerifyFileChip(finding.location.substringAfterLast('/'), finding.location)
        (finding.specialists + "${finding.confidence} confidence").forEach { BasicText(it, style = SkillBillTheme.type.t2xs.copy(color = colors.fgMuted, fontWeight = FontWeight.Normal)) }
        trailing()
      }
      BasicText(verifyText(finding.description), Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Toggle ${finding.ref}", onClick = { open = !open }), style = SkillBillTheme.type.tSm.copy(color = colors.fg2), maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
      extra()
    }
  }
}

@Composable
internal fun VerifyCompletenessReceipt(receipt: VerifyCompletenessAudit, onScrollToSection: (String) -> Unit) {
  VerifyRawFindings(receipt.findings)
  VerifyTextLink("Show the graded criteria") { onScrollToSection(VERIFY_CRITERIA_KEY) }
}

@Composable
internal fun VerifyReceiptField(label: String, value: String, mono: Boolean = false) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    BasicText(label, Modifier.width(112.dp), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint))
    BasicText(verifyText(value), Modifier.weight(1f), style = (if (mono) SkillBillTheme.type.monoSm else SkillBillTheme.type.tSm).copy(color = SkillBillTheme.colors.fg2))
  }
}

@Composable
internal fun VerifyRawFindings(findings: List<String>) {
  val visibleFindings = findings.filter { it.isNotBlank() }
  if (visibleFindings.isEmpty()) return
  var open by rememberSaveable(findings) { mutableStateOf(false) }
  VerifyFold("Original report", open, { open = !open }, note = "includes unparsed details") {
    Column(Modifier.padding(start = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { visibleFindings.forEach { VerifyReportText(it) } }
  }
}
