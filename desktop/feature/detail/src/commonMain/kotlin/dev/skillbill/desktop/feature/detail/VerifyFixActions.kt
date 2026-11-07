package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.domain.settings.CriterionMark
import dev.skillbill.desktop.core.domain.settings.CriterionMarkKey
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.feedback.ToastTone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

internal class VerifyFixActions(
  private val scope: CoroutineScope,
  private val settings: AppSettingsRepository,
  private val toasts: ToastQueue,
  private val state: MutableStateFlow<DetailUiState>,
  private val send: (DetailEffect) -> Unit,
) {
  private var selectionWorkflowId: String? = null
  private var storedMarks = emptyMap<CriterionMarkKey, CriterionMark>()

  init {
    settings.criterionMarks.onEach { result ->
      if (result is Outcome.Success) {
        storedMarks = result.value
        refresh()
      }
    }.launchIn(scope)
  }

  fun refresh() {
    val run = currentRun()
    val changedRun = selectionWorkflowId != run?.workflowId
    selectionWorkflowId = run?.workflowId
    val marks = storedMarks.filterKeys { it.workflowId == run?.workflowId }.mapKeys { it.key.criterionNumber }
    state.update { current ->
      val currentRun = (current.sections as? SectionsUi.Loaded)?.detail?.verifyRun
      val items = currentRun?.let { verifyFixItems(it, marks) }.orEmpty()
      current.copy(criterionMarks = marks, fixSelection = pruneSelection(if (changedRun) emptyMap() else current.fixSelection, items, currentRun?.let(::verifySelectionAllowed) == true))
    }
  }

  fun onAction(action: DetailAction): Boolean {
    val run = currentRun() ?: return false
    val items = verifyFixItems(run, state.value.criterionMarks)
    if (!verifySelectionAllowed(run)) return false
    when (action) {
      is DetailAction.ToggleFix -> items.firstOrNull { it.key == action.key }?.let { item -> state.update { it.copy(fixSelection = toggleItem(it.fixSelection, item)) } }
      is DetailAction.SetFixRoute -> state.update { it.copy(fixSelection = setRoute(it.fixSelection, action.key, action.route)) }
      is DetailAction.ToggleSelectAllFixes -> state.update { current ->
        val matching = items.filter { it.kind == action.kind }
        current.copy(fixSelection = toggleSelectAll(matching, current.fixSelection))
      }
      DetailAction.ClearFixSelection -> state.update { it.copy(fixSelection = emptyMap()) }
      DetailAction.FixInChat -> {
        val selected = orderedSelectedItems(items, state.value.fixSelection, FixRoute.Chat)
        if (selected.isNotEmpty()) {
          send(DetailEffect.FixInChat(state.value.header, verifyChatBrief(run, selected)))
          state.update { current -> current.copy(fixSelection = current.fixSelection - selected.map { it.key }.toSet()) }
        }
      }
      DetailAction.StartGoalWithFixes -> {
        val selected = orderedSelectedItems(items, state.value.fixSelection, FixRoute.Goal)
        val root = run.repoRoot?.takeIf(String::isNotBlank)
        val branch = run.target?.takeIf(String::isNotBlank)
        if (root == null || branch == null) {
          toasts.show(ToastMessage(ToastTone.Problem, "The Verify run has no repository or branch"))
          return true
        }
        if (selected.isNotEmpty()) {
          send(DetailEffect.PrefillGoalIntake(root, branch, verifyGoalIntake(run, selected), selected.map { it.key }))
        }
      }
      is DetailAction.GoalIntakeResult -> if (action.accepted) state.update { it.copy(fixSelection = it.fixSelection - action.keys.toSet()) }
        else toasts.show(ToastMessage(ToastTone.Problem, "Finish or clear the New task draft first"))
      is DetailAction.MarkCriterion -> if (items.any { it is FixItem.Criterion && it.grade.number == action.number }) {
        mark(run.workflowId, action.number, action.mark)
      }
      else -> return false
    }
    return true
  }

  private fun mark(workflowId: String, number: Int, mark: CriterionMark?) {
    scope.launch {
      when (settings.setCriterionMark(CriterionMarkKey(workflowId, number), mark)) {
        is Outcome.Success -> if (mark != null) state.update { current ->
          if ((current.sections as? SectionsUi.Loaded)?.detail?.verifyRun?.workflowId == workflowId) current.copy(fixSelection = current.fixSelection - "c:$number") else current
        }
        is Outcome.Failure -> toasts.show(ToastMessage(ToastTone.Problem, "Couldn't save this mark. Try again."))
      }
    }
  }

  private fun currentRun() = ((state.value.sections as? SectionsUi.Loaded)?.detail)?.verifyRun
}
