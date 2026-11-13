package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.ResultRowFileChip
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
        VerifyReportText(check.text, Modifier.weight(1f))
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
internal fun VerifyFindingRow(
  finding: dev.skillbill.desktop.core.domain.model.VerifyReviewFinding,
  selectable: Boolean = false,
  trailing: @Composable () -> Unit = {},
  extra: @Composable () -> Unit = {},
  leading: @Composable () -> Unit = {},
) {
  var open by rememberSaveable(finding.ref, finding.location) { mutableStateOf(false) }
  val onToggle = remember { { open = !open } }
  val colors = SkillBillTheme.colors
  Row(Modifier.fillMaxWidth().hairline(Edge.Top, colors.borderSubtle).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    if (selectable) Column(Modifier.width(22.dp)) { leading() }
    VerifyBadge(
      finding.severity,
      when (finding.severity.lowercase()) {
        "blocker", "major" -> colors.stFailed
        "minor" -> colors.stBlocked
        else -> colors.fgFaint
      },
    )
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
      ) {
        BasicText(finding.ref, style = SkillBillTheme.type.t2xs.copy(color = colors.fg2))
        ResultRowFileChip(finding.location.substringAfterLast('/'), finding.location)
        (finding.specialists + listOfNotNull(finding.confidence?.let { "$it confidence" })).forEach {
          BasicText(it, style = SkillBillTheme.type.t2xs.copy(color = colors.fgMuted, fontWeight = FontWeight.Normal))
        }
        trailing()
      }
      Column(
        Modifier
          .fillMaxWidth()
          .clickable(role = Role.Button, onClickLabel = "Toggle ${finding.ref}", onClick = onToggle)
          .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
      ) {
        if (open) VerifyReportText(finding.description)
        else {
          BasicText(
            verifyText(finding.description),
            style = SkillBillTheme.type.tSm.copy(color = colors.fg2),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        VerifyChevron(open)
      }
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
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val labelContent: @Composable () -> Unit = { BasicText(label, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint)) }
    val valueContent: @Composable () -> Unit = {
      if (mono) BasicText(value, style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fg2))
      else VerifyReportText(value)
    }
    if (maxWidth < 400.dp) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      labelContent()
      valueContent()
    } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(Modifier.width(112.dp)) { labelContent() }
      Column(Modifier.weight(1f)) { valueContent() }
    }
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
