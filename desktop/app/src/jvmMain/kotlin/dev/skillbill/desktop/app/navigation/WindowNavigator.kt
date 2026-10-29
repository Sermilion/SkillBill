package dev.skillbill.desktop.app.navigation

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.AppNavigator
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import dev.skillbill.desktop.core.ui.navigation.HistoryRoute
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute
import dev.skillbill.desktop.core.ui.navigation.WorkRoute
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * One back-stack entry. Work, History and Detail are made under the open session, so a new session gives every one of them a new
 * content key and clears the old entries' ViewModels; Settings is made under none and survives a switch.
 */
data class RouteEntry(val route: AppRoute, val sessionId: Long?) {
  val contentKey: String get() = "${route.entryKey}#$sessionId"
}

/** The window's back stack. Work and History are roots; a detail sits on one of them, and Settings sits on top of either. */
@Inject
@SingleIn(WindowScope::class)
class WindowNavigator(private val session: DatabaseSessionController, private val selection: SelectionHolder) : AppNavigator {
  private var sessionId: Long? = (session.state.value as? DatabaseSessionState.Open)?.sessionId

  val backStack: SnapshotStateList<RouteEntry> = mutableStateListOf(RouteEntry(WorkRoute(null), sessionId))

  val top: RouteEntry get() = backStack.last()

  override fun navigate(route: AppRoute) {
    val entry = RouteEntry(route, if (route is SettingsRoute) null else sessionId)
    when (route) {
      is WorkRoute, HistoryRoute -> {
        backStack.clear()
        backStack += entry
      }
      // Stepping between items in a detail, or reopening one, replaces the detail instead of stacking a second one. An entry for
      // the same item further down (under Settings) leaves first, so two entries never share a content key.
      is DetailRoute -> {
        val last = top
        backStack.removeAll { it !== last && it.contentKey == entry.contentKey }
        if (top.route is DetailRoute) backStack[backStack.lastIndex] = entry else backStack += entry
      }
      is SettingsRoute -> if (top.route is SettingsRoute) backStack[backStack.lastIndex] = entry else backStack += entry
    }
  }

  /** Leaves the top entry; the root never leaves. */
  fun pop(): Boolean {
    if (backStack.size <= 1) return false
    backStack.removeAt(backStack.lastIndex)
    return true
  }

  /** Rebinds each session-owned entry to the new generation while keeping the window's route history. */
  suspend fun followSession() {
    session.state.collect { state ->
      val next = (state as? DatabaseSessionState.Open)?.sessionId
      if (next == sessionId) return@collect
      sessionId = next
      backStack.indices.forEach { index ->
        val entry = backStack[index]
        backStack[index] = if (entry.route is SettingsRoute) entry else RouteEntry(entry.route, next)
      }
      selection.select(null)
    }
  }
}
