package dev.skillbill.desktop.app.usage

import androidx.compose.runtime.snapshotFlow
import dev.skillbill.desktop.app.navigation.RouteEntry
import dev.skillbill.desktop.app.navigation.WindowNavigator
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.usage.UsageRepository
import dev.skillbill.desktop.core.domain.usage.model.ModelSelectionProvenance
import dev.skillbill.desktop.core.domain.usage.model.UsageDemand
import dev.skillbill.desktop.core.domain.usage.model.UsageModelSelection
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageRequest
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import dev.skillbill.desktop.core.ui.navigation.HistoryRoute
import dev.skillbill.desktop.core.ui.navigation.NewTaskRoute
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.core.ui.navigation.WorkRoute
import dev.skillbill.desktop.core.ui.pane.ChatCommands
import dev.skillbill.desktop.core.ui.usage.model.SelectedNewTaskUsageContext
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

data class UsageSidebarState(
  val provider: UsageProvider? = null,
  val contextual: Boolean = false,
  val contextLabel: String? = null,
  val accountLabel: String? = null,
  val accountExplanation: String? = null,
  val fallback: Boolean = false,
  val unavailableReason: String? = null,
  val result: UsageResult? = null,
  val active: Boolean = false,
  val choicesEnabled: Boolean = true,
  val demandEligible: Boolean = false,
  val popupDismissalToken: Long = 0,
)

@Inject
@SingleIn(WindowScope::class)
class UsageSidebarModel(
  private val chat: ChatCommands,
  private val executionChats: ExecutionChatRepository,
  private val details: WorkDetailRepository,
  private val navigator: WindowNavigator,
  private val session: DatabaseSessionController,
  private val usage: UsageRepository,
  private val diagnostics: Diagnostics,
  windowScope: WindowCoroutineScope,
) {
  private val scope = CoroutineScope(windowScope.scope.coroutineContext + SupervisorJob(windowScope.scope.coroutineContext[Job]))
  private val ownerId = "window-${UUID.randomUUID()}"
  private val state = MutableStateFlow(UsageSidebarState())
  val view: StateFlow<UsageSidebarState> = state.asStateFlow()
  private var manualAgentId = "codex"
  private var request: UsageRequest? = null
  private var demandJob: Job? = null
  private var demandGeneration = 0L
  private var popupToken = 0L
  private var routeGeneration = 0L
  private var routeEntry: RouteEntry? = null
  private var released = false
  private var observationGeneration = 0L
  private var manualChoiceGeneration = 0L
  private var demandActive = false
  private var mainVisible = false
  private var minimized = false
  private var settingsRoute = false
  private var inlineSidebarVisible = false
  private var drawerOpen = false
  private var cardHostAttached = false
  private var hasFocus = false

  init {
    scope.launch {
      try {
        combine(snapshotFlow { navigator.top }, session.state) { entry, database -> entry to database }
          .collectLatest { (entry, database) ->
            val generation = ++routeGeneration
            routeEntry = entry
            replaceContext()
            if (database !is DatabaseSessionState.Open || entry.sessionId != database.sessionId || entry.route is SettingsRoute) {
              state.value = state.value.copy(
                contextLabel = null,
                unavailableReason = null,
                contextual = entry.route is DetailRoute || entry.route is NewTaskRoute,
                choicesEnabled = entry.route is WorkRoute || entry.route is HistoryRoute,
              )
              settingsRoute = entry.route is SettingsRoute
              return@collectLatest
            }
            settingsRoute = false
            when (val route = entry.route) {
              is DetailRoute -> observeDetail(route.item, generation)
              is NewTaskRoute -> observeNewTask(generation)
              is WorkRoute, HistoryRoute -> observeManual(generation)
              is SettingsRoute -> Unit
            }
          }
      } finally {
        release()
      }
    }
  }

  fun setWindowFacts(visible: Boolean, isMinimized: Boolean, focused: Boolean) {
    val wasEligible = state.value.demandEligible
    val triggerWasVisible = eligible()
    val regained = visible && !isMinimized && focused && !hasFocus
    mainVisible = visible
    minimized = isMinimized
    hasFocus = focused
    dismissPopupIfTriggerLost(triggerWasVisible)
    reconcileDemand()
    if ((regained || !wasEligible && state.value.demandEligible) && state.value.demandEligible) usage.checkDue(ownerId)
  }

  fun setSidebarFacts(settings: Boolean, inlineVisible: Boolean, drawerIsOpen: Boolean) {
    val triggerWasVisible = eligible()
    settingsRoute = settings
    inlineSidebarVisible = inlineVisible
    drawerOpen = drawerIsOpen
    dismissPopupIfTriggerLost(triggerWasVisible)
    reconcileDemand()
  }

  fun setCardHostAttached(attached: Boolean) {
    val triggerWasVisible = eligible()
    cardHostAttached = attached
    dismissPopupIfTriggerLost(triggerWasVisible)
    reconcileDemand()
  }

  fun chooseManualProvider(agentId: String) {
    if (released || !state.value.choicesEnabled || providerFor(agentId) == null) return
    if (!isCurrentRoute(routeGeneration) || navigator.top.route.let { it !is WorkRoute && it !is HistoryRoute }) return
    manualAgentId = agentId
    val choiceGeneration = ++manualChoiceGeneration
    if (!state.value.contextual) {
      replaceContext()
      val expectedRouteGeneration = routeGeneration
      scope.launch { useManualContext(expectedRouteGeneration, choiceGeneration) }
    }
  }

  fun refresh() {
    request?.let(usage::refresh)
  }

  fun release() {
    if (released) return
    released = true
    cardHostAttached = false
    replaceContext()
    scope.cancel()
  }

  private suspend fun observeDetail(item: WorkItem, expectedRouteGeneration: Long) {
    var previous: ContextDetail? = null
    details.observe(item).catch { failure ->
      if (failure is CancellationException) throw failure
      diagnostics.record("usage_detail_observation_failed")
      if (isCurrentRoute(expectedRouteGeneration)) {
        replaceContext(contextual = true, label = "Current item defaults, details failed to load")
        val agent = resolveAgentOrNull(item, null, null)
        replaceAgent(agent, null, item.running, true, "Current item defaults, details failed to load", true, expectedRouteGeneration = expectedRouteGeneration)
      }
    }.collectLatest { detailState ->
      if (!isCurrentRoute(expectedRouteGeneration)) return@collectLatest
      val context = when (val sections = detailState.sections) {
        DetailSections.Pending -> ContextDetail(detailState.header, null, null, true, "Current item defaults while details load")
        is DetailSections.Loaded -> if (sections.stale) {
          ContextDetail(detailState.header, null, null, true, "Current item defaults, details are stale")
        } else {
          ContextDetail(detailState.header, sections.detail, sections.runOptions, false, "Run details")
        }
        DetailSections.Gone -> ContextDetail(detailState.header, null, null, true, "Current item defaults, run details are unavailable")
        is DetailSections.Failed -> ContextDetail(detailState.header, null, null, true, "Current item defaults, run details failed to load")
      }
      val sameSource = previous?.let {
        it.header.agentId == context.header.agentId && it.detail?.activeAgent == context.detail?.activeAgent &&
          it.options == context.options && it.fallback == context.fallback && it.label == context.label
      } == true
      previous = context
      if (sameSource && request != null) {
        state.value = state.value.copy(active = context.header.running)
        reconcileDemand()
        return@collectLatest
      }
      replaceContext(contextual = true, label = context.label)
      val agent = resolveAgentOrNull(context.header, context.detail, context.options)
      if (isCurrentRoute(expectedRouteGeneration)) {
        replaceAgent(agent, null, context.header.running, true, context.label, context.fallback, expectedRouteGeneration = expectedRouteGeneration)
      }
    }
  }

  private suspend fun observeNewTask(expectedRouteGeneration: Long) {
    var previous: SelectedNewTaskUsageContext.Ready? = null
    chat.selectedNewTaskUsageContext.collectLatest { selected ->
      if (!isCurrentRoute(expectedRouteGeneration)) return@collectLatest
      if (selected is SelectedNewTaskUsageContext.Ready && previous?.copy(activeWork = selected.activeWork) == selected && request != null) {
        state.value = state.value.copy(active = selected.activeWork)
        reconcileDemand()
        return@collectLatest
      }
      previous = selected as? SelectedNewTaskUsageContext.Ready
      when (selected) {
        is SelectedNewTaskUsageContext.Absent -> replaceContext()
        is SelectedNewTaskUsageContext.Pending -> replaceContext(contextual = true)
        is SelectedNewTaskUsageContext.Ready -> replaceAgent(
          selected.agent,
          selected.logicalSessionId,
          selected.activeWork,
          contextual = true,
          label = "Selected New task",
          fallback = false,
          modelIsActual = selected.agent.model != null,
          expectedRouteGeneration = expectedRouteGeneration,
        )
      }
    }
  }

  private suspend fun observeManual(expectedRouteGeneration: Long) {
    useManualContext(expectedRouteGeneration, manualChoiceGeneration)
    awaitCancellation()
  }

  private suspend fun useManualContext(expectedRouteGeneration: Long, expectedManualChoiceGeneration: Long) {
    if (!isCurrentRoute(expectedRouteGeneration) || expectedManualChoiceGeneration != manualChoiceGeneration) return
    if (providerFor(manualAgentId) == null) return replaceContext()
    replaceContext()
    val agent = try {
      executionChats.agentFor(manualAgentId)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      diagnostics.record("usage_manual_agent_resolution_failed")
      if (!isCurrentRoute(expectedRouteGeneration) || expectedManualChoiceGeneration != manualChoiceGeneration) return
      return replaceContext(reason = "The selected provider could not be resolved", label = "Current local account")
    }
    if (!isCurrentRoute(expectedRouteGeneration) || expectedManualChoiceGeneration != manualChoiceGeneration) return
    replaceAgent(agent, null, false, false, "Current local account", false, accountExplanation = "This is the selected local account, not a verified account for a particular run.", expectedRouteGeneration = expectedRouteGeneration, expectedManualChoiceGeneration = expectedManualChoiceGeneration)
  }

  private suspend fun resolveAgentOrNull(
    item: WorkItem,
    detail: dev.skillbill.desktop.core.domain.model.Detail?,
    options: dev.skillbill.desktop.core.domain.model.RunOptions?,
  ): ChatAgent? = try {
    executionChats.resolveAgent(item, detail, options)
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    diagnostics.record("usage_run_agent_resolution_failed")
    null
  }

  private suspend fun replaceAgent(
    agent: ChatAgent?,
    logicalSessionId: String?,
    active: Boolean,
    contextual: Boolean,
    label: String,
    fallback: Boolean,
    modelIsActual: Boolean = false,
    accountExplanation: String? = if (contextual) "Run history does not record a verified account. This uses the current local account." else null,
    expectedRouteGeneration: Long = routeGeneration,
    expectedManualChoiceGeneration: Long? = null,
  ) {
    if (!isCurrentRoute(expectedRouteGeneration) || expectedManualChoiceGeneration?.let { it != manualChoiceGeneration } == true) return
    val contextGeneration = ++demandGeneration
    val contextPopupToken = ++popupToken
    stopDemand()
    request = null
    val contextState = UsageSidebarState(
      contextual = contextual,
      contextLabel = label,
      accountLabel = if (contextual) "Current local account" else label,
      accountExplanation = accountExplanation,
      fallback = fallback,
      active = active,
      choicesEnabled = !contextual,
      popupDismissalToken = contextPopupToken,
    )
    state.value = contextState
    val provider = agent?.let { providerFor(it.agentId) }
    if (agent == null || provider == null) {
      return replaceContext(
        contextual,
        if (agent == null) "No provider is available for this context" else null,
        if (agent == null) label else null,
      )
    }
    val profile = try {
      usage.profileLookup(provider, agent.profile, logicalSessionId)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      diagnostics.record("usage_context_profile_lookup_failed")
      if (contextGeneration == demandGeneration && isCurrentRoute(expectedRouteGeneration) && expectedManualChoiceGeneration?.let { it != manualChoiceGeneration } != true) {
        stopDemand()
        request = null
        state.value = contextState.copy(
          provider = provider,
          unavailableReason = "The provider profile is unavailable",
          popupDismissalToken = popupToken,
        )
      }
      return
    }
    if (contextGeneration != demandGeneration || !isCurrentRoute(expectedRouteGeneration) || expectedManualChoiceGeneration?.let { it != manualChoiceGeneration } == true) return
    val model = agent.model?.takeIf { modelIsActual }?.let { UsageModelSelection(it, ModelSelectionProvenance.ActualExecution) }
    request = UsageRequest(profile, logicalSessionId, model)
    state.value = contextState.copy(
      provider = provider,
      popupDismissalToken = popupToken,
      demandEligible = eligible(),
    )
    reconcileDemand()
  }

  private fun replaceContext(contextual: Boolean = false, reason: String? = null, label: String? = null) {
    stopDemand()
    request = null
    ++demandGeneration
    val dismissalToken = ++popupToken
    state.value = UsageSidebarState(
      contextual = contextual,
      contextLabel = label ?: if (contextual) "Usage context unavailable" else null,
      unavailableReason = reason,
      choicesEnabled = !contextual,
      popupDismissalToken = dismissalToken,
      demandEligible = false,
    )
  }

  private fun reconcileDemand() {
    val currentRequest = request
    val eligible = currentRequest != null && eligible() && state.value.provider != null
    state.value = state.value.copy(demandEligible = eligible)
    if (!eligible || currentRequest == null) {
      stopDemand()
      return
    }
    val demand = UsageDemand(ownerId, currentRequest, visible = true, active = state.value.active)
    if (demandActive) {
      usage.updateDemand(demand)
    } else {
      usage.acquireDemand(demand)
      demandActive = true
      val generation = demandGeneration
      val observation = ++observationGeneration
      demandJob = scope.launch {
        try {
          usage.observeDemand(ownerId).collectLatest { result ->
            if (generation == demandGeneration && observation == observationGeneration) {
              state.value = state.value.copy(result = result, unavailableReason = null)
            }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          diagnostics.record("usage_demand_observation_failed")
          if (observation == observationGeneration) {
            state.value = state.value.copy(result = null, unavailableReason = "Usage observation failed")
          }
        } finally {
          if (observation == observationGeneration) {
            stopDemand()
            state.value = state.value.copy(demandEligible = false)
          }
        }
      }
    }
  }

  private fun stopDemand() {
    ++observationGeneration
    demandJob?.cancel()
    demandJob = null
    usage.releaseDemand(ownerId)
    demandActive = false
  }

  private fun isCurrentRoute(generation: Long): Boolean {
    val entry = routeEntry ?: return false
    val database = session.state.value as? DatabaseSessionState.Open ?: return false
    return !released && generation == routeGeneration && navigator.top.entryToken == entry.entryToken && entry.sessionId == database.sessionId
  }

  private fun eligible() = !released && mainVisible && !minimized && !settingsRoute && (inlineSidebarVisible || drawerOpen) && cardHostAttached

  private fun dismissPopupIfTriggerLost(wasVisible: Boolean) {
    if (wasVisible && !eligible()) state.value = state.value.copy(popupDismissalToken = ++popupToken)
  }

  private fun providerFor(agentId: String): UsageProvider? = when (agentId.lowercase()) {
    "codex" -> UsageProvider.Codex
    "claude" -> UsageProvider.Claude
    "cursor" -> UsageProvider.Cursor
    else -> null
  }

  private data class ContextDetail(
    val header: WorkItem,
    val detail: dev.skillbill.desktop.core.domain.model.Detail?,
    val options: dev.skillbill.desktop.core.domain.model.RunOptions?,
    val fallback: Boolean,
    val label: String,
  )
}
