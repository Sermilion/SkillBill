package dev.skillbill.tray.feature.work

import dev.skillbill.tray.core.ui.navigation.AppRoute
import dev.skillbill.tray.core.ui.navigation.DatabaseGenerationRef
import kotlinx.serialization.Serializable

/**
 * The live work list: the root of the work section. Its filter, selection and scroll anchor are not in the route; the window's
 * navigator keeps them per section so that leaving the section and coming back restores them. The entry key carries the
 * generation, so an entry made for an earlier database never survives into the next one.
 */
@Serializable
data class WorkRoute(override val generation: DatabaseGenerationRef? = null) : AppRoute {
  override val entryKey: String get() = "work:${generation?.generation}"
}
