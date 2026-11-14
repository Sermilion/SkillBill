package dev.skillbill.desktop.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.repository.ChatChangesRepository
import dev.skillbill.desktop.core.ui.changes.ChangesWindows
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.navigation.PaneContribution
import dev.skillbill.desktop.core.ui.navigation.PaneSlot
import dev.skillbill.desktop.core.ui.shell.DockedPane
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Contributes the docked chat pane to the work area's right slot. */
@Inject
@SingleIn(WindowScope::class)
class ChatFeatureEntry(
  workspace: ChatWorkspace, chatPaneWidth: ChatPaneWidth,
  changesRepository: ChatChangesRepository, changesWindows: ChangesWindows,
) : FeatureEntry {
  override fun renders(route: AppRoute): Boolean = false

  @Composable
  override fun Content(route: AppRoute) = Unit

  override val panes: List<PaneContribution> = listOf(PaneContribution(PaneSlot.Chat) {
    val open by workspace.paneOpen.collectAsState()
    val width by chatPaneWidth.widthDp.collectAsState()
    if (open) DockedPane("Chat", width, onResize = chatPaneWidth::resizeTo, onReset = chatPaneWidth::reset, onDismiss = workspace::hide) {
      ChatPane(workspace, changesRepository) { source, path -> changesWindows.openChat(source, ChangeScope.All, path) }
    }
  })
}

@ContributesTo(WindowScope::class)
interface ChatEntryBinding {
  @Provides
  @IntoSet
  fun chatEntry(entry: ChatFeatureEntry): FeatureEntry = entry
}
