package dev.skillbill.tray.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import dev.skillbill.tray.core.designsystem.DesignButton
import dev.skillbill.tray.core.designsystem.DesignLabel
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.designsystem.orange
import dev.skillbill.tray.core.domain.model.Incident
import dev.skillbill.tray.core.domain.model.IncidentProgress

@Composable
internal fun IncidentPanel(
  incident: Incident?,
  history: List<Incident>,
  confirmedPrerequisites: Set<String>,
  prerequisitesEnabled: Boolean,
  onAction: (DetailAction) -> Unit,
  copy: (String) -> Unit,
) {
  val uriHandler = LocalUriHandler.current
  Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
    incident?.let { current ->
      val label = current.progress.label()
      DesignLabel(label, color = when (current.progress) {
        IncidentProgress.DIAGNOSIS_FAILED, IncidentProgress.NEEDS_PREREQUISITES,
        IncidentProgress.RECOVERY_FAILED, IncidentProgress.DIAGNOSIS_PARTIAL,
        IncidentProgress.RECOVERY_AVAILABLE -> orange
        IncidentProgress.INVESTIGATION_PENDING, IncidentProgress.INVESTIGATING,
        IncidentProgress.RECOVERING, IncidentProgress.RESUMED -> muted
        IncidentProgress.DIAGNOSIS_COMPLETE -> ink
      })
      Text("Cause: ${current.cause ?: "Unknown"} (${current.certainty})", color = ink, fontSize = 12.sp)
      Text("Next: ${current.nextAction}", color = ink, fontSize = 12.sp)
      current.prerequisites.forEach { prerequisite ->
        if (current.progress == IncidentProgress.NEEDS_PREREQUISITES) {
          Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Checkbox(
              checked = prerequisite.kind in confirmedPrerequisites,
              enabled = prerequisitesEnabled,
              onCheckedChange = { onAction(DetailAction.ConfirmPrerequisite(prerequisite.kind, it)) },
              modifier = Modifier.semantics { contentDescription = prerequisite.instruction },
            )
            Text(prerequisite.instruction, color = muted, fontSize = 11.sp)
          }
        } else {
          Text(prerequisite.instruction, color = muted, fontSize = 11.sp)
        }
      }
      var evidenceExpanded by remember(current.incidentToken) { mutableStateOf(false) }
      DesignButton(
        if (evidenceExpanded) "Evidence expanded" else "Evidence collapsed",
        modifier = Modifier.semantics {
          role = Role.Button
          stateDescription = if (evidenceExpanded) "Expanded" else "Collapsed"
          contentDescription = "Evidence"
        },
      ) { evidenceExpanded = !evidenceExpanded }
      if (evidenceExpanded) {
        current.evidence.forEach { evidence ->
          Text(
            "${evidence.summary}${if (evidence.trusted) " · trusted" else " · untrusted"}",
            color = muted,
            fontSize = 11.sp,
          )
          DesignButton("Copy ${evidence.referenceId}") { copy(evidence.referenceId) }
        }
        current.telemetry.searchIdentifiers.values.forEach { identifier ->
          DesignButton("Copy $identifier") { copy(identifier) }
        }
      }
      val issue = current.report.issueUrl
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val reportDetail = current.report.failureReason?.let { " ${reportFailureText(it)}" }.orEmpty()
        Text(
          "Report: ${current.report.status}$reportDetail",
          color = muted,
          fontSize = 11.sp,
        )
        if (issue != null) {
          Text("Open issue", Modifier.clickable { uriHandler.openUri(issue) }, color = ink, fontSize = 11.sp)
        }
      }
    }
    if (history.isNotEmpty()) {
      DesignLabel("History")
      history.forEach { past ->
        Text(
          "${past.progress.label()} · ${past.cause ?: past.summary} · ${past.observedAt}",
          color = muted,
          fontSize = 11.sp,
        )
      }
    }
  }
}

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

private fun IncidentProgress.label(): String = when (this) {
  IncidentProgress.INVESTIGATION_PENDING -> "Investigation queued"
  IncidentProgress.INVESTIGATING -> "Investigating block"
  IncidentProgress.DIAGNOSIS_COMPLETE -> "Diagnosis ready"
  IncidentProgress.DIAGNOSIS_PARTIAL -> "Partial diagnosis"
  IncidentProgress.DIAGNOSIS_FAILED -> "Diagnosis unavailable"
  IncidentProgress.NEEDS_PREREQUISITES -> "Needs your action"
  IncidentProgress.RECOVERY_AVAILABLE -> "Recovery available"
  IncidentProgress.RECOVERING -> "Recovering"
  IncidentProgress.RECOVERY_FAILED -> "Recovery failed"
  IncidentProgress.RESUMED -> "Resumed"
}
