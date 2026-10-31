package dev.skillbill.desktop.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.skillbill.desktop.app.keyboard.MOD_KEY
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.navigation.Sidebar
import dev.skillbill.desktop.core.designsystem.navigation.SidebarItem
import dev.skillbill.desktop.core.designsystem.navigation.SidebarSection
import dev.skillbill.desktop.core.designsystem.navigation.WorkspaceSwitcher
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.ui.status.boardSectionLead
import dev.skillbill.desktop.core.ui.status.boardSectionLeadFill
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import dev.skillbill.desktop.core.ui.navigation.HistoryRoute
import dev.skillbill.desktop.core.ui.navigation.SettingsPageId
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute
import dev.skillbill.desktop.core.ui.navigation.SidebarContribution
import dev.skillbill.desktop.core.ui.navigation.WorkRoute

/**
 * The app sidebar. [sectionSizes] are the shared board's section sizes, hidden while the board is not loaded. Each Board item
 * counts the rows its section lists, so it matches that section's group header: a blocked phase sits under Needs you only, not
 * also under Standalone phases. Work counts every live row once (every section but Finished today).
 */
@Composable
fun ShellSidebar(
  topRoute: AppRoute,
  sectionSizes: Map<BoardSectionKind, Int>?,
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
      count = sectionSizes?.filterKeys { it != BoardSectionKind.Finished }?.values?.sum(),
      // The prototype keeps Work highlighted while a detail is open.
      on = topRoute == WorkRoute(null) || topRoute is DetailRoute,
      tooltip = "Work",
      shortcut = "G W",
    )
    SidebarItem("History", { onNavigate(HistoryRoute) }, icon = LucideIcons.history, on = topRoute == HistoryRoute, tooltip = "History", shortcut = "G H")
    SidebarSection("Board", boardExpanded, onToggle = { boardExpanded = !boardExpanded }) {
      BoardSectionKind.entries.forEach { kind ->
        val needsYou = kind == BoardSectionKind.NeedsYou
        val on = topRoute == WorkRoute(kind)
        // Same neutral tint as the sidebar's line icons, not the status palette.
        val leadTint = if (on) SkillBillTheme.colors.fg else SkillBillTheme.colors.fgMuted
        SidebarItem(
          sectionLabel(kind),
          { onNavigate(WorkRoute(kind)) },
          lead = { StatusIcon(boardSectionLead(kind), boardSectionLeadFill(kind), tint = leadTint) },
          count = if (needsYou) null else sectionSizes?.get(kind),
          attention = if (needsYou) sectionSizes?.get(kind) else null,
          on = on,
        )
      }
    }
    contributions.forEach { SidebarItem(it.label, it.onClick, icon = it.icon) }
  }
}
