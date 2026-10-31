package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.rememberRuntimeText
import dev.skillbill.desktop.core.designsystem.component.Callout
import dev.skillbill.desktop.core.designsystem.component.ExpandableText
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbCheckbox
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.designsystem.detail.Section
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.Incident
import dev.skillbill.desktop.core.domain.model.IncidentProgress
import dev.skillbill.desktop.core.domain.model.IncidentRecoveryEligibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.detail.SectionCollapse
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.IncidentEvidence
import dev.skillbill.desktop.core.designsystem.component.ShortId

/**
 * HANDOFF 9.2 item 7: the current incident, its prerequisites, the [decision] slot, evidence and report, as screens.js
 * `incidentPanel` lays them out. The caller adds it only when there is an incident.
 */
@Composable
internal fun IncidentSection(
  incident: Incident,
  view: RunActionsView,
  decision: @Composable () -> Unit,
  onAction: (ControlsAction) -> Unit,
  collapse: SectionCollapse,
  onCopy: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val labelColor = incidentLabelColor(incident)
  Section(
    title = "Incident",
    modifier = modifier,
    actions = { SbLabel(incident.progress.label(), SbLabelStyle.Dot(labelColor)) },
    collapsed = collapse.isCollapsed(INCIDENT_KEY),
    onToggle = { collapse.toggle(INCIDENT_KEY) },
  ) {
    // The callout body (screens.js incidentPanel): an `.l-col g-1` of faint-led Cause and Next lines and the `--t-xs`
    // certainty line, then the prerequisite checklist 8 below it, inside the callout.
    val bodyStyle = type.tSm.copy(color = colors.fgMuted, lineHeight = 1.5.em)
    val checklist = view.checklist
    Callout(incident.tone(), incident.progress.label(), modifier = Modifier.fillMaxWidth()) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ExpandableText(
          buildAnnotatedString {
            withStyle(SpanStyle(color = colors.fgFaint)) { append("Cause:") }
            append(" ${incident.cause ?: "Unknown"}")
          },
          bodyStyle,
        )
        ExpandableText(
          buildAnnotatedString {
            withStyle(SpanStyle(color = colors.fgFaint)) { append("Next:") }
            append(" ${incident.nextAction}")
          },
          bodyStyle,
        )
        BasicText(
          "certainty ${incident.certainty}" + (unavailableText(incident)?.let { " · recovery unavailable: $it" } ?: ""),
          style = bodyStyle.copy(color = colors.fgFaint, fontSize = type.tXs.fontSize),
        )
      }
      // `.sb-checklist`: rows 6 apart, checkbox and text 10 apart, top-aligned with the box 2 down.
      // screens.js drops the prerequisites once recovery runs.
      val recovered = incident.progress == IncidentProgress.Recovering || incident.progress == IncidentProgress.Resumed
      if (checklist != null || (incident.prerequisites.isNotEmpty() && !recovered)) {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          val rowStyle = type.tSm.copy(color = colors.fg2, lineHeight = 1.45.em)
          if (checklist != null) {
            checklist.forEach { item ->
              Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                SbCheckbox(item.checked, { onAction(ControlsAction.ConfirmPrerequisite(item.id, it)) }, item.text, enabled = view.checklistEnabled, modifier = Modifier.padding(top = 2.dp))
                BasicText(item.text, Modifier.weight(1f), style = rowStyle)
              }
            }
          } else {
            incident.prerequisites.forEach { BasicText(rememberRuntimeText(it.instruction), style = rowStyle) }
          }
        }
      }
    }
    decision()
    if (incident.evidence.isNotEmpty()) {
      // Evidence starts folded: its rows are long runtime records, read only when the summary above isn't enough.
      val folded = collapse.isCollapsed(EVIDENCE_KEY, default = true)
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EvidenceToggle(incident.evidence.size, folded) { collapse.toggle(EVIDENCE_KEY, default = true) }
        if (!folded) {
          SbBox {
            incident.evidence.forEachIndexed { index, evidence -> EvidenceRow(evidence, showDivider = index > 0, onCopy = onCopy) }
          }
        }
      }
    }
    ReportRow(incident)
  }
}

private const val INCIDENT_KEY = "incident"
private const val EVIDENCE_KEY = "incident-evidence"

/** The faint `--t-xs` Evidence caption as a toggle: caption, count and a chevron. */
@Composable
private fun EvidenceToggle(count: Int, folded: Boolean, onToggle: () -> Unit) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val color = if (hovered) colors.fgMuted else colors.fgFaint
  Row(
    Modifier.sbFocusRing(interaction, RoundedCornerShape(SkillBillRadius.xs))
      .pointerHoverIcon(PointerIcon.Hand)
      .clickable(interaction, indication = null, role = Role.Button, onClick = onToggle)
      .semantics { stateDescription = if (folded) "Collapsed" else "Expanded" },
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText("Evidence · $count", style = SkillBillTheme.type.tXs.copy(color = color, fontWeight = FontWeight.Normal, lineHeight = 1.em))
    CompositionLocalProvider(LocalContentColor provides color) {
      SbIcon(if (folded) LucideIcons.chevronRight else LucideIcons.chevronDown, 12.dp)
    }
  }
}

/**
 * One evidence record, stacked so a long reference never squeezes the text: the reference shortened on its own line (its kind
 * prefix dropped, since the kind shows below; full value in the tooltip and the copy button), the summary at full width, then
 * the kind.
 */
@Composable
private fun EvidenceRow(evidence: IncidentEvidence, showDivider: Boolean, onCopy: (String) -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val divider = colors.borderSubtle
  Column(
    Modifier.fillMaxWidth()
      .drawBehind {
        if (showDivider) {
          val stroke = 1.dp.toPx()
          drawLine(divider, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
        }
      }
      .padding(start = 12.dp, end = 12.dp, top = if (showDivider) 10.dp else 9.dp, bottom = 9.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    ShortId(evidence.referenceId, onCopy, label = "reference", shown = evidence.referenceId.removePrefix("${evidence.kind}:"))
    ExpandableText(evidence.summary, type.tSm.copy(color = colors.fg2, lineHeight = 1.45.em))
    BasicText(evidence.kind, style = type.tXs.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal, lineHeight = 1.45.em))
  }
}

/** `.l-row g-2 l-wrap t-muted` at `--t-sm`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportRow(incident: Incident) {
  val uriHandler = LocalUriHandler.current
  val report = incident.report
  val detail = report.failureReason?.let { " ${reportFailureText(it)}" }.orEmpty()
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
    BasicText("Report: ${report.status}$detail", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
    report.issueUrl?.let { url ->
      SbButton("Open issue", { uriHandler.openUri(url) }, variant = SbButtonVariant.Ghost, size = SbControlSize.Small, icon = LucideIcons.external)
    }
  }
}

/** The design's rule: blue (info) while recovery is on offer or running, orange (warn) otherwise. */
private fun Incident.tone(): FeedbackTone = when {
  progress == IncidentProgress.Recovering || progress == IncidentProgress.Resumed -> FeedbackTone.Info
  progress == IncidentProgress.RecoveryAvailable || progress == IncidentProgress.NeedsPrerequisites -> FeedbackTone.Info
  recovery.eligibility is IncidentRecoveryEligibility.Available || recovery.eligibility is IncidentRecoveryEligibility.NeedsPrerequisites -> FeedbackTone.Info
  else -> FeedbackTone.Warn
}

/** The header label's dot: active while recovering, planning blue for the info tone, blocked orange otherwise. */
@Composable
private fun incidentLabelColor(incident: Incident): Color {
  val colors = SkillBillTheme.colors
  return when {
    incident.progress == IncidentProgress.Recovering -> colors.stActive
    incident.tone() == FeedbackTone.Info -> colors.stPlanning
    else -> colors.stBlocked
  }
}

/** "operator decision" for `OperatorDecision`, as the design lowercases and spaces the runtime's reason. */
private fun unavailableText(incident: Incident): String? =
  (incident.recovery.eligibility as? IncidentRecoveryEligibility.Unavailable)?.reason?.name
    ?.replace(Regex("([a-z])([A-Z])"), "$1 $2")?.lowercase()

/** `IncidentPanel.kt`'s report failure wording. */
private fun reportFailureText(value: String): String = when (value) {
  "missing_auth" -> "Not submitted: GitHub credentials not found"
  "offline" -> "Not submitted: network unavailable"
  "permission_refused" -> "Not submitted: GitHub permission refused"
  "rate_limited" -> "Not submitted: GitHub rate limited the request"
  "submission_failed" -> "Not submitted: issue creation failed"
  "residual_secret" -> "Not submitted: report contains a secret"
  "retries_exhausted" -> "Not submitted: retries exhausted"
  else -> value
}
