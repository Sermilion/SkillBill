package dev.skillbill.tray.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.skillbill.tray.application.defaultSelection
import dev.skillbill.tray.core.designsystem.amber
import dev.skillbill.tray.core.designsystem.border
import dev.skillbill.tray.core.designsystem.canvas
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.designsystem.orange
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.RuntimeBuildReason
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.ui.navigation.AppRoute
import dev.skillbill.tray.core.ui.navigation.DatabaseGenerationRef
import dev.skillbill.tray.core.ui.navigation.EntryArgsOutcome
import dev.skillbill.tray.core.ui.navigation.rememberEntryComponent
import dev.skillbill.tray.core.ui.navigation.rememberEntryViewModel
import dev.skillbill.tray.core.ui.startOfToday
import dev.skillbill.tray.feature.detail.DetailArgs
import dev.skillbill.tray.feature.detail.DetailEffect
import dev.skillbill.tray.feature.detail.DetailRoute
import dev.skillbill.tray.feature.detail.IncidentDetailUnavailablePanel
import dev.skillbill.tray.feature.detail.HistoryDetailRoute
import dev.skillbill.tray.feature.detail.WorkDetailRoute
import dev.skillbill.tray.feature.history.HistoryAction
import dev.skillbill.tray.feature.history.HistoryEffect
import dev.skillbill.tray.feature.history.HistoryRoute
import dev.skillbill.tray.feature.history.HistoryUiState
import dev.skillbill.tray.feature.history.historySection
import dev.skillbill.tray.feature.settings.SettingsRoute
import dev.skillbill.tray.feature.settings.SettingsScreen
import dev.skillbill.tray.feature.specs.SPECS_PANE_WIDTH
import dev.skillbill.tray.feature.specs.SpecsAction
import dev.skillbill.tray.feature.specs.SpecsRoute
import dev.skillbill.tray.feature.specs.SpecsViewModel
import dev.skillbill.tray.feature.work.WorkAction
import dev.skillbill.tray.feature.work.WorkEffect
import dev.skillbill.tray.feature.work.WorkRoute
import dev.skillbill.tray.feature.work.workSections
import dev.skillbill.tray.lifetime.SessionState
import dev.skillbill.tray.lifetime.WindowOwner
import dev.skillbill.tray.navigation.ListStatus
import dev.skillbill.tray.navigation.Section

/**
 * What an entry can ask of the application around it: open the settings window, close the window it is in, or refresh through
 * the shell, which re-reads the installed runtime's build record before anything reloads.
 */
private class WindowActions(val openSettings: () -> Unit, val closeWindow: () -> Unit, val refreshShell: () -> Unit)

private val LocalWindowActions = staticCompositionLocalOf<WindowActions> { error("Entries compose inside SkillBillNavHost.") }

/** What the detail pane shows while nothing is selected. It is a display-only route: it is never part of a saved stack. */
private data object NoSelection : AppRoute {
  override val entryKey: String get() = "no-selection"
  override val generation: DatabaseGenerationRef? get() = null
}

/**
 * One window's content: typed routes shown through navigation displays that carry the saveable-state-holder and ViewModelStore
 * decorators, so every entry has its own saved state and its own ViewModels. A main window keeps the sidebar and detail layout:
 * the section root is the sidebar, and what is on top of it is the detail. A window rooted at settings shows that one route.
 *
 * Nothing that names work renders unless a session is open and every route in the stack was made for it, so a window never shows
 * the previous database's routes or results under the next one. Entries live in the window's current [WindowOwner.entryStoreOwner];
 * when a database generation ends the window clears that store, which ends the old entries' ViewModels, and the content is keyed
 * on the replacement so every entry of the new generation starts fresh against it.
 */
@Composable
fun SkillBillNavHost(
  window: WindowOwner,
  session: SessionState,
  shellProblem: Problem?,
  onOpenSettings: () -> Unit,
  onCloseWindow: () -> Unit,
  modifier: Modifier = Modifier,
  onRefreshShell: () -> Unit = {},
  specs: SpecsViewModel? = null,
) {
  val actions = remember(onOpenSettings, onCloseWindow, onRefreshShell) { WindowActions(onOpenSettings, onCloseWindow, onRefreshShell) }
  CompositionLocalProvider(LocalViewModelStoreOwner provides window.entryStoreOwner, LocalWindowActions provides actions) {
    key(window.entryStoreOwner) {
      val navigator = window.navigator
      val routes = navigator.stack.entries
      // A main window's routes are made under a generation; one without was made while a switch was under way, and the session
      // state collected here can trail the navigator reset by a frame, so only an exact match renders.
      val renderable = session is SessionState.Active &&
        routes.all { it.generation == session.ref || navigator.section == null && it.generation == null }
      if (session is SessionState.Refused && session.problem.reason == RuntimeBuildReason.INCIDENT_DETAIL_UNAVAILABLE) {
        IncidentDetailUnavailablePanel(session.problem, onRefreshShell, modifier.fillMaxSize())
      } else if (session is SessionState.Refused) {
        MessagePane(
          "Skill Bill runtime unavailable",
          "Install a matched runtime and desktop pair with ${session.problem.reinstallCommand}, then retry.",
          "Retry",
          modifier.fillMaxSize(),
          onRefreshShell,
        )
      } else if (!renderable) {
        MessagePane("Opening your database", "Skill Bill is switching to the selected database.", null, modifier.fillMaxSize())
      } else if (navigator.section == null) {
        NavigationPane(routes, onBack = onCloseWindow, window, modifier.fillMaxSize())
      } else {
        Row(modifier.fillMaxSize().background(canvas)) {
          NavigationPane(listOf(routes.first()), onBack = {}, window, Modifier.width(SIDEBAR_PANE_WIDTH).fillMaxHeight())
          Box(Modifier.width(1.dp).fillMaxHeight().background(border))
          Column(Modifier.weight(1f).fillMaxHeight()) {
            val status = navigator.status
            // A refused runtime build explains every other read failure, and only a shell refresh can clear it.
            val runtimeRefusal = shellProblem?.takeIf { it.runtimeBuild != null }
            val problem = runtimeRefusal ?: status.problem ?: shellProblem
            val retry = if (runtimeRefusal != null) onRefreshShell else { { onRefreshShell(); status.onRefresh() } }
            if (problem != null) ProblemBanner(problem, status, retry)
            NavigationPane(
              routes.drop(1).ifEmpty { listOf(NoSelection) },
              onBack = { navigator.back() },
              window,
              Modifier.weight(1f).fillMaxWidth(),
            )
          }
          if (specs != null) SpecsPaneHost(specs, navigator.selected)
        }
      }
    }
  }
}

private val SIDEBAR_PANE_WIDTH = 250.dp

/**
 * The spec pane to the right of the detail, while it is open. The sidebar's selection goes in read-only; nothing here opens a
 * detail or assigns navigator state, so the main panel keeps following the left sidebar alone.
 */
@Composable
private fun RowScope.SpecsPaneHost(specs: SpecsViewModel, selected: WorkIdentity?) {
  val state by specs.state.collectAsState()
  LaunchedEffect(specs, selected) { specs.onAction(SpecsAction.SelectedWorkChanged(selected)) }
  if (state.paneOpen) {
    Box(Modifier.width(1.dp).fillMaxHeight().background(border))
    SpecsRoute(specs, Modifier.width(SPECS_PANE_WIDTH).fillMaxHeight())
  }
}

@Composable
private fun NavigationPane(
  routes: List<AppRoute>,
  onBack: () -> Unit,
  window: WindowOwner,
  modifier: Modifier,
) {
  NavDisplay(
    backStack = routes,
    modifier = modifier,
    onBack = onBack,
    entryDecorators = listOf(
      rememberSaveableStateHolderNavEntryDecorator<AppRoute>(),
      rememberViewModelStoreNavEntryDecorator<AppRoute>(),
    ),
    // Selecting another item replaces the detail in place, as it always has; no slide between entries.
    transitionSpec = { ContentTransform(targetContentEnter = EnterTransition.None, initialContentExit = ExitTransition.None) },
    popTransitionSpec = { ContentTransform(targetContentEnter = EnterTransition.None, initialContentExit = ExitTransition.None) },
    entryProvider = { route -> NavEntry(route, contentKey = route.entryKey) { current -> EntryContent(current, window) } },
  )
}

/** The central entry adapters: one place that says which composable each route opens. */
@Composable
private fun EntryContent(route: AppRoute, window: WindowOwner) {
  when (route) {
    is WorkRoute -> WorkEntry(window)
    is HistoryRoute -> HistoryEntry(window)
    is WorkDetailRoute, is HistoryDetailRoute -> DetailEntry(route, window)
    SettingsRoute -> SettingsEntry(window)
    NoSelection -> EmptyDetailEntry(window)
    else -> MessagePane(
      "This screen can't be opened",
      "${route::class.simpleName.orEmpty()} is not a screen this version knows.",
      "Back to list",
      Modifier.fillMaxSize(),
    ) { window.navigator.back() }
  }
}

@Composable
private fun WorkEntry(window: WindowOwner) {
  val navigator = window.navigator
  val actions = LocalWindowActions.current
  val memory = navigator.memory(Section.WORK)
  val component = rememberEntryComponent { window.entries.create() }
  val work = rememberEntryViewModel("work") { component.viewModels.work() }
  val state by work.state.collectAsState()
  val today by startOfToday()
  val scroll = rememberLazyListState(memory.scrollIndex, memory.scrollOffset)
  var opened by remember { mutableStateOf(false) }

  LaunchedEffect(work, memory.filter) { work.onAction(WorkAction.FilterChanged(memory.filter)) }
  LaunchedEffect(work) {
    work.effects.collect { effect -> if (effect is WorkEffect.OpenDetail) navigator.openDetail(effect.identity) }
  }
  LaunchedEffect(state.loaded, state.liveProblem, state.stale, state.hasMoreLive) {
    navigator.status = ListStatus(
      loading = !state.loaded,
      failed = state.liveProblem != null,
      hasMore = state.hasMoreLive,
      problem = state.liveProblem,
      stale = state.stale,
      onRefresh = { work.onAction(WorkAction.Refresh) },
    )
  }
  // The first live item opens once the sections are read, unless something is selected already.
  val initial = if (!opened && navigator.selected == null) defaultSelection(state, HistoryUiState()) else null
  LaunchedEffect(initial) {
    if (initial != null && navigator.openDetail(initial)) opened = true
  }

  // A section's retry reloads through the shell too, so it re-reads the installed runtime's build record.
  val onAction: (WorkAction) -> Unit = { action ->
    if (action is WorkAction.Retry || action == WorkAction.Refresh) actions.refreshShell()
    work.onAction(action)
  }
  Sidebar(navigator, memory, scroll, actions.openSettings) {
    workSections(state, today, memory.filter.isNotBlank(), navigator.selected, scroll, onAction)
  }
}

@Composable
private fun HistoryEntry(window: WindowOwner) {
  val navigator = window.navigator
  val actions = LocalWindowActions.current
  val memory = navigator.memory(Section.HISTORY)
  val component = rememberEntryComponent { window.entries.create() }
  val history = rememberEntryViewModel("history") { component.viewModels.history() }
  val state by history.state.collectAsState()
  val scroll = rememberLazyListState(memory.scrollIndex, memory.scrollOffset)

  LaunchedEffect(history, memory.filter) { history.onAction(HistoryAction.FilterChanged(memory.filter)) }
  LaunchedEffect(history) {
    history.effects.collect { effect -> if (effect is HistoryEffect.OpenDetail) navigator.openDetail(effect.identity) }
  }
  LaunchedEffect(state.list) { navigator.status = ListStatus(loading = false, onRefresh = { history.onAction(HistoryAction.Retry) }) }

  val onAction: (HistoryAction) -> Unit = { action ->
    if (action == HistoryAction.Retry) actions.refreshShell()
    history.onAction(action)
  }
  Sidebar(navigator, memory, scroll, actions.openSettings) {
    historySection(state, navigator.selected, scroll, onAction)
  }
}

@Composable
private fun DetailEntry(route: AppRoute, window: WindowOwner) {
  when (val outcome = DetailArgs.from(route)) {
    is EntryArgsOutcome.Rejected -> MessagePane(
      "This item can't be opened",
      "${outcome.routeType}: ${outcome.reason}.",
      "Back to list",
      Modifier.fillMaxSize(),
    ) { window.navigator.back() }
    is EntryArgsOutcome.Valid -> {
      val component = rememberEntryComponent { window.entries.create() }
      val detail = rememberEntryViewModel("detail") { component.viewModels.detail(outcome.args) }
      LaunchedEffect(detail) {
        detail.effects.collect { effect ->
          if (effect is DetailEffect.SelectSuccessor) window.navigator.selectSuccessor(effect.from, effect.successor)
        }
      }
      DetailRoute(
        detail,
        Modifier.fillMaxSize(),
        onBackToList = { window.navigator.back() },
        onRefresh = LocalWindowActions.current.refreshShell,
      )
    }
  }
}

@Composable
private fun SettingsEntry(window: WindowOwner) {
  val component = rememberEntryComponent { window.entries.create() }
  val settings = rememberEntryViewModel("settings") { component.viewModels.settings() }
  SettingsScreen(settings, onClose = LocalWindowActions.current.closeWindow)
}

@Composable
private fun EmptyDetailEntry(window: WindowOwner) {
  val status = window.navigator.status
  val actions = LocalWindowActions.current
  val failed = status.failed
  val title = if (failed) "Couldn't load your work" else if (status.loading) "Loading your work" else if (status.hasMore) "More work is available" else "All quiet"
  val message = if (failed) "Check the database path in Settings, then try refreshing."
  else if (status.loading) "Fetching goals, standalone phases, and history."
  else "Select a goal, standalone phase, or history item in the sidebar to see its details."
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 380.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(title, color = ink, style = MaterialTheme.typography.titleLarge)
      Text(message, color = muted, style = MaterialTheme.typography.bodyMedium)
      if (failed) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { actions.refreshShell(); status.onRefresh() }) { Text("Refresh") }
        OutlinedButton(onClick = actions.openSettings) { Text("Settings") }
      }
    }
  }
}

@Composable
private fun ProblemBanner(problem: Problem, status: ListStatus, onRetry: () -> Unit) {
  Row(Modifier.fillMaxWidth().background(orange.copy(alpha = .10f)).padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(problem.summary, color = amber, style = MaterialTheme.typography.bodyMedium)
      problem.runtimeBuild?.let { build ->
        Text("Installed runtime: ${build.installedIdentity ?: "none"}", color = muted, style = MaterialTheme.typography.bodySmall)
        Text("This app: ${build.desktopIdentity}", color = muted, style = MaterialTheme.typography.bodySmall)
        Text("Install a matched pair with ${build.reinstallCommand}, then retry.", color = muted, style = MaterialTheme.typography.bodySmall)
      }
      if (status.stale) Text("Showing the last successful update.", color = muted, style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = onRetry) { Text("Retry", color = amber) }
  }
}

/** A bounded outcome for a route that cannot be shown: what happened, and, when there is one, the way back. */
@Composable
private fun MessagePane(title: String, message: String, actionLabel: String?, modifier: Modifier, onAction: () -> Unit = {}) {
  Box(modifier.background(canvas), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 380.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(title, color = ink, style = MaterialTheme.typography.titleLarge)
      Text(message, color = muted, style = MaterialTheme.typography.bodyMedium)
      if (actionLabel != null) Button(onClick = onAction) { Text(actionLabel) }
    }
  }
}
