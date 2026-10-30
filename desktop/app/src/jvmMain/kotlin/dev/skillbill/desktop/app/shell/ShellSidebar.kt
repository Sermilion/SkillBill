package dev.skillbill.desktop.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.skillbill.desktop.app.keyboard.MOD_KEY
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.navigation.Sidebar
import dev.skillbill.desktop.core.designsystem.navigation.SidebarItem
import dev.skillbill.desktop.core.designsystem.navigation.SidebarSection
import dev.skillbill.desktop.core.designsystem.navigation.WorkspaceSwitcher
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.BoardCounts
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import dev.skillbill.desktop.core.ui.navigation.HistoryRoute
import dev.skillbill.desktop.core.ui.navigation.SettingsPageId
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute
import dev.skillbill.desktop.core.ui.navigation.SidebarContribution
import dev.skillbill.desktop.core.ui.navigation.WorkRoute

/**
 * The app sidebar. [counts] are the shared board's runtime counts, hidden while the board is not loaded; Work counts the live
 * items the runtime lists (goals, standalone phases and operations).
 */
@Composable
fun ShellSidebar(
  topRoute: AppRoute,
  counts: BoardCounts?,
  specsOpen: Boolean,
  terminalOpen: Boolean,
  contributions: List<SidebarContribution>,
  onNavigate: (AppRoute) -> Unit,
  onOpenCommandMenu: () -> Unit,
  onToggleSpecs: () -> Unit,
  onToggleTerminal: () -> Unit,
  onShowShortcuts: () -> Unit,
) {
  var boardExpanded by rememberSaveable { mutableStateOf(true) }
  Sidebar(
    top = {
      WorkspaceSwitcher("Skill Bill") {
        SbIconButton(LucideIcons.search, "Command menu", onOpenCommandMenu, size = SbControlSize.Small, shortcut = "$MOD_KEY K")
      }
    },
    footer = {
      SidebarItem("Specs", onToggleSpecs, icon = LucideIcons.fileText, on = specsOpen, tooltip = "Specs pane", shortcut = "G S")
      SidebarItem("Terminal", onToggleTerminal, icon = LucideIcons.terminal, on = terminalOpen, tooltip = "Terminal", shortcut = "⌃ `")
      SidebarItem("Settings", { onNavigate(SettingsRoute(SettingsPageId.App)) }, icon = LucideIcons.sliders, tooltip = "Settings", shortcut = "G ,")
      SidebarItem("Keyboard shortcuts", onShowShortcuts, icon = LucideIcons.keyboard, tooltip = "Keyboard shortcuts", shortcut = "?")
    },
  ) {
    SidebarItem(
      "Work",
      { onNavigate(WorkRoute(null)) },
      icon = LucideIcons.target,
      count = counts?.let { it.goals + it.livePhases + it.liveOperations },
      // The prototype keeps Work highlighted while a detail is open.
      on = topRoute == WorkRoute(null) || topRoute is DetailRoute,
      tooltip = "Work",
      shortcut = "G W",
    )
    SidebarItem("History", { onNavigate(HistoryRoute) }, icon = LucideIcons.history, on = topRoute == HistoryRoute, tooltip = "History", shortcut = "G H")
    SidebarSection("Board", boardExpanded, onToggle = { boardExpanded = !boardExpanded }) {
      BoardSectionKind.entries.forEach { kind ->
        val needsYou = kind == BoardSectionKind.NeedsYou
        SidebarItem(
          sectionLabel(kind),
          { onNavigate(WorkRoute(kind)) },
          lead = { StatusIcon(sectionLead(kind), 0.5f) },
          count = if (needsYou) null else counts?.let { sectionCount(kind, it) },
          attention = if (needsYou) counts?.needsYou else null,
          on = topRoute == WorkRoute(kind),
        )
      }
    }
    contributions.forEach { SidebarItem(it.label, it.onClick, icon = it.icon) }
  }
}

/** The Board section's status glyph, the same one its group header leads with. */
internal fun sectionLead(kind: BoardSectionKind): StatusKind = when (kind) {
  BoardSectionKind.NeedsYou -> StatusKind.Blocked
  BoardSectionKind.Finished -> StatusKind.Completed
  BoardSectionKind.Goals, BoardSectionKind.Phases, BoardSectionKind.Operations -> StatusKind.Active
}

private fun sectionCount(kind: BoardSectionKind, counts: BoardCounts): Int = when (kind) {
  BoardSectionKind.NeedsYou -> counts.needsYou
  BoardSectionKind.Goals -> counts.goals
  BoardSectionKind.Phases -> counts.livePhases
  BoardSectionKind.Operations -> counts.liveOperations
  BoardSectionKind.Finished -> counts.doneWithinDay
}
