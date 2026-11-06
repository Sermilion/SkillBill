package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.designsystem.component.ChecklistItem
import dev.skillbill.desktop.core.domain.model.Incident
import dev.skillbill.desktop.core.domain.model.IncidentPrerequisite
import dev.skillbill.desktop.core.domain.model.IncidentProgress
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkKind

internal const val REFRESH_BEFORE_CONTROLS = "Refresh the detail before using run controls."
internal const val WORKING = "Working…"

private val RECOVER_ALLOWED = setOf(IncidentProgress.RecoveryAvailable, IncidentProgress.NeedsPrerequisites)

internal data class ControlButton(val label: String, val enabled: Boolean, val tooltip: String? = null)

/**
 * What the run actions, the prerequisite checklist and the decision block show, derived from one detail state. A null button is
 * absent. [agentId] is the chosen agent, else the run options' default. [checklist] is null when the prerequisites render as
 * plain rows. [decision] is null when no decision is awaited.
 */
internal data class RunActionsView(
  val usable: Boolean,
  val refreshNeeded: Boolean,
  val pauseAfter: ControlButton?,
  val pauseNow: ControlButton?,
  val launch: ControlButton?,
  val agent: ControlButton?,
  val agentId: String?,
  val cancel: ControlButton?,
  val recover: ControlButton?,
  val unavailableReason: String?,
  val launchNote: Boolean,
  val pausePending: Boolean,
  val confirmed: Set<IncidentPrerequisite>,
  val checklist: List<ChecklistItem>?,
  val checklistEnabled: Boolean,
  val decision: OperatorDecisionNeeded?,
  val implement: ControlButton? = null,
  val implementationNote: Boolean = false,
)

/** HANDOFF 9.2 items 4, 5 and 7 as rules D-A to D-F: presence, labels and enablement only; nothing is sorted or timed. */
internal fun runActionsView(state: DetailUiState): RunActionsView {
  val item = state.header
  val controls = state.controls
  val loaded = state.sections as? SectionsUi.Loaded
  val usable = loaded != null && !state.stale
  val idle = controls.inFlight == null
  val ready = usable && idle
  val options = loaded?.runOptions
  val incident = loaded?.detail?.incident
  fun label(control: InFlightControl, label: String) = if (controls.inFlight == control) WORKING else label

  val goal = item.kind == WorkKind.Goal
  val pauseAfter = ControlButton(
    label(InFlightControl.PauseAfterSubtask, if (item.pauseRequested) "Pause requested" else "Pause after subtask"),
    ready && item.pauseEligible && !item.pauseRequested,
  ).takeIf { goal }
  val pauseNow = ControlButton(label(InFlightControl.PauseNow, "Pause"), ready && item.controlEligible, "Stops the goal runner now").takeIf { goal }

  val resumable = item.lifecycle != Lifecycle.Active &&
    (item.lifecycle != Lifecycle.Terminal || isStandaloneValidation(item))
  val launchable = ready && options?.canLaunch == true && (item.issueKey ?: item.id) !in controls.reservations
  val agentId = controls.chosenAgent ?: options?.agentId
  val launch = ControlButton(label(InFlightControl.Launch, options?.launchLabel?.ifBlank { null } ?: "Resume"), launchable).takeIf { resumable }
  val agent = ControlButton(agentId?.let { "Agent: $it" } ?: "Choose agent", launchable).takeIf { resumable }

  val confirmed = confirmedPrerequisites(state, incident)
  val allConfirmed = incident != null && incident.prerequisites.all { prerequisiteOf(it.kind) in confirmed }
  val progress = incident?.progress
  val recovering = controls.inFlight == InFlightControl.Recover
  val previewing = controls.inFlight == InFlightControl.RecoveryPreview
  val recover = ControlButton(
    label = when {
      recovering -> "Recovering…"
      previewing -> "Loading preview…"
      else -> "Recover"
    },
    enabled = ready && progress in RECOVER_ALLOWED && allConfirmed,
    tooltip = "Confirm the prerequisites first".takeIf { !allConfirmed },
  ).takeIf { progress in RECOVER_ALLOWED || recovering || previewing }

  val checklist = incident?.takeIf { it.progress in RECOVER_ALLOWED && it.prerequisites.isNotEmpty() }?.prerequisites
    ?.map { ChecklistItem(id = it.kind, text = it.instruction, checked = prerequisiteOf(it.kind) in confirmed) }

  val decision = if (item.awaitsOperatorDecision) operatorDecisionNeeded(item.issueKey, item.currentSubtaskId) else null

  return RunActionsView(
    usable = usable,
    refreshNeeded = (loaded != null && (state.stale || options == null)) || state.sections is SectionsUi.Failed,
    pauseAfter = pauseAfter,
    pauseNow = pauseNow,
    launch = launch,
    agent = agent,
    agentId = agentId,
    cancel = ControlButton(label(InFlightControl.Cancel, "Cancel"), ready && options?.canCancel == true).takeIf { item.lifecycle != Lifecycle.Terminal && item.lifecycle != Lifecycle.Canceled },
    recover = recover,
    unavailableReason = options?.unavailableReason,
    launchNote = launch != null && options?.canLaunch == true,
    pausePending = item.pauseRequested,
    confirmed = confirmed,
    checklist = checklist,
    checklistEnabled = ready,
    decision = decision,
    implement = ControlButton(
      label(InFlightControl.Launch, "Implement"),
      ready && !controls.implementationStarted && planImplementationEnabled(item, loaded.detail) && item.issueKey !in controls.reservations,
    ).takeIf { item.offersPlanImplementation() },
    implementationNote = item.offersPlanImplementation() && !planFinished(item, loaded?.detail),
  )
}

/** The wire kind `credentials_configured` names `CREDENTIALS_CONFIGURED`; an unknown kind maps to null and is never confirmed. */
internal fun prerequisiteOf(kind: String): IncidentPrerequisite? = IncidentPrerequisite.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) }

/** The confirmation for [incident], or none when the item, incident token or listed kinds changed since it was made (D-D). */
internal fun confirmedPrerequisites(state: DetailUiState, incident: Incident?): Set<IncidentPrerequisite> {
  val confirmation = state.controls.prerequisites ?: return emptySet()
  if (incident == null) return emptySet()
  val matches = confirmation.itemId == state.header.id && confirmation.incidentToken == incident.incidentToken &&
    confirmation.listedKinds == incident.prerequisites.map { it.kind }.toSet()
  return if (matches) confirmation.confirmed else emptySet()
}

/** What a no-change pause asks of the operator: [text] explains it and [command] records the decision from a shell. */
internal data class OperatorDecisionNeeded(val text: String, val command: String)

/** A missing issue key or subtask shows as a placeholder in the command rather than hiding the notice. */
internal fun operatorDecisionNeeded(issueKey: String?, subtaskId: String?): OperatorDecisionNeeded {
  val subject = subtaskId?.let { "Subtask $it" } ?: "The current subtask"
  return OperatorDecisionNeeded(
    text = "$subject finished with no code changes and is waiting for your decision. Settle it in chat, or run this " +
      "command and then resume the goal. Pass --instructions with retry_fix.",
    command = "skill-bill goal operator-decision ${issueKey ?: "<issue-key>"} --subtask ${subtaskId ?: "<subtask>"} " +
      "--decision retry_fix|accept_and_advance|abandon_subtask",
  )
}

/** `IncidentPanel.kt`'s status labels. */
internal fun IncidentProgress.label(): String = when (this) {
  IncidentProgress.InvestigationPending -> "Investigation queued"
  IncidentProgress.Investigating -> "Investigating block"
  IncidentProgress.DiagnosisComplete -> "Diagnosis ready"
  IncidentProgress.DiagnosisPartial -> "Partial diagnosis"
  IncidentProgress.DiagnosisFailed -> "Diagnosis unavailable"
  IncidentProgress.NeedsPrerequisites -> "Needs your action"
  IncidentProgress.RecoveryAvailable -> "Recovery available"
  IncidentProgress.Recovering -> "Recovering"
  IncidentProgress.RecoveryFailed -> "Recovery failed"
  IncidentProgress.Resumed -> "Resumed"
}
