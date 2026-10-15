package dev.skillbill.tray.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.HistoryFilter
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.SettingsRepository
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import dev.skillbill.tray.core.ui.Feedback
import dev.skillbill.tray.core.ui.FeedbackIds
import dev.skillbill.tray.core.ui.FeedbackTone
import dev.skillbill.tray.core.ui.NavigationEffects
import dev.skillbill.tray.core.ui.SectionPager
import dev.skillbill.tray.core.ui.SectionQuery
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
 * Owns the history section alone. It reads the same preferences as the work screen but never shares state with it: a failing or
 * appending history list cannot disturb the live sections, and the other way round.
 */
class HistoryViewModel(
  private val boards: WorkBoardRepository,
  private val settings: SettingsRepository,
) : ViewModel() {
  private val pager = SectionPager(viewModelScope, boards, WorkSection.HISTORY) { true }
  private val local = MutableStateFlow(Local())
  private val feedbackIds = FeedbackIds()
  private val navigation = NavigationEffects<HistoryEffect>()

  /** Preferences the section was last started with; null until the first read of them, and again after one fails. */
  private var applied: AppSettings? = null

  val state: StateFlow<HistoryUiState> = combine(pager.state, local) { list, l ->
    HistoryUiState(list, l.filter, l.retention, l.historyFilter, l.feedback)
  }.stateIn(viewModelScope, SharingStarted.Eagerly, HistoryUiState())

  val effects: Flow<HistoryEffect> = navigation.flow

  init {
    viewModelScope.launch { settings.settings.collect(::applySettings) }
    viewModelScope.launch {
      boards.refreshEvents.collect { changed -> if (WorkSection.HISTORY in changed) pager.markRefreshing() }
    }
  }

  fun onAction(action: HistoryAction) {
    when (action) {
      is HistoryAction.FilterChanged -> changeFilter(action.text)
      is HistoryAction.ChooseFilter -> chooseFilter(action.choice)
      HistoryAction.Retry -> pager.retry()
      HistoryAction.LoadMore -> pager.loadMore()
      is HistoryAction.Select -> navigation.send(HistoryEffect.OpenDetail(action.identity))
      is HistoryAction.AcknowledgeFeedback -> local.update { it.copy(feedback = it.feedback.acknowledged(action.id)) }
    }
  }

  private fun applySettings(result: SettingsState) {
    when (result) {
      SettingsState.Loading -> Unit
      is SettingsState.Failed -> {
        applied = null
        pager.failInitial(result.problem)
      }
      is SettingsState.Ready -> {
        val next = result.settings
        val previous = applied
        applied = next
        local.update { it.copy(retention = next.retention, historyFilter = next.historyFilter) }
        // What the rows mean changed with the store or the history filter, so they go; a new retention keeps them meanwhile.
        if (previous == null || previous.databasePath != next.databasePath || previous.historyFilter != next.historyFilter) {
          start(next.retention, next.historyFilter, clear = true)
        } else if (previous.retention != next.retention) {
          start(next.retention, next.historyFilter, clear = false)
        }
      }
    }
  }

  private fun start(retention: Retention, historyFilter: HistoryFilter, clear: Boolean) {
    pager.start(SectionQuery(retention, local.value.filter, historyFilter), clear)
  }

  private fun changeFilter(text: String) {
    if (local.value.filter == text) return
    local.update { it.copy(filter = text) }
    applied?.let { start(it.retention, it.historyFilter, clear = false) }
  }

  private fun chooseFilter(choice: HistoryFilter) {
    viewModelScope.launch {
      val result = settings.update { it.copy(historyFilter = choice) }
      if (result is Outcome.Failure) {
        val feedback = Feedback(feedbackIds.next(), FeedbackTone.PROBLEM, result.problem.summary)
        local.update { it.copy(feedback = it.feedback.withFeedback(feedback)) }
      }
    }
  }

  private data class Local(
    val filter: String = "",
    val retention: Retention = Retention.DAY,
    val historyFilter: HistoryFilter = HistoryFilter.ALL,
    val feedback: List<Feedback> = emptyList(),
  )
}
