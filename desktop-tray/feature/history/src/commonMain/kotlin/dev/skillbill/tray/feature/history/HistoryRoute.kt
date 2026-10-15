package dev.skillbill.tray.feature.history

import dev.skillbill.tray.core.ui.navigation.AppRoute
import dev.skillbill.tray.core.ui.navigation.DatabaseGenerationRef
import kotlinx.serialization.Serializable

/** The finished-work list: the root of the history section. The navigator keeps its filter, selection and scroll anchor. */
@Serializable
data class HistoryRoute(override val generation: DatabaseGenerationRef? = null) : AppRoute {
  override val entryKey: String get() = "history:${generation?.generation}"
}
