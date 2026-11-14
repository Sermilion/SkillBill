package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatPaneWidthTest {
  @Test
  fun `a drag writes once after it settles and a pending width is flushed on close`() = runTest {
    val settings = FakeAppSettingsRepository(AppSettings.DEFAULT.copy(chatWidthDp = 640))
    val width = ChatPaneWidth(settings, WindowCoroutineScope(backgroundScope))
    runCurrent()
    assertEquals(640, width.widthDp.value)

    width.resizeTo(700)
    advanceTimeBy(200)
    width.resizeTo(720)
    width.resizeTo(740)
    assertEquals(740, width.widthDp.value)
    // The first resize's write would have fired at 300 ms had the newer resizes not replaced it.
    advanceTimeBy(150)
    runCurrent()
    assertEquals(640, settings.settings.value.chatWidthDp)
    advanceTimeBy(151)
    runCurrent()
    assertEquals(740, settings.settings.value.chatWidthDp)

    width.resizeTo(800)
    width.close()
    assertEquals(800, settings.settings.value.chatWidthDp)
  }

  @Test
  fun `reset shows the default at once and replaces a pending drag write with the default after the drag delay`() = runTest {
    val settings = FakeAppSettingsRepository(AppSettings.DEFAULT.copy(chatWidthDp = 640))
    val width = ChatPaneWidth(settings, WindowCoroutineScope(backgroundScope))
    runCurrent()

    width.resizeTo(700)
    advanceTimeBy(200)
    width.reset()
    assertEquals(560, width.widthDp.value)
    // The drag's write would have fired at 300 ms had the reset not replaced it.
    advanceTimeBy(299)
    runCurrent()
    assertEquals(640, settings.settings.value.chatWidthDp)
    advanceTimeBy(2)
    runCurrent()
    assertEquals(560, settings.settings.value.chatWidthDp)
  }
}
