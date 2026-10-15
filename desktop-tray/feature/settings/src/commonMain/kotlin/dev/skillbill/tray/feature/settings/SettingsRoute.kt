package dev.skillbill.tray.feature.settings

import dev.skillbill.tray.core.ui.navigation.AppRoute
import dev.skillbill.tray.core.ui.navigation.DatabaseGenerationRef
import kotlinx.serialization.Serializable

/** The settings destination. Settings read no database work, so the route belongs to no generation. */
@Serializable
data object SettingsRoute : AppRoute {
  override val entryKey: String get() = "settings"
  override val generation: DatabaseGenerationRef? get() = null
}
