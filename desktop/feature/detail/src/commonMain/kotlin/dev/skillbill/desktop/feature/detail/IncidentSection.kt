package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Callout
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.FindingRow
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbCheckbox
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.detail.Section
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.domain.model.Incident
import dev.skillbill.desktop.core.domain.model.IncidentProgress

/**
 * HANDOFF 9.2 item 7: the current incident, its prerequisites, the [decision] slot, evidence and report, then the past incidents
 * as `IncidentPanel.kt` lists them. The caller adds it only when there is either.
 */
@Composable
internal fun IncidentSection(
  incident: Incident?,
  history: List<Incident>,
  view: RunActionsView,
  decision: @Composable () -> Unit,
  onAction: (ControlsAction) -> Unit,
  onCopy: (String) -> Unit,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Section(title = "Incident", actions = incident?.let { { SbLabel(it.progress.label()) } }) {
    if (incident != null) {
      val body = buildAnnotatedString {
        append("Cause: ${incident.cause ?: "Unknown"}\n")
        append("Next: ${incident.nextAction}\n")
        withStyle(SpanStyle(color = colors.fgFaint)) { append("certainty ${incident.certainty}") }
      }
      Callout(incident.progress.tone(), incident.progress.label(), body)
      val checklist = view.checklist
      if (checklist != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          checklist.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
              SbCheckbox(item.checked, { onAction(ControlsAction.ConfirmPrerequisite(item.id, it)) }, item.text, enabled = view.checklistEnabled)
              BasicText(item.text, style = type.tSm.copy(color = colors.fg2))
            }
          }
        }
      } else {
        incident.prerequisites.forEach { BasicText(it.instruction, style = type.tSm.copy(color = colors.fg2)) }
      }
      decision()
      if (incident.evidence.isNotEmpty()) {
        BasicText("Evidence", style = type.tSmMedium.copy(color = colors.fgMuted))
        Column {
          incident.evidence.forEachIndexed { index, evidence ->
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(Modifier.weight(1f)) {
                FindingRow(
                  title = evidence.summary,
                  ref = evidence.referenceId,
                  meta = "${evidence.kind} · ${if (evidence.trusted) "trusted" else "untrusted"}",
                  showDivider = index > 0,
                )
              }
              SbButton("Copy ${evidence.referenceId}", { onCopy(evidence.referenceId) }, variant = SbButtonVariant.Ghost, size = SbControlSize.Small, icon = LucideIcons.copy)
            }
          }
        }
      }
      ReportRow(incident)
    }
    if (history.isNotEmpty()) IncidentHistory(history)
  }
}

@Composable
private fun IncidentHistory(history: List<Incident>) {
  val expanded = remember(history) { mutableStateOf(false) }
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(
    Modifier.fillMaxWidth()
      .clickable(role = Role.Button) { expanded.value = !expanded.value }
      .semantics { stateDescription = if (expanded.value) "Expanded" else "Collapsed" }
      .padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText("History", Modifier.semantics { heading() }, style = type.tSmMedium.copy(color = colors.fgMuted))
    BasicText(history.size.toString(), Modifier.padding(start = 6.dp), style = type.tSm.copy(color = colors.fgFaint))
    Spacer(Modifier.weight(1f))
    SbIcon(if (expanded.value) LucideIcons.chevronDown else LucideIcons.chevronRight, SbIconSize.Xs)
  }
  if (expanded.value) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      history.forEach { past ->
        BasicText("${past.progress.label()} · ${past.cause ?: past.summary} · ${past.observedAt}", style = type.tSm.copy(color = colors.fgMuted))
      }
    }
  }
}

@Composable
private fun ReportRow(incident: Incident) {
  val uriHandler = LocalUriHandler.current
  val report = incident.report
  val detail = report.failureReason?.let { " ${reportFailureText(it)}" }.orEmpty()
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText("Report: ${report.status}$detail", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
    report.issueUrl?.let { url ->
      SbButton("Open issue", { uriHandler.openUri(url) }, variant = SbButtonVariant.Ghost, size = SbControlSize.Small, icon = LucideIcons.external)
    }
  }
}

private fun IncidentProgress.tone(): FeedbackTone = when (this) {
  IncidentProgress.RecoveryAvailable, IncidentProgress.Recovering, IncidentProgress.Resumed -> FeedbackTone.Info
  IncidentProgress.InvestigationPending, IncidentProgress.Investigating -> FeedbackTone.Neutral
  IncidentProgress.DiagnosisComplete, IncidentProgress.DiagnosisPartial, IncidentProgress.DiagnosisFailed,
  IncidentProgress.NeedsPrerequisites, IncidentProgress.RecoveryFailed,
  -> FeedbackTone.Warn
}

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
