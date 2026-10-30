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
import dev.skillbill.desktop.core.designsystem.component.Callout
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.FindingRow
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
  modifier: Modifier = Modifier,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val labelColor = incidentLabelColor(incident)
  Section(title = "Incident", modifier = modifier, actions = { SbLabel(incident.progress.label(), SbLabelStyle.Dot(labelColor)) }) {
    // The callout body (screens.js incidentPanel): an `.l-col g-1` of faint-led Cause and Next lines and the `--t-xs`
    // certainty line, then the prerequisite checklist 8 below it, inside the callout.
    val bodyStyle = type.tSm.copy(color = colors.fgMuted, lineHeight = 1.5.em)
    val checklist = view.checklist
    Callout(incident.tone(), incident.progress.label(), modifier = Modifier.fillMaxWidth()) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(
          buildAnnotatedString {
            withStyle(SpanStyle(color = colors.fgFaint)) { append("Cause:") }
            append(" ${incident.cause ?: "Unknown"}")
          },
          style = bodyStyle,
        )
        BasicText(
          buildAnnotatedString {
            withStyle(SpanStyle(color = colors.fgFaint)) { append("Next:") }
            append(" ${incident.nextAction}")
          },
          style = bodyStyle,
        )
        BasicText(
          "certainty ${incident.certainty}" + (unavailableText(incident)?.let { " · recovery unavailable: $it" } ?: ""),
          style = bodyStyle.copy(color = colors.fgFaint, fontSize = type.tXs.fontSize),
        )
      }
      // `.sb-checklist`: rows 6 apart, checkbox and text 10 apart, top-aligned with the box 2 down.
      if (checklist != null || incident.prerequisites.isNotEmpty()) {
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
            incident.prerequisites.forEach { BasicText(it.instruction, style = rowStyle) }
          }
        }
      }
    }
    decision()
    if (incident.evidence.isNotEmpty()) {
      // `.l-col g-2`: the faint `--t-xs` caption, then the evidence rows in one box.
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText("Evidence", style = type.tXs.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal))
        SbBox {
          incident.evidence.forEachIndexed { index, evidence ->
            FindingRow(title = evidence.summary, ref = evidence.referenceId, meta = evidence.kind, showDivider = index > 0)
          }
        }
      }
    }
    ReportRow(incident)
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
