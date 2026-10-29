package dev.skillbill.desktop.core.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.skillbill.desktop.core.designsystem.overlay.CommandItem
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.ui.di.WindowScope
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * What one feature module adds to the window. A feature binds its entry into the window's set with
 * `@ContributesTo(WindowScope::class) interface XEntryBinding { @Provides @IntoSet fun entry(entry: XFeatureEntry): FeatureEntry = entry }`,
 * and `:app` collects the set, so no feature module depends on another. Screen keys reach the entry through
 * [dev.skillbill.desktop.core.ui.input.ScreenKeyHandler] inside [Content].
 */
interface FeatureEntry {
  fun renders(route: AppRoute): Boolean

  @Composable
  fun Content(route: AppRoute)

  fun commands(context: CommandContext): List<CommandSection> = emptyList()

  val sidebarItems: List<SidebarContribution> get() = emptyList()

  val panes: List<PaneContribution> get() = emptyList()

  val settingsPages: List<SettingsPageContribution> get() = emptyList()
}

/** What a command menu is opened over: the window's top route and the focused or open item. */
class CommandContext(val topRoute: AppRoute?, val item: WorkItem?)

/** One command menu entry and what it runs; the menu closes before [run] is called. */
class ShellCommand(val item: CommandItem, val run: () -> Unit)

class CommandSection(val head: String, val commands: List<ShellCommand>)

class SidebarContribution(val label: String, val icon: ImageVector?, val onClick: () -> Unit)

enum class PaneSlot {
  /** Beside the main column: the Specs pane. */
  Right,

  /** Docked under the main column: the terminal. */
  Bottom,
}

class PaneContribution(val slot: PaneSlot, val content: @Composable () -> Unit)

class SettingsPageContribution(val id: SettingsPageId, val label: String, val group: String)

/** Every feature's settings pages, so the config feature adds pages without depending on the settings feature. */
@Inject
@SingleIn(WindowScope::class)
class SettingsPages(entries: Set<FeatureEntry>) {
  val pages: List<SettingsPageContribution> = entries.flatMap { it.settingsPages }
}
