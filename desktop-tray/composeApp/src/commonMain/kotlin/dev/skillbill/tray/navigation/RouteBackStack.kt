package dev.skillbill.tray.navigation

import androidx.compose.runtime.mutableStateListOf
import dev.skillbill.tray.core.ui.navigation.AppRoute

/**
 * One window's typed back stack: a section root, and above it at most one detail, because choosing another item replaces the
 * selection instead of stacking a second one. It is snapshot state, so what the navigation display shows follows it.
 */
class RouteBackStack(root: AppRoute) {
  private val routes = mutableStateListOf(root)

  val entries: List<AppRoute> get() = routes
  val root: AppRoute get() = routes.first()
  val top: AppRoute get() = routes.last()

  /** Shows [route] above the root, replacing whatever detail was there. Opening what is already on top changes nothing. */
  fun open(route: AppRoute) {
    if (routes.size == 2 && routes[1] == route) return
    while (routes.size > 1) routes.removeAt(routes.lastIndex)
    routes.add(route)
  }

  /** Removes the top route; the root never leaves. Returns whether a route was removed. */
  fun back(): Boolean {
    if (routes.size <= 1) return false
    routes.removeAt(routes.lastIndex)
    return true
  }

  /** Replaces the whole stack; [next] starts with the root. */
  fun replaceAll(next: List<AppRoute>) {
    require(next.isNotEmpty() && next.size <= MAX_DEPTH) { "A route stack holds a root and at most one detail." }
    routes.clear()
    routes.addAll(next)
  }

  companion object {
    const val MAX_DEPTH = 2
  }
}
