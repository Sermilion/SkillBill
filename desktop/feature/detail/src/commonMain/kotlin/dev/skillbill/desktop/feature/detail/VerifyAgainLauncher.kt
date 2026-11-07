package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.NewTaskKind
import dev.skillbill.desktop.core.domain.model.NewTaskStart
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.feedback.ToastTone
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class VerifyAgainLauncher(
  private val scope: CoroutineScope,
  private val controls: WorkControlRepository,
  private val session: DatabaseSessionController,
  private val toasts: ToastQueue,
  private val state: MutableStateFlow<DetailUiState>,
) {
  fun start(): Boolean {
    val current = state.value
    val run = (current.sections as? SectionsUi.Loaded)?.detail?.verifyRun ?: return false
    val options = (current.sections as? SectionsUi.Loaded)?.runOptions ?: return false
    if (!canVerifyAgain(run, options.agentChoices)) return false
    val agent = options.agentId?.takeIf { it in options.agentChoices } ?: options.agentChoices.firstOrNull() ?: return false
    val intake = run.intake?.takeIf(String::isNotBlank) ?: return false
    val repoRoot = run.repoRoot?.takeIf(String::isNotBlank) ?: return false
    val branch = run.target?.takeIf(String::isNotBlank) ?: return false
    if (current.verifyAgainInFlight) return false
    state.update { it.copy(verifyAgainInFlight = true) }
    scope.launch {
      try {
        val request = NewTaskStart(
          repoRoot = repoRoot,
          requirements = intake,
          baseBranch = null,
          agentId = agent,
          kind = NewTaskKind.Verify,
          keyChoice = run.issueKey,
          expectedDatabaseSessionId = (session.state.value as? DatabaseSessionState.Open)?.sessionId,
          target = intake,
          branch = branch,
        )
        when (val outcome = controls.startNewTask(request)) {
          is NewTaskStartOutcome.Started -> toasts.show(ToastMessage(ToastTone.Confirmation, "Started a new Verify run"))
          is NewTaskStartOutcome.Refused -> toasts.show(ToastMessage(ToastTone.Problem, outcome.message))
          is NewTaskStartOutcome.Failed -> toasts.show(ToastMessage(ToastTone.Problem, readProblemText(outcome.problem)))
          is NewTaskStartOutcome.NeedsKey -> toasts.show(ToastMessage(ToastTone.Problem, "Verify could not start without an issue key."))
        }
      } finally {
        state.update { it.copy(verifyAgainInFlight = false) }
      }
    }
    return true
  }
}
