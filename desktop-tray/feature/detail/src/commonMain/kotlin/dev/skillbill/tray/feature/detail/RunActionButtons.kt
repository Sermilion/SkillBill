package dev.skillbill.tray.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.DesignButton
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.DatabaseFileIdentity
import dev.skillbill.tray.core.domain.model.IncidentProgress
import dev.skillbill.tray.core.domain.model.IncidentRecoveryPreview
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.eligibleForControl
import dev.skillbill.tray.core.domain.model.WorkIdentity

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RunActionButtons(
  detail: Detail,
  busy: Boolean,
  available: Boolean,
  recoveryConfirmation: Boolean,
  recoveryPreviewLoading: Boolean,
  recoveryPreview: IncidentRecoveryPreview?,
  confirmedPrerequisites: Set<String>,
  refusalMessage: String?,
  recovering: Boolean,
  statusFocus: FocusRequester,
  statusAttached: Boolean,
  onAction: (DetailAction) -> Unit,
) {
  val item = detail.item
  val options = detail.runOptions
  var chooseAgent by remember(item.identity) { mutableStateOf(false) }
  var selectedAgent by remember(item.identity, options?.agentId) { mutableStateOf(options?.agentId) }
  val idle = !busy && available
  val controllable = item.eligibleForControl()
  val canLaunch = options?.canLaunch == true
  val incident = detail.incident
  val focusIdentity = RecoveryFocusIdentity(
    item.identity, item.workflowId, item.invocationId, item.databaseGeneration, item.databaseFile, item.databaseStoreId,
    incident?.incidentToken, incident?.revision,
  )
  val buttonFocus = remember(focusIdentity) { FocusRequester() }
  var recoverAttached by remember(focusIdentity) { mutableStateOf(false) }
  var focusRestoration by remember { mutableStateOf(RecoveryFocusRestorationState()) }
  val prerequisitesReady = incident?.prerequisites.orEmpty().all { it.kind in confirmedPrerequisites }
  val recoveryOffered = incident?.progress == IncidentProgress.RECOVERY_AVAILABLE ||
    (incident?.progress == IncidentProgress.NEEDS_PREREQUISITES && prerequisitesReady)
  LaunchedEffect(recoveryConfirmation, focusIdentity, recoverAttached, recoveryOffered, idle, statusAttached) {
    var state = focusRestoration
    if (recoveryConfirmation) state = state.copy(openedIdentity = focusIdentity)
    if (state.previousConfirmation && !recoveryConfirmation) state = state.copy(restoreFocus = true)
    state = state.copy(previousConfirmation = recoveryConfirmation)
    if (!recoveryConfirmation && state.restoreFocus) {
      if (state.openedIdentity == focusIdentity && recoverAttached && recoveryOffered && idle) {
        buttonFocus.requestFocus()
        state = state.copy(restoreFocus = false, openedIdentity = null)
      } else if (state.openedIdentity != focusIdentity) {
        if (statusAttached) statusFocus.requestFocus()
        state = state.copy(restoreFocus = false, openedIdentity = null)
      } else if (statusAttached) {
        statusFocus.requestFocus()
      }
    }
    focusRestoration = state
  }
  val resumable = item.lifecycle == Lifecycle.CANCELED || item.lifecycle == Lifecycle.PAUSED
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      DesignButton(
        if (item.pauseRequested) "Pause requested" else "Pause after subtask",
        enabled = idle && controllable && item.pauseEligible,
      ) {
        onAction(DetailAction.Pause)
      }
      DesignButton("Pause", enabled = idle && controllable, danger = true) { onAction(DetailAction.Stop) }
      if (resumable) Box {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          DesignButton(if (busy) "Working..." else options?.launchLabel ?: "Resume", enabled = idle && canLaunch) {
            if (selectedAgent == null) chooseAgent = true else onAction(DetailAction.Launch(selectedAgent))
          }
          DesignButton(selectedAgent?.let { "Agent: $it" } ?: "Choose agent", enabled = idle && canLaunch) { chooseAgent = true }
        }
        DropdownMenu(expanded = chooseAgent && canLaunch, onDismissRequest = { chooseAgent = false }) {
          options?.agentChoices.orEmpty().forEach { agent ->
            DropdownMenuItem(text = { Text(agent) }, onClick = {
              chooseAgent = false
              selectedAgent = agent
            })
          }
        }
      }
      DesignButton(if (busy) "Working..." else "Cancel", enabled = idle && options?.canCancel == true, danger = true) {
        onAction(DetailAction.Cancel)
      }
      if (recoveryOffered || recovering || incident?.progress == IncidentProgress.RECOVERING) {
        DesignButton(
          when {
            incident?.progress == IncidentProgress.RECOVERING || recovering -> "Recovering..."
            recoveryPreviewLoading -> "Loading preview..."
            else -> "Recover"
          },
          enabled = idle && recoveryOffered && !recoveryPreviewLoading,
          modifier = Modifier.focusRequester(buttonFocus).onGloballyPositioned { recoverAttached = true },
        ) { onAction(DetailAction.Recover) }
      }
    }
    refusalMessage?.let { Text(it, color = muted, fontSize = 12.sp) }
    options?.unavailableReason?.let { Text(it, color = muted, fontSize = 11.sp) }
    if (canLaunch && resumable) {
      Text("Starts the installed agent harness", color = muted, fontSize = 11.sp)
    }
  }
  if (recoveryConfirmation && recoveryPreview != null) {
    RecoveryConfirmationDialog(recoveryPreview, onAction)
  }
}

@Composable
private fun RecoveryConfirmationDialog(preview: IncidentRecoveryPreview, onAction: (DetailAction) -> Unit) {
  val dialogFocus = remember(preview.incidentToken, preview.incidentRevision) { FocusRequester() }
  var actionAttached by remember(preview.incidentToken, preview.incidentRevision) { mutableStateOf(false) }
  LaunchedEffect(preview.incidentToken, preview.incidentRevision, actionAttached) {
    if (actionAttached) dialogFocus.requestFocus()
  }
  AlertDialog(
    onDismissRequest = { onAction(DetailAction.CancelRecovery) },
    title = { Text(preview.label) },
    text = {
      Text(
        listOfNotNull(
          preview.effects?.let { "Effects: $it" },
          preview.targetWorkflowId?.let { "Target workflow: $it" },
          preview.targetInvocationId?.let { "Target invocation: $it" },
          preview.phaseId?.let { "Phase: $it" },
        ).joinToString("\n"),
      )
    },
    confirmButton = {
      DesignButton("Apply and resume", modifier = Modifier.focusRequester(dialogFocus).onGloballyPositioned { actionAttached = true }) {
        onAction(DetailAction.ConfirmRecovery)
      }
    },
    dismissButton = { DesignButton("Cancel") { onAction(DetailAction.CancelRecovery) } },
  )
}

private data class RecoveryFocusIdentity(
  val workIdentity: WorkIdentity,
  val workflowId: String?,
  val invocationId: String?,
  val databaseGeneration: Long,
  val databaseFile: DatabaseFileIdentity?,
  val databaseStoreId: String?,
  val incidentToken: String?,
  val incidentRevision: Long?,
)

private data class RecoveryFocusRestorationState(
  val previousConfirmation: Boolean = false,
  val restoreFocus: Boolean = false,
  val openedIdentity: RecoveryFocusIdentity? = null,
)
