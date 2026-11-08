package dev.skillbill.desktop.app.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.navigation.Sidebar
import dev.skillbill.desktop.core.designsystem.navigation.SidebarItem
import dev.skillbill.desktop.core.designsystem.navigation.SidebarSection
import dev.skillbill.desktop.core.designsystem.navigation.WorkspaceSwitcher
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.ui.pane.MOD_KEY
import dev.skillbill.desktop.core.ui.status.boardSectionLead
import dev.skillbill.desktop.core.ui.status.boardSectionLeadFill
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import dev.skillbill.desktop.core.ui.navigation.HistoryRoute
import dev.skillbill.desktop.core.ui.navigation.NewTaskRoute
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
  favorites: List<FavoriteSidebarEntry>,
  specFavorites: List<FavoriteSpecSidebarEntry>,
  specsOpen: Boolean,
  terminalOpen: Boolean,
  chatOpen: Boolean,
  newTaskDraft: Boolean,
  newTaskOpenChats: Int,
  contributions: List<SidebarContribution>,
  usage: @Composable () -> Unit = {},
  onNavigate: (AppRoute) -> Unit,
  onOpenCommandMenu: () -> Unit,
  onToggleSpecs: () -> Unit,
  onToggleTerminal: () -> Unit,
  onToggleChat: () -> Unit,
  onShowShortcuts: () -> Unit,
  onRemoveFavorite: (dev.skillbill.desktop.core.domain.settings.FavoriteWorkKey) -> Unit,
  onRemoveSpecFavorite: (dev.skillbill.desktop.core.domain.settings.FavoriteSpecKey) -> Unit,
  onOpenSpecFavorite: (projectId: String, path: String) -> Unit,
  qaDeviceSwitcher: @Composable () -> Unit = {},
) {
  var boardExpanded by rememberSaveable { mutableStateOf(true) }
  Sidebar(
    top = {
      WorkspaceSwitcher("Skill Bill") {
        SbIconButton(LucideIcons.search, "Command menu", onOpenCommandMenu, size = SbControlSize.Small, shortcut = "$MOD_KEY K")
      }
    },
    footer = {
      usage()
      SidebarItem("Specs", onToggleSpecs, icon = LucideIcons.fileText, on = specsOpen, tooltip = "Specs pane", shortcut = "G S")
      SidebarItem("Terminal", onToggleTerminal, icon = LucideIcons.terminal, on = terminalOpen, tooltip = "Terminal", shortcut = "⌃ `")
      SidebarItem("Chats", onToggleChat, icon = LucideIcons.bot, on = chatOpen, tooltip = "Chat pane")
      SidebarItem("Settings", { onNavigate(SettingsRoute(SettingsPageId.App)) }, icon = LucideIcons.sliders, tooltip = "Settings", shortcut = "G ,")
      SidebarItem("Keyboard shortcuts", onShowShortcuts, icon = LucideIcons.keyboard, tooltip = "Keyboard shortcuts", shortcut = "?")
      qaDeviceSwitcher()
    },
  ) {
    SidebarItem(
      "New task",
      { onNavigate(NewTaskRoute) },
      icon = LucideIcons.pencil,
      count = newTaskOpenChats.takeIf { it > 1 },
      attention = if (newTaskOpenChats <= 1 && newTaskDraft && topRoute != NewTaskRoute) 1 else null,
      on = topRoute == NewTaskRoute,
      tooltip = "New task (G N)",
      shortcut = "G N",
    )
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
    SidebarSection("Favorites", expanded = true, onToggle = {}, collapsible = false) {
      Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
        favorites.forEach { entry ->
          when (entry) {
            is FavoriteSidebarEntry.Resolved -> SidebarItem(
              entry.item.title,
              { onNavigate(DetailRoute(entry.item)) },
              icon = LucideIcons.star,
              tooltip = "${entry.item.title}, ${entry.item.lifecycle.name}",
              trailingText = entry.item.lifecycle.name,
            )
            else -> FavoriteUnavailable(entry, onRemoveFavorite)
          }
        }
        specFavorites.forEach { entry ->
          when (entry) {
            is FavoriteSpecSidebarEntry.Resolved -> SidebarItem(
              entry.title,
              { onOpenSpecFavorite(entry.key.projectId, entry.key.path) },
              icon = LucideIcons.star,
              tooltip = "${entry.title}, ${entry.issueKey.orEmpty()}",
              trailingText = entry.issueKey.orEmpty(),
            )
            else -> SpecFavoriteUnavailable(entry, onRemoveSpecFavorite)
          }
        }
      }
    }
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

@Composable
private fun FavoriteUnavailable(
  entry: FavoriteSidebarEntry,
  onRemove: (dev.skillbill.desktop.core.domain.settings.FavoriteWorkKey) -> Unit,
) {
  val key = entry.key
  val status = when (entry) {
    is FavoriteSidebarEntry.Loading -> "Loading"
    is FavoriteSidebarEntry.Missing -> "Missing"
    is FavoriteSidebarEntry.Failed -> "Could not load"
    is FavoriteSidebarEntry.OtherDatabase -> "Other database"
    is FavoriteSidebarEntry.Resolved -> return
  }
  val identity = "${key.databaseFile.path} · ${key.workItemId}"
  Row(Modifier.fillMaxWidth().height(28.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText("$status · ${key.workItemId}", Modifier.weight(1f), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted), maxLines = 1)
    SbIconButton(LucideIcons.trash, "Remove $status favorite $identity", { onRemove(key) }, size = SbControlSize.Small)
  }
}

@Composable
private fun SpecFavoriteUnavailable(
  entry: FavoriteSpecSidebarEntry,
  onRemove: (dev.skillbill.desktop.core.domain.settings.FavoriteSpecKey) -> Unit,
) {
  val key = entry.key
  val status = when (entry) {
    is FavoriteSpecSidebarEntry.Loading -> "Loading"
    is FavoriteSpecSidebarEntry.Missing -> "Missing"
    is FavoriteSpecSidebarEntry.Failed -> "Could not load"
    is FavoriteSpecSidebarEntry.Resolved -> return
  }
  val identity = "${key.projectId} · ${key.path}"
  Row(Modifier.fillMaxWidth().height(28.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText("$status · ${key.path.substringAfterLast('/')}", Modifier.weight(1f), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted), maxLines = 1)
    SbIconButton(LucideIcons.trash, "Remove $status favorite $identity", { onRemove(key) }, size = SbControlSize.Small)
  }
}
