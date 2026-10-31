package dev.skillbill.desktop.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.navigation.PaneContribution
import dev.skillbill.desktop.core.ui.navigation.PaneSlot
import dev.skillbill.desktop.core.ui.shell.FloatingPane
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Contributes the floating chat pane above the active route. */
@Inject
@SingleIn(WindowScope::class)
class ChatFeatureEntry(workspace: ChatWorkspace) : FeatureEntry {
  override fun renders(route: AppRoute): Boolean = false

  @Composable
  override fun Content(route: AppRoute) = Unit

  override val panes: List<PaneContribution> = listOf(PaneContribution(PaneSlot.Chat) {
    val open by workspace.paneOpen.collectAsState()
    FloatingPane(open, "Chat", onDismiss = workspace::hide) { ChatPane(workspace) }
  })
}

@ContributesTo(WindowScope::class)
interface ChatEntryBinding {
  @Provides
  @IntoSet
  fun chatEntry(entry: ChatFeatureEntry): FeatureEntry = entry
}
