package dev.skillbill.desktop.core.ui.feedback

import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.ControlRejection

/** The control a toast reports on. [SpecPause] is the Specs pane's pause, which stops after the current subtask. */
enum class ControlAction { PauseAfterSubtask, PauseNow, SpecPause, Cancel, Launch }

/** The item a control ran on: its key, the launch label the run options gave, and the agent a launch starts. */
data class FeedbackSubject(val key: String, val launchLabel: String?, val agentId: String?)

enum class ToastTone { Confirmation, Problem }

/** A toast's wording. [title] and [body] split [text] at its first sentence, so the full string stays exact. */
data class ToastMessage(val tone: ToastTone, val text: String) {
  val title: String get() = text.substringBefore(". ", text).let { if (it.length < text.length) "$it." else it }
  val body: String? get() = text.substringAfter(". ", "").ifEmpty { null }
}

/** The one mapping every screen uses to report a control; each wording claims only what [outcome] reported. */
fun controlFeedback(action: ControlAction, outcome: ControlOutcome, subject: FeedbackSubject): ToastMessage {
  val run = action == ControlAction.Cancel || action == ControlAction.Launch
  return when (outcome) {
    ControlOutcome.IntentRecorded -> confirmation(
      if (action == ControlAction.SpecPause) "Pause recorded. The goal stops after its current subtask."
      else "Pause recorded. The goal pauses at its next checkpoint.",
    )
    ControlOutcome.ExitConfirmed -> confirmation("Stop confirmed. The goal runner has exited.")
    ControlOutcome.TerminationAttempted -> problem(
      if (run) "The runner has not stopped yet. Refresh and retry cancellation."
      else "Stop signal sent. The goal runner's exit was not confirmed; refresh and check it before retrying.",
    )
    ControlOutcome.NoProcessNeeded -> confirmation("Request accepted. No running goal process needed stopping.")
    ControlOutcome.RequestAccepted -> confirmation(
      when (action) {
        ControlAction.Cancel -> "Cancel recorded. ${subject.key} is marked canceled. Its branch and commits stay."
        ControlAction.Launch -> {
          val label = subject.launchLabel ?: "Resume"
          subject.agentId?.let { "$label requested. Starts the installed $it harness." } ?: "$label requested."
        }
        else -> "Request accepted. No running goal process needed stopping."
      },
    )
    ControlOutcome.AlreadyLaunching -> problem("Already starting. Wait for the run to show as running.")
    is ControlOutcome.Refused -> problem(if (run) runRefusal(outcome.rejection) else goalRefusal(outcome.rejection))
    is ControlOutcome.Failed -> problem(readProblemText(outcome.problem))
  }
}

private fun confirmation(text: String) = ToastMessage(ToastTone.Confirmation, text)

private fun problem(text: String) = ToastMessage(ToastTone.Problem, text)

private fun goalRefusal(rejection: ControlRejection): String = when (rejection) {
  ControlRejection.TERMINATION_INCOMPLETE ->
    "The stop was recorded, but the goal runner has not exited yet. Refresh and check it before retrying."
  ControlRejection.OWNERSHIP_MISMATCH -> "The goal runner's identity could not be confirmed. Refresh before retrying."
  ControlRejection.FOREIGN_HOST -> "The goal runner process is on another host. Refresh before retrying stop or pause."
  ControlRejection.PID_REUSED -> "The goal runner's PID was reused by a different process. Refresh before retrying stop or pause."
  ControlRejection.UNINSPECTABLE -> "The goal runner process could not be inspected. Refresh before retrying stop or pause."
  ControlRejection.IDENTITY_MISMATCH -> "The goal runner's identity no longer matches this goal. Refresh before retrying stop or pause."
  ControlRejection.UNKNOWN_TARGET,
  ControlRejection.CHANGED_TARGET,
  ControlRejection.INELIGIBLE_TARGET,
  ControlRejection.NOT_FOUND,
  ControlRejection.AGENT_REQUIRED,
  ControlRejection.BRANCH_CHANGED,
  ControlRejection.BRANCH_UNAVAILABLE,
  ControlRejection.STALE_INCIDENT,
  ControlRejection.RECOVERY_UNAVAILABLE,
  ControlRejection.PREREQUISITE_UNMET,
  ControlRejection.RECOVERY_IN_PROGRESS,
  -> "This goal changed or cannot accept this control. Refresh before retrying."
}

private fun runRefusal(rejection: ControlRejection): String = when (rejection) {
  ControlRejection.AGENT_REQUIRED -> "Choose an agent to start this run."
  ControlRejection.BRANCH_CHANGED -> "Check out this run's recorded branch before starting it again."
  ControlRejection.BRANCH_UNAVAILABLE ->
    "Cannot read this run's checkout branch. Restore its checkout or check out its recorded branch before starting it again."
  ControlRejection.TERMINATION_INCOMPLETE -> "The runner has not stopped yet. Refresh and retry cancellation."
  ControlRejection.OWNERSHIP_MISMATCH -> "The runner's identity could not be confirmed. Refresh before retrying."
  ControlRejection.FOREIGN_HOST -> "The runner process is on another host. Refresh before retrying cancellation."
  ControlRejection.PID_REUSED -> "The runner PID was reused by a different process. Refresh before retrying cancellation."
  ControlRejection.UNINSPECTABLE -> "The runner process could not be inspected. Refresh before retrying cancellation."
  ControlRejection.IDENTITY_MISMATCH -> "The runner identity no longer matches this run. Refresh before retrying cancellation."
  ControlRejection.STALE_INCIDENT -> "The incident changed. Refresh before trying recovery again."
  ControlRejection.RECOVERY_UNAVAILABLE -> "Recovery is no longer available. Refresh the incident details."
  ControlRejection.PREREQUISITE_UNMET -> "Confirm the listed prerequisites before applying recovery."
  ControlRejection.RECOVERY_IN_PROGRESS -> "Recovery is already in progress. Refresh the incident details."
  ControlRejection.UNKNOWN_TARGET,
  ControlRejection.CHANGED_TARGET,
  ControlRejection.INELIGIBLE_TARGET,
  ControlRejection.NOT_FOUND,
  -> "This run changed or cannot accept this action. Refresh before retrying."
}
