package dev.skillbill.desktop.feature.chat

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

/** Puts the chat pane in the window's right slot. It owns no route. */
@Inject
@SingleIn(WindowScope::class)
class ChatFeatureEntry(workspace: ChatWorkspace) : FeatureEntry {
  override fun renders(route: AppRoute): Boolean = false

  @Composable
  override fun Content(route: AppRoute) = Unit

  override val panes: List<PaneContribution> = listOf(PaneContribution(PaneSlot.Chat) { ChatPane(workspace) })
}

@ContributesTo(WindowScope::class)
interface ChatEntryBinding {
  @Provides
  @IntoSet
  fun chatEntry(entry: ChatFeatureEntry): FeatureEntry = entry
}
