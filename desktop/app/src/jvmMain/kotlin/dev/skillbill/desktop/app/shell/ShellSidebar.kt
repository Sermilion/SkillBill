package dev.skillbill.desktop.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.skillbill.desktop.app.keyboard.MOD_KEY
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.navigation.Sidebar
import dev.skillbill.desktop.core.designsystem.navigation.SidebarItem
import dev.skillbill.desktop.core.designsystem.navigation.SidebarSection
import dev.skillbill.desktop.core.designsystem.navigation.WorkspaceSwitcher
import dev.skillbill.desktop.core.domain.model.BoardCounts
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.ui.navigation.AppRoute
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
        SbIconButton(LucideIcons.command, "Command menu", onOpenCommandMenu, shortcut = "$MOD_KEY K")
      }
    },
    footer = {
      SidebarItem("Specs", onToggleSpecs, icon = LucideIcons.fileText, on = specsOpen, shortcut = "G S")
      SidebarItem("Terminal", onToggleTerminal, icon = LucideIcons.terminal, on = terminalOpen, shortcut = "⌃ `")
      SidebarItem("Settings", { onNavigate(SettingsRoute(SettingsPageId.App)) }, icon = LucideIcons.sliders, shortcut = "G ,")
      SidebarItem("Keyboard shortcuts", onShowShortcuts, icon = LucideIcons.keyboard, shortcut = "?")
    },
  ) {
    SidebarItem(
      "Work",
      { onNavigate(WorkRoute(null)) },
      icon = LucideIcons.target,
      count = counts?.let { it.goals + it.livePhases + it.liveOperations },
      on = topRoute == WorkRoute(null),
      shortcut = "G W",
    )
    SidebarItem("History", { onNavigate(HistoryRoute) }, icon = LucideIcons.history, on = topRoute == HistoryRoute, shortcut = "G H")
    SidebarSection("Board", boardExpanded, onToggle = { boardExpanded = !boardExpanded }) {
      BoardSectionKind.entries.forEach { kind ->
        val needsYou = kind == BoardSectionKind.NeedsYou
        SidebarItem(
          sectionLabel(kind),
          { onNavigate(WorkRoute(kind)) },
          count = if (needsYou) null else counts?.let { sectionCount(kind, it) },
          attention = if (needsYou) counts?.needsYou else null,
          on = topRoute == WorkRoute(kind),
        )
      }
    }
    contributions.forEach { SidebarItem(it.label, it.onClick, icon = it.icon) }
  }
}

private fun sectionCount(kind: BoardSectionKind, counts: BoardCounts): Int = when (kind) {
  BoardSectionKind.NeedsYou -> counts.needsYou
  BoardSectionKind.Goals -> counts.goals
  BoardSectionKind.Phases -> counts.livePhases
  BoardSectionKind.Operations -> counts.liveOperations
  BoardSectionKind.Finished -> counts.doneWithinDay
}
