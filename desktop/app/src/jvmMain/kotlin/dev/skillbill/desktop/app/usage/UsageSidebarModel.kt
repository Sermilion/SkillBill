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
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.domain.usage.LastUsageStore
import dev.skillbill.desktop.core.domain.usage.UsageRepository
import dev.skillbill.desktop.core.domain.usage.model.ModelSelectionProvenance
import dev.skillbill.desktop.core.domain.usage.model.UsageDemand
import dev.skillbill.desktop.core.domain.usage.model.UsageModelSelection
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageRequest
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshAvailability
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableReason
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What the usage card shows. [provider] and [result] always describe the last valid agent: a context without a supported
 * agent, a pending selection, a database switch or a hidden sidebar leaves them in place, so the card never blanks out or
 * jumps. Before any context names an agent, the card shows the agent it showed last, even in an earlier app run.
 */
data class UsageSidebarState(
  val provider: UsageProvider? = null,
  val result: UsageResult? = null,
  val unavailableReason: String? = null,
  val active: Boolean = false,
  val demandEligible: Boolean = false,
  val popupDismissalToken: Long = 0,
  /**
   * The open chat or run uses this agent, which has no usage source (Cursor). The card shows only its name and the
   * popup still offers provider tabs and the default agent; [provider] and [result] stay for when a usage agent returns.
   */
  val nameOnlyAgent: String? = null,
  /** Changes whenever the usage request changes, so refresh availability can be re-observed. */
  val requestVersion: Long = 0,
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
  private val settings: AppSettingsRepository,
  private val lastUsage: LastUsageStore,
  windowScope: WindowCoroutineScope,
  private val clock: Clock = Clock.System,
) {
  private val scope = CoroutineScope(windowScope.scope.coroutineContext + SupervisorJob(windowScope.scope.coroutineContext[Job]))
  private val ownerId = "window-${UUID.randomUUID()}"
  private val state = MutableStateFlow(UsageSidebarState())
  val view: StateFlow<UsageSidebarState> = state.asStateFlow()
  /** Every agent the app can run, for the default agent choice. */
  val agents: List<String> = try {
    executionChats.agents()
  } catch (_: Exception) {
    emptyList()
  }
  private val defaultAgentState = MutableStateFlow<String?>(null)

  /** The agent new chats, goals and phases start with; null means the first detected agent. */
  val defaultAgent: StateFlow<String?> = defaultAgentState.asStateFlow()
  private val presentationTimeState = MutableStateFlow(clock.now())
  val presentationTime: StateFlow<Instant> = presentationTimeState.asStateFlow()
  /** The agent the operator picked on the card; null follows the fallback chain in [fallbackAgentId]. */
  private var manualAgentId: String? = null
  private var request: UsageRequest? = null
  private var demandJob: Job? = null
  private var profileJob: Job? = null
  private var presentationTimeJob: Job? = null
  private var agentGeneration = 0L
  private var observationGeneration = 0L
  private var popupToken = 0L
  private var routeGeneration = 0L
  private var routeEntry: RouteEntry? = null
  private var released = false
  private var demandActive = false
  private var mainVisible = false
  private var minimized = false
  private var settingsRoute = false
  private var inlineSidebarVisible = false
  private var drawerOpen = false
  private var cardHostAttached = false
  private var hasFocus = false

  /** The agent the card currently shows. */
  private var currentKey: AgentKey? = null

  /** The agent the current context last asked for; a manual pick holds until this changes. */
  private var contextKey: AgentKey? = null
  private var manualOverride = false

  /** The usage-less agent the current context last named, so a manual pick holds until the context changes agent. */
  private var nameOnlyContext: String? = null

  /** The last result worth showing per provider, so switching back shows it at once instead of a loading state. */
  private val lastResults = mutableMapOf<Pair<UsageProvider, String?>, UsageResult>()

  init {
    scope.launch {
      try {
        settings.settings.collect { defaultAgentState.value = it.defaultAgentId }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        diagnostics.record("usage_default_agent_unavailable")
      }
    }
    scope.launch {
      try {
        combine(snapshotFlow { navigator.top }, session.state) { entry, database -> entry to database }
          .collectLatest { (entry, database) ->
            val generation = ++routeGeneration
            routeEntry = entry
            settingsRoute = entry.route is SettingsRoute
            if (database !is DatabaseSessionState.Open || (entry.sessionId != null && entry.sessionId != database.sessionId)) {
              clearAgent()
              return@collectLatest
            }
            if (settingsRoute) {
              reconcileDemand()
              if (currentKey == null) useManualAgent(generation, onSettings = true)
              return@collectLatest
            }
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

  /** Shows [agentId]'s usage. The pick holds until the open run or chat switches to a different agent. */
  fun chooseManualProvider(agentId: String) {
    if (released || providerFor(agentId) == null) return
    manualAgentId = agentId
    manualOverride = navigator.top.route.let { it !is WorkRoute && it !is HistoryRoute }
    val expectedRouteGeneration = routeGeneration
    scope.launch { useManualAgent(expectedRouteGeneration) }
  }

  /** Saves [agentId] as the default agent; the current default clears it. */
  fun toggleDefaultAgent(agentId: String) {
    if (released) return
    val next = agentId.takeUnless { it == defaultAgentState.value }
    defaultAgentState.value = next
    scope.launch {
      if (settings.setDefaultAgent(next) is Outcome.Failure) {
        diagnostics.record("usage_default_agent_write_failed")
        defaultAgentState.value = settings.settings.first().defaultAgentId
      }
    }
  }

  fun refresh() {
    request?.let(usage::refresh)
  }

  fun refreshAvailability(): Flow<UsageRefreshAvailability> = request?.let(usage::refreshAvailability)
    ?: flowOf(UsageRefreshAvailability.Disabled(UsageUnavailableReason(UsageUnavailableCode.ProfileUnverified)))

  fun recheckSignInAvailability(): Flow<UsageRefreshAvailability> = request?.let(usage::recheckSignInAvailability)
    ?: flowOf(UsageRefreshAvailability.Disabled(UsageUnavailableReason(UsageUnavailableCode.ProfileUnverified)))

  fun recheckSignIn() {
    request?.let(usage::recheckSignIn)
  }

  fun recordDashboardOpenFailure() {
    diagnostics.record("usage_dashboard_open_failed")
  }

  fun release() {
    if (released) return
    released = true
    cardHostAttached = false
    stopPresentationTime()
    profileJob?.cancel()
    stopDemand()
    scope.cancel()
  }

  private suspend fun observeDetail(item: WorkItem, expectedRouteGeneration: Long) {
    var previous: ContextDetail? = null
    details.observe(item).catch { failure ->
      if (failure is CancellationException) throw failure
      diagnostics.record("usage_detail_observation_failed")
      if (isCurrentRoute(expectedRouteGeneration)) applyContextAgent(resolveAgentOrNull(item, null, null), null, item.running)
    }.collectLatest { detailState ->
      if (!isCurrentRoute(expectedRouteGeneration)) return@collectLatest
      val context = when (val sections = detailState.sections) {
        is DetailSections.Loaded -> if (sections.stale) ContextDetail(detailState.header, null, null) else ContextDetail(detailState.header, sections.detail, sections.runOptions)
        DetailSections.Pending, DetailSections.Gone, is DetailSections.Failed -> ContextDetail(detailState.header, null, null)
      }
      val sameSource = previous?.let {
        it.header.agentId == context.header.agentId && it.detail?.activeAgent == context.detail?.activeAgent && it.options == context.options
      } == true
      previous = context
      if (sameSource) {
        updateActivity(context.header.running)
        return@collectLatest
      }
      val agent = resolveAgentOrNull(context.header, context.detail, context.options)
      if (isCurrentRoute(expectedRouteGeneration)) applyContextAgent(agent, null, context.header.running)
    }
  }

  private suspend fun observeNewTask(expectedRouteGeneration: Long) {
    var previous: SelectedNewTaskUsageContext.Ready? = null
    chat.selectedNewTaskUsageContext.collectLatest { selected ->
      if (!isCurrentRoute(expectedRouteGeneration)) return@collectLatest
      // Absent and Pending keep the last valid agent on screen, or show the fallback agent when there is none yet.
      if (selected !is SelectedNewTaskUsageContext.Ready) {
        if (currentKey == null) useManualAgent(expectedRouteGeneration)
        return@collectLatest
      }
      if (previous?.copy(activeWork = selected.activeWork) == selected) {
        updateActivity(selected.activeWork)
        return@collectLatest
      }
      previous = selected
      applyContextAgent(selected.agent, selected.logicalSessionId, selected.activeWork, modelIsActual = selected.agent.model != null)
    }
  }

  private suspend fun observeManual(expectedRouteGeneration: Long) {
    manualOverride = false
    contextKey = null
    useManualAgent(expectedRouteGeneration)
    awaitCancellation()
  }

  private suspend fun useManualAgent(expectedRouteGeneration: Long, onSettings: Boolean = false) {
    val picked = manualAgentId
    val agentId = picked ?: fallbackAgentId()
    val agent = try {
      executionChats.agentFor(agentId)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      diagnostics.record("usage_manual_agent_resolution_failed")
      return
    }
    if (!isCurrentRoute(expectedRouteGeneration) || (settingsRoute && !onSettings) || picked != manualAgentId) return
    applyAgent(agent, null, state.value.active.takeIf { manualOverride } ?: false, modelIsActual = false)
  }

  /** The agent shown now, else the one shown last (also across restarts), else the default agent, else Codex. */
  private suspend fun fallbackAgentId(): String {
    state.value.provider?.let { return it.agentId() }
    val remembered = try {
      lastUsage.lastAgent()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      diagnostics.record("usage_last_agent_unavailable")
      null
    }
    val default = defaultAgentState.value ?: try {
      settings.settings.first().defaultAgentId
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    }
    return listOfNotNull(remembered, default).firstOrNull { providerFor(it) != null } ?: "codex"
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

  /**
   * Follows the context's agent unless the operator picked one for this context. An agent without a usage source shows
   * by name only; an unresolved agent keeps the last one.
   */
  private fun applyContextAgent(agent: ChatAgent?, logicalSessionId: String?, active: Boolean, modelIsActual: Boolean = false) {
    if (agent != null && providerFor(agent.agentId) == null) {
      val sameAgent = nameOnlyContext == agent.agentId
      nameOnlyContext = agent.agentId
      contextKey = null
      if (manualOverride && sameAgent) return updateActivity(active)
      manualOverride = false
      return showNameOnly(agent.agentId, active)
    }
    nameOnlyContext = null
    val key = agent?.let { keyFor(it, logicalSessionId, modelIsActual) }
    if (key == null) {
      // Nothing supported to follow: keep the last agent, or start from the manual pick if nothing has been shown yet.
      if (currentKey == null) {
        val expectedRouteGeneration = routeGeneration
        scope.launch { useManualAgent(expectedRouteGeneration) }
      }
      return updateActivity(active)
    }
    val sameAgent = contextKey?.let { it.provider == key.provider && it.profile == key.profile } == true
    contextKey = key
    if (manualOverride && sameAgent) return
    manualOverride = false
    applyAgent(agent, logicalSessionId, active, modelIsActual)
  }

  private fun applyAgent(agent: ChatAgent, logicalSessionId: String?, active: Boolean, modelIsActual: Boolean) {
    if (released) return
    val key = keyFor(agent, logicalSessionId, modelIsActual) ?: return updateActivity(active)
    val wasNameOnly = state.value.nameOnlyAgent != null
    if (wasNameOnly) state.value = state.value.copy(nameOnlyAgent = null)
    if (key == currentKey && (request != null || profileJob?.isActive == true)) {
      updateActivity(active)
      if (wasNameOnly) reconcileDemand()
      return
    }
    if (key.provider != currentKey?.provider) rememberAgent(key.provider)
    currentKey = key
    val generation = ++agentGeneration
    profileJob?.cancel()
    profileJob = null
    stopDemand()
    setRequest(null)
    state.value = state.value.copy(
      provider = key.provider,
      result = lastResults[key.cacheKey],
      unavailableReason = null,
      active = active,
      demandEligible = false,
    )
    val model = agent.model?.takeIf { modelIsActual }?.let { UsageModelSelection(it, ModelSelectionProvenance.ActualExecution) }
    profileJob = scope.launch {
      try {
        usage.observeProfileLookup(key.provider, agent.profile, logicalSessionId).collect { profile ->
          if (generation != agentGeneration || released) return@collect
          val next = UsageRequest(profile, logicalSessionId, model)
          if (request == next) return@collect
          stopDemand()
          setRequest(next)
          reconcileDemand()
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        diagnostics.record("usage_context_profile_lookup_failed")
        if (generation == agentGeneration) {
          stopDemand()
          setRequest(null)
          state.value = state.value.copy(unavailableReason = "The provider profile is unavailable", demandEligible = false)
        }
      }
    }
  }

  /** Drops the request tied to the closed database but keeps the agent and its readings on screen; usage is per account. */
  private fun clearAgent() {
    ++agentGeneration
    profileJob?.cancel()
    profileJob = null
    stopDemand()
    request = null
    currentKey = null
    contextKey = null
    manualOverride = false
    state.value = state.value.copy(
      unavailableReason = null,
      active = false,
      demandEligible = false,
      popupDismissalToken = ++popupToken,
      requestVersion = state.value.requestVersion + 1,
    )
  }

  /** Names [agentId] on the card and stops asking for usage; the last usage agent and reading stay for later. */
  private fun showNameOnly(agentId: String, active: Boolean) {
    state.value = state.value.copy(nameOnlyAgent = agentId, active = active)
    reconcileDemand()
  }

  private fun rememberAgent(provider: UsageProvider) {
    scope.launch {
      try {
        lastUsage.setLastAgent(provider.agentId())
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        diagnostics.record("usage_last_agent_write_failed")
      }
    }
  }

  private fun setRequest(next: UsageRequest?) {
    request = next
    state.value = state.value.copy(requestVersion = state.value.requestVersion + 1)
  }

  private fun updateActivity(active: Boolean) {
    if (state.value.active == active) return
    state.value = state.value.copy(active = active)
    reconcileDemand()
  }

  private fun reconcileDemand() {
    val currentRequest = request
    val eligible = currentRequest != null && eligible() && state.value.provider != null
    if (state.value.demandEligible != eligible) state.value = state.value.copy(demandEligible = eligible)
    reconcilePresentationTime(eligible)
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
      val generation = agentGeneration
      val cacheKey = currentKey?.cacheKey
      val observation = ++observationGeneration
      demandJob = scope.launch {
        try {
          usage.observeDemand(ownerId).collectLatest { result ->
            if (generation == agentGeneration && observation == observationGeneration && cacheKey != null) accept(cacheKey, result)
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          diagnostics.record("usage_demand_observation_failed")
          if (observation == observationGeneration) {
            state.value = state.value.copy(unavailableReason = "Usage observation failed")
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

  /** A failed or unverified read never replaces a reading the card already shows; sign-in and real data always do. */
  private fun accept(cacheKey: Pair<UsageProvider, String?>, result: UsageResult) {
    val previous = lastResults[cacheKey]
    val shown = if (cacheKey.first == UsageProvider.Devin || result.isDefinitive() || previous?.isDefinitive() != true) result else previous
    lastResults[cacheKey] = shown
    state.value = state.value.copy(result = shown, unavailableReason = null)
  }

  private fun UsageResult.isDefinitive() = when (this) {
    is UsageResult.Snapshot, is UsageResult.Partial, is UsageResult.EmptySuccess, is UsageResult.SignInRequired -> true
    is UsageResult.Unavailable, is UsageResult.Unsupported, is UsageResult.TransientFailure, is UsageResult.Throttled -> false
  }

  private fun reconcilePresentationTime(eligible: Boolean) {
    if (!eligible) {
      stopPresentationTime()
      return
    }
    if (presentationTimeJob?.isActive == true) return
    presentationTimeState.value = clock.now()
    presentationTimeJob = scope.launch {
      try {
        while (true) {
          delay(60_000)
          presentationTimeState.value = clock.now()
        }
      } finally {
        if (presentationTimeJob === coroutineContext[Job]) presentationTimeJob = null
      }
    }
  }

  private fun stopPresentationTime() {
    presentationTimeJob?.cancel()
    presentationTimeJob = null
  }

  private fun stopDemand() {
    stopPresentationTime()
    ++observationGeneration
    demandJob?.cancel()
    demandJob = null
    if (demandActive) usage.releaseDemand(ownerId)
    demandActive = false
  }

  private fun isCurrentRoute(generation: Long): Boolean {
    val entry = routeEntry ?: return false
    val database = session.state.value as? DatabaseSessionState.Open ?: return false
    return !released && generation == routeGeneration && navigator.top.entryToken == entry.entryToken &&
      (entry.sessionId == null || entry.sessionId == database.sessionId)
  }

  private fun eligible() = !released && state.value.nameOnlyAgent == null && mainVisible && !minimized && !settingsRoute && (inlineSidebarVisible || drawerOpen) && cardHostAttached

  private fun dismissPopupIfTriggerLost(wasVisible: Boolean) {
    if (wasVisible && !eligible()) state.value = state.value.copy(popupDismissalToken = ++popupToken)
  }

  private fun keyFor(agent: ChatAgent, logicalSessionId: String?, modelIsActual: Boolean): AgentKey? =
    providerFor(agent.agentId)?.let { AgentKey(it, agent.profile, logicalSessionId, agent.model?.takeIf { modelIsActual }) }

  private fun providerFor(agentId: String): UsageProvider? = when (agentId.lowercase()) {
    "codex" -> UsageProvider.Codex
    "claude" -> UsageProvider.Claude
    "devin" -> UsageProvider.Devin
    else -> null
  }

  private data class AgentKey(val provider: UsageProvider, val profile: String?, val logicalSessionId: String?, val model: String?) {
    val cacheKey get() = provider to profile
  }

  private data class ContextDetail(
    val header: WorkItem,
    val detail: dev.skillbill.desktop.core.domain.model.Detail?,
    val options: dev.skillbill.desktop.core.domain.model.RunOptions?,
  )
}
