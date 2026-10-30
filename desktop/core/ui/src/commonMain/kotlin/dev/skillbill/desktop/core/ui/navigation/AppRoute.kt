package dev.skillbill.desktop.core.ui.navigation

import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlin.jvm.JvmInline

/** A typed Navigation 3 key. Routes are never serialized; [entryKey] names the screen an entry keeps its state under. */
sealed interface AppRoute {
  val entryKey: String
}

/** Work, scrolled to [section] when set. Every section shares the `work` entry, so changing it keeps the screen's filter. */
data class WorkRoute(val section: BoardSectionKind?) : AppRoute {
  override val entryKey: String get() = "work"
}

data object HistoryRoute : AppRoute {
  override val entryKey: String get() = "history"
}

/** The row travels with the route, so the detail header paints in the same frame the route opens. */
data class DetailRoute(val item: WorkItem) : AppRoute {
  override val entryKey: String get() = "detail:${item.id}"
}

data class SettingsRoute(val page: SettingsPageId) : AppRoute {
  override val entryKey: String get() = "settings"
}

/** A settings page. The settings and config screens own the page bodies and may add ids. */
@JvmInline
value class SettingsPageId(val id: String) {
  companion object {
    val App = SettingsPageId("app")
    val Models = SettingsPageId("models")
    val Compaction = SettingsPageId("compaction")
  }
}
