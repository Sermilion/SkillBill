package dev.skillbill.desktop.app.command

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.skillbill.desktop.app.keyboard.TerminalTriggers
import dev.skillbill.desktop.app.navigation.WindowNavigator
import dev.skillbill.desktop.app.shell.ShellBoardState
import dev.skillbill.desktop.app.shell.sectionLabel
import dev.skillbill.desktop.app.shell.sectionLead
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.overlay.CommandItem
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.CommandContext
import dev.skillbill.desktop.core.ui.navigation.CommandSection
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.navigation.HistoryRoute
import dev.skillbill.desktop.core.ui.navigation.SettingsPageId
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute
import dev.skillbill.desktop.core.ui.navigation.ShellCommand
import dev.skillbill.desktop.core.ui.navigation.WorkRoute
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import dev.skillbill.desktop.core.ui.status.statusKind
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** The shell's own command groups, contributed through the same seam as every feature: Navigation, Panes, Work and Config. */
@Inject
@SingleIn(WindowScope::class)
class ShellCommandsEntry(
  private val navigator: WindowNavigator,
  private val shellPanes: ShellPanes,
  private val terminal: TerminalTriggers,
  private val board: WorkBoardRepository,
  private val boardState: ShellBoardState,
) : FeatureEntry {
  override fun renders(route: AppRoute): Boolean = false

  @Composable
  override fun Content(route: AppRoute) = Unit

  override fun commands(context: CommandContext): List<CommandSection> {
    val navigation = listOf(go("nav:work", "Go to Work", LucideIcons.target, "G W", WorkRoute(null))) +
      BoardSectionKind.entries.map { kind ->
        ShellCommand(CommandItem("section:${kind.name}", "Go to ${sectionLabel(kind)}", lead = { StatusIcon(sectionLead(kind), 0.5f) })) { navigator.navigate(WorkRoute(kind)) }
      } +
      go("nav:history", "Go to History", LucideIcons.history, "G H", HistoryRoute) +
      go("nav:settings", "Go to Settings", LucideIcons.sliders, "G ,", SettingsRoute(SettingsPageId.App))
    val paneCommands = listOf(
      ShellCommand(CommandItem("specs", if (shellPanes.specsOpen) "Close specs pane" else "Open specs pane", LucideIcons.fileText, "G S")) {
        shellPanes.specsOpen = !shellPanes.specsOpen
      },
      ShellCommand(CommandItem("term", if (terminal.open) "Close terminal" else "Open terminal", LucideIcons.terminal, "⌃ `"), terminal::toggle),
      ShellCommand(CommandItem("term-new", "New terminal tab", LucideIcons.plus, "⌃ ⇧ `"), terminal::newTab),
      ShellCommand(CommandItem("refresh", "Refresh", LucideIcons.refresh), board::refresh),
    )
    val work = boardState.view.value.lastLoaded?.items.orEmpty().map { item ->
      val key = rowKey(item)
      // The board row's status icon (`X.statusIcon`): plans saved while planning, otherwise subtask progress.
      val status = item.statusKind()
      val progress = (if (status == StatusKind.Planning) item.planningProgress else item.progress)
        ?.let { it.done.toFloat() / it.total.coerceAtLeast(1) }
      val command = CommandItem(
        "open:${item.id}",
        "$key ${item.title} ${item.repositoryName}",
        lead = { StatusIcon(status, progress) },
        displayKey = key,
        displayTitle = item.title,
        end = item.repositoryName,
      )
      ShellCommand(command) { navigator.navigate(DetailRoute(item)) }
    }
    val config = listOf(
      go("cfg:models", "Configure models", LucideIcons.cpu, null, SettingsRoute(SettingsPageId.Models)),
      go("cfg:compaction", "Configure compaction", LucideIcons.gauge, null, SettingsRoute(SettingsPageId.Compaction)),
      ShellCommand(CommandItem("shortcuts", "Keyboard shortcuts", LucideIcons.keyboard, "?")) { shellPanes.shortcutsOpen = true },
    )
    return listOf(
      CommandSection("Navigation", navigation),
      CommandSection("Panes", paneCommands),
      CommandSection("Work", work),
      CommandSection("Config", config),
    )
  }

  private fun go(id: String, label: String, icon: ImageVector?, shortcut: String?, route: AppRoute) =
    ShellCommand(CommandItem(id, label, icon, shortcut)) { navigator.navigate(route) }
}

@ContributesTo(WindowScope::class)
interface ShellCommandsEntryBinding {
  @Provides
  @IntoSet
  fun shellCommandsEntry(entry: ShellCommandsEntry): FeatureEntry = entry
}
