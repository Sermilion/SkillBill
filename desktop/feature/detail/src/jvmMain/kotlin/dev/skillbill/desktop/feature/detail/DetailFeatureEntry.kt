package dev.skillbill.desktop.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.skillbill.desktop.core.designsystem.component.ToastData
import dev.skillbill.desktop.core.designsystem.component.ToastLead
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ScreenKey
import dev.skillbill.desktop.core.ui.input.ScreenKeyHandler
import dev.skillbill.desktop.core.ui.navigation.AppNavigator
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.navigation.rememberEntryViewModel
import dev.skillbill.desktop.core.ui.pane.CHAT_SHORTCUT
import dev.skillbill.desktop.core.ui.pane.ChatCommands
import dev.skillbill.desktop.core.ui.pane.PaneCommands
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private const val COPY_PREVIEW_LENGTH = 90

private val DETAILS_SHORTCUT = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) "⌘ I" else "Ctrl I"

/**
 * Renders [DetailRoute]. J and K open the neighbouring board row through the navigator, which replaces the open detail; Esc and
 * Cmd/Ctrl+I stay with the shell.
 */
@Inject
@SingleIn(WindowScope::class)
class DetailFeatureEntry(
  private val createViewModel: (WorkItem) -> DetailViewModel,
  private val navigator: AppNavigator,
  private val toasts: ToastQueue,
  private val shellPanes: ShellPanes,
  private val paneCommands: PaneCommands,
  private val chat: ChatCommands,
) : FeatureEntry {
  override fun renders(route: AppRoute): Boolean = route is DetailRoute

  @Composable
  override fun Content(route: AppRoute) {
    val item = (route as DetailRoute).item
    val viewModel = rememberEntryViewModel("detail") { createViewModel(item) }
    LaunchedEffect(viewModel) {
      viewModel.effects.collect { effect ->
        when (effect) {
          is DetailEffect.OpenItem -> navigator.navigate(DetailRoute(effect.item))
          is DetailEffect.OpenRoute -> navigator.navigate(effect.route)
          is DetailEffect.ShowToast -> toasts.show(effect.message)
          is DetailEffect.CopyToClipboard -> copy(effect.text)
        }
      }
    }
    val state by viewModel.state.collectAsState()
    val shellInput = LocalShellInput.current
    ScreenKeyHandler { key ->
      when (key) {
        ScreenKey.Next -> viewModel.step(next = true, keepListFocus = shellInput.subtaskListFocused)
        ScreenKey.Previous -> viewModel.step(next = false, keepListFocus = shellInput.subtaskListFocused)
        else -> viewModel.onKey(key)
      }
    }
    val terminalOpen by paneCommands.terminalOpen.collectAsState()
    val shownChatId by chat.shownChatId.collectAsState()
    val host = DetailScreenHost(
      detailsPaneOpen = shellPanes.detailsPaneOpen,
      detailsShortcut = DETAILS_SHORTCUT,
      onToggleDetails = { shellPanes.detailsPaneOpen = !shellPanes.detailsPaneOpen },
      specsOpen = shellPanes.specsOpen,
      terminalOpen = terminalOpen,
      onToggleSpecs = { shellPanes.specsOpen = !shellPanes.specsOpen },
      onToggleTerminal = paneCommands::toggleTerminal,
      chatOpen = shownChatId == item.id,
      chatShortcut = CHAT_SHORTCUT,
      onToggleChat = { chat.toggleChat(item) },
    )
    DetailScreen(state, host) { action ->
      if (action is DetailAction.SelectSubtask || action is DetailAction.MoveSubtaskSelection) {
        shellPanes.specsOpen = false
        shellPanes.detailsPaneOpen = true
      }
      viewModel.onAction(action)
    }
  }

  private fun copy(text: String) {
    val selection = StringSelection(text)
    Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
    val preview = if (text.length <= COPY_PREVIEW_LENGTH) text else text.take(COPY_PREVIEW_LENGTH) + "…"
    toasts.show(ToastData("Copied", preview, ToastLead.Icon(LucideIcons.copy)))
  }
}

@ContributesTo(WindowScope::class)
interface DetailEntryBinding {
  @Provides
  @IntoSet
  fun detailEntry(entry: DetailFeatureEntry): FeatureEntry = entry
}
