package dev.skillbill.desktop.feature.terminal

import androidx.compose.runtime.Composable
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.navigation.PaneContribution
import dev.skillbill.desktop.core.ui.navigation.PaneSlot
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Puts the terminal pane in the window's bottom slot. It owns no route. */
@Inject
@SingleIn(WindowScope::class)
class TerminalFeatureEntry(workspace: TerminalWorkspace) : FeatureEntry {
  override fun renders(route: AppRoute): Boolean = false

  @Composable
  override fun Content(route: AppRoute) = Unit

  override val panes: List<PaneContribution> = listOf(PaneContribution(PaneSlot.Bottom) { TerminalPane(workspace) })
}

@ContributesTo(WindowScope::class)
interface TerminalEntryBinding {
  @Provides
  @IntoSet
  fun terminalEntry(entry: TerminalFeatureEntry): FeatureEntry = entry
}
