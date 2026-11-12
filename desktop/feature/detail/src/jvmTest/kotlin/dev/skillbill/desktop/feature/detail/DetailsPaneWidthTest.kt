package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailsPaneWidthTest {
  @Test
  fun `resize is immediate, stale settings cannot replace it, reset supersedes queued width, and close flushes`() = runTest {
    val settings = FakeAppSettingsRepository(AppSettings.DEFAULT.copy(detailsWidthDp = 600))
    val width = DetailsPaneWidth(settings, RecordingDiagnostics(), WindowCoroutineScope(backgroundScope))
    runCurrent()
    assertEquals(600, width.widthDp.value)

    width.resizeTo(720)
    settings.settings.value = settings.settings.value.copy(detailsWidthDp = 640)
    runCurrent()
    assertEquals(720, width.widthDp.value)
    advanceTimeBy(200)
    width.reset()
    advanceTimeBy(301)
    runCurrent()
    assertEquals(null, settings.settings.value.detailsWidthDp)

    width.resizeTo(800)
    width.close()
    assertEquals(800, settings.settings.value.detailsWidthDp)
  }

  @Test
  fun `a rejected save is diagnosed and does not change the requested width`() = runTest {
    val settings = FakeAppSettingsRepository()
    settings.detailsWidthWriteFailure = true
    val diagnostics = RecordingDiagnostics()
    val width = DetailsPaneWidth(settings, diagnostics, WindowCoroutineScope(backgroundScope))

    width.resizeTo(700)
    advanceTimeBy(301)
    runCurrent()

    assertEquals(700, width.widthDp.value)
    assertEquals(null, settings.settings.value.detailsWidthDp)
    assertEquals(listOf("detail.details_width_save_failed"), diagnostics.categories)
  }

  @Test
  fun `close suspends without blocking the window and clears after an in-flight resize commits`() = runTest {
    val saved = FakeAppSettingsRepository(AppSettings.DEFAULT.copy(detailsWidthDp = 600))
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val settings = object : AppSettingsRepository by saved {
      override suspend fun setDetailsWidthDp(widthDp: Int?) = if (widthDp == 720) {
        withContext(NonCancellable) {
          started.complete(Unit)
          release.await()
          saved.setDetailsWidthDp(widthDp)
        }
      } else saved.setDetailsWidthDp(widthDp)
    }
    val diagnostics = RecordingDiagnostics()
    val width = DetailsPaneWidth(settings, diagnostics, WindowCoroutineScope(backgroundScope))
    width.resizeTo(720)
    advanceTimeBy(300)
    runCurrent()
    assertTrue(started.isCompleted)

    width.reset()
    val closing = launch { width.close() }
    try {
      runCurrent()
      assertFalse(closing.isCompleted)
      assertEquals(null, width.widthDp.value)
      assertEquals(600, saved.settings.value.detailsWidthDp)
    } finally {
      release.complete(Unit)
    }
    runCurrent()

    assertTrue(closing.isCompleted)
    assertEquals(null, saved.settings.value.detailsWidthDp)
    assertEquals(emptyList(), diagnostics.categories)
  }
}
