package dev.skillbill.tray.navigation

import androidx.savedstate.SavedState
import androidx.savedstate.serialization.SavedStateConfiguration
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import dev.skillbill.tray.core.ui.navigation.AppRoute
import dev.skillbill.tray.core.ui.navigation.DatabaseGenerationRef
import dev.skillbill.tray.core.ui.navigation.EntryArgsOutcome
import dev.skillbill.tray.feature.detail.DetailArgs
import dev.skillbill.tray.feature.detail.HistoryDetailRoute
import dev.skillbill.tray.feature.detail.WorkDetailRoute
import dev.skillbill.tray.feature.history.HistoryRoute
import dev.skillbill.tray.feature.settings.SettingsRoute
import dev.skillbill.tray.feature.work.WorkRoute
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/** The saved form of one window's stack. Only routes are saved: no ViewModel, component, repository or callback. */
@Serializable
internal data class RouteStackSnapshot(val routes: List<AppRoute>)

/** Why a saved route was not restored. */
data class RestoreDiagnostic(val routeType: String?, val reason: String)

/** What restoring a saved stack came to. Every outcome carries a stack that is safe to show. */
sealed interface RouteRestoreOutcome {
  val routes: List<AppRoute>

  /** The saved stack, or its valid prefix; [dropped] says why anything after that prefix was not kept. */
  data class Restored(override val routes: List<AppRoute>, val dropped: List<RestoreDiagnostic> = emptyList()) : RouteRestoreOutcome

  /** Nothing saved could be used; [routes] is the safe root. */
  data class Reset(override val routes: List<AppRoute>, val diagnostic: RestoreDiagnostic) : RouteRestoreOutcome
}

/** The explicit saved-state serialization of the typed back stack: every route type is registered here, and nowhere else. */
object RouteSnapshots {
  val configuration: SavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
      polymorphic(AppRoute::class) {
        subclass(WorkRoute::class)
        subclass(HistoryRoute::class)
        subclass(WorkDetailRoute::class)
        subclass(HistoryDetailRoute::class)
        subclass(SettingsRoute::class)
      }
    }
  }

  fun encode(routes: List<AppRoute>): SavedState =
    encodeToSavedState(RouteStackSnapshot.serializer(), RouteStackSnapshot(routes), configuration)

  /** The routes saved in [saved], or null when it is not a readable snapshot (a route type this build no longer knows, say). */
  fun decode(saved: SavedState): List<AppRoute>? = try {
    decodeFromSavedState(RouteStackSnapshot.serializer(), saved, configuration).routes
  } catch (unreadable: IllegalArgumentException) {
    // SerializationException is one: an unknown route type, a missing field or a wrongly typed value.
    null
  } catch (unreadable: IllegalStateException) {
    null
  }

  /**
   * Restores [saved] against the database generation the app is at now. A route made under another generation is obsolete, a
   * detail that names no item is invalid, and the stack must start at a section root; anything unusable is dropped with its
   * reason, and a stack with no usable root becomes [fallback].
   */
  fun restore(saved: SavedState, current: DatabaseGenerationRef?, fallback: AppRoute): RouteRestoreOutcome {
    val decoded = decode(saved)
      ?: return RouteRestoreOutcome.Reset(listOf(fallback), RestoreDiagnostic(null, "The saved routes could not be read"))
    val kept = mutableListOf<AppRoute>()
    val dropped = mutableListOf<RestoreDiagnostic>()
    for (route in decoded) {
      val problem = when {
        kept.size >= RouteBackStack.MAX_DEPTH -> "A stack holds a root and one detail"
        else -> unusable(route, kept.firstOrNull(), current)
      }
      if (problem != null) {
        dropped += RestoreDiagnostic(route::class.simpleName, problem)
        break
      }
      kept += route
    }
    return if (kept.isEmpty()) {
      RouteRestoreOutcome.Reset(listOf(fallback), dropped.firstOrNull() ?: RestoreDiagnostic(null, "The saved stack was empty"))
    } else {
      RouteRestoreOutcome.Restored(kept, dropped)
    }
  }

  /** [root] is the stack's first kept route, or null while [route] is the first one. */
  private fun unusable(route: AppRoute, root: AppRoute?, current: DatabaseGenerationRef?): String? {
    val generation = route.generation
    return when {
      generation != null && generation != current -> "Made for database generation ${generation.generation}"
      root == null && route !is WorkRoute && route !is HistoryRoute && route !is SettingsRoute -> "A stack starts at a section root"
      root != null && DetailArgs.from(route) is EntryArgsOutcome.Rejected -> "A detail needs a work item"
      root != null && !detailBelongsTo(route, root) -> "A detail belongs to the section it was opened from"
      else -> null
    }
  }

  private fun detailBelongsTo(detail: AppRoute, root: AppRoute): Boolean =
    (detail is WorkDetailRoute && root is WorkRoute) || (detail is HistoryDetailRoute && root is HistoryRoute)
}
