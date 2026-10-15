package dev.skillbill.tray.feature.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.SettingsRepository
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import dev.skillbill.tray.core.domain.repository.WorkPolicyRepository
import dev.skillbill.tray.core.ui.Feedback
import dev.skillbill.tray.core.ui.FeedbackIds
import dev.skillbill.tray.core.ui.FeedbackTone
import dev.skillbill.tray.core.ui.NavigationEffects
import dev.skillbill.tray.core.ui.TrackedBoardSections
import dev.skillbill.tray.core.ui.acknowledged
import dev.skillbill.tray.core.ui.withFeedback
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The tray's view of the board: the status icon, the macOS menu, the tooltip and the popover. It observes the board with its
 * own sections, unfiltered, so the main window's sidebar filter never reaches the tray, and it is the only owner of the
 * runtime's board arrangement.
 */
class TrayViewModel(
  boards: WorkBoardRepository,
  private val policy: WorkPolicyRepository,
  private val settings: SettingsRepository,
) : ViewModel() {
  private val sections = TrackedBoardSections(viewModelScope, boards)
  private val local = MutableStateFlow(Local())
  private val feedbackIds = FeedbackIds()
  private val navigation = NavigationEffects<TrayEffect>()

  val state: StateFlow<TrayUiState> = combine(sections.state, local) { tracked, l ->
    val arrangement = if (tracked.loaded) policy.arrange(tracked.trackedItems) else null
    TrayUiState(tracked, arrangement, l.tab, l.feedback)
  }.stateIn(viewModelScope, SharingStarted.Eagerly, TrayUiState())

  val effects: Flow<TrayEffect> = navigation.flow

  init {
    viewModelScope.launch {
      settings.settings.collect { result ->
        sections.apply(result)
        if (result is SettingsState.Ready) local.update { it.copy(tab = PopoverTab.fromLabel(result.settings.lastPopoverTab)) }
      }
    }
  }

  fun onAction(action: TrayAction) {
    when (action) {
      TrayAction.Refresh -> sections.refresh()
      TrayAction.LoadMoreLive -> sections.loadMoreLive()
      is TrayAction.ChooseTab -> chooseTab(action.tab)
      is TrayAction.Select -> navigation.send(TrayEffect.OpenDetail(action.identity))
      is TrayAction.AcknowledgeFeedback -> local.update { it.copy(feedback = it.feedback.acknowledged(action.id)) }
    }
  }

  private fun chooseTab(tab: PopoverTab) {
    viewModelScope.launch {
      val result = settings.update { it.copy(lastPopoverTab = tab.label) }
      if (result is Outcome.Failure) addFeedback(result.problem)
    }
  }

  private fun addFeedback(problem: Problem) {
    val feedback = Feedback(feedbackIds.next(), FeedbackTone.PROBLEM, problem.summary)
    local.update { it.copy(feedback = it.feedback.withFeedback(feedback)) }
  }

  private data class Local(
    val tab: PopoverTab = PopoverTab.GOALS,
    val feedback: List<Feedback> = emptyList(),
  )
}
