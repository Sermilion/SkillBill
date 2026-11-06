package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// A drag reports many widths; the width is written once it has been still this long.
private val WIDTH_WRITE_DELAY = 300.milliseconds

// The close-time write of the last width; a stuck disk must not hang exit.
private val WIDTH_FLUSH_TIMEOUT = 1.seconds

/**
 * The window's docked chat pane width in dp. It starts from the stored width, follows the user's drags at once, and persists the
 * last drag after it settles; [close] flushes a write still waiting. A rejected write is not retried and keeps the in-memory width.
 */
@Inject
@SingleIn(WindowScope::class)
class ChatPaneWidth(private val settings: AppSettingsRepository, windowScope: WindowCoroutineScope) {
  private val scope = windowScope.scope
  private val state = MutableStateFlow(AppSettings.CHAT_WIDTH_DEFAULT_DP)

  // Both are confined to the window scope's UI thread, where resizeTo, the debounced write and close() run.
  private var dragged = false
  private var widthWrite: Job? = null

  // The width not yet written; volatile like the terminal's pending height, so a close() off the UI thread reads the latest value.
  @Volatile private var pendingWidth: Int? = null

  val widthDp: StateFlow<Int> = state.asStateFlow()

  init {
    scope.launch { settings.settings.map { it.chatWidthDp }.collect { if (!dragged) state.value = it } }
  }

  fun resizeTo(widthDp: Int) {
    val value = widthDp.coerceIn(AppSettings.CHAT_WIDTH_MIN_DP, AppSettings.CHAT_WIDTH_MAX_DP)
    dragged = true
    state.value = value
    pendingWidth = value
    widthWrite?.cancel()
    widthWrite = scope.launch {
      delay(WIDTH_WRITE_DELAY)
      settings.setChatWidthDp(value)
      if (pendingWidth == value) pendingWidth = null
    }
  }

  fun close() {
    widthWrite?.cancel()
    val value = pendingWidth ?: return
    pendingWidth = null
    // DataStore writes on its own IO scope, so blocking the UI thread here cannot deadlock.
    runBlocking { withTimeoutOrNull(WIDTH_FLUSH_TIMEOUT) { settings.setChatWidthDp(value) } }
  }
}
