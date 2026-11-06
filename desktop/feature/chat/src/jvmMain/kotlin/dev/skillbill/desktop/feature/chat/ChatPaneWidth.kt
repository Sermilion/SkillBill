package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.ui.di.WindowScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** The window's docked chat pane width in dp. It lives in memory only; [close] is where a persistence flush will go. */
@Inject
@SingleIn(WindowScope::class)
class ChatPaneWidth {
  private val state = MutableStateFlow(AppSettings.CHAT_WIDTH_DEFAULT_DP)

  val widthDp: StateFlow<Int> = state.asStateFlow()

  fun resizeTo(widthDp: Int) {
    state.value = widthDp.coerceIn(AppSettings.CHAT_WIDTH_MIN_DP, AppSettings.CHAT_WIDTH_MAX_DP)
  }

  fun close() = Unit
}
