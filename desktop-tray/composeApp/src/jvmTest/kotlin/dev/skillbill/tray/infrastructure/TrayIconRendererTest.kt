package dev.skillbill.tray.infrastructure

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.use
import dev.skillbill.tray.core.domain.model.TrayStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class TrayIconRendererTest {
  @Test
  fun `tray updates and window icon rendering use independent SVG drawing state`() = runBlocking<Unit> {
    val ready = CompletableDeferred<Unit>()
    val finished = CompletableDeferred<Unit>()
    coroutineScope {
      listOf(
        async(Dispatchers.Default) {
          ImageComposeScene(64, 64, coroutineContext = coroutineContext) {
            Image(painterResource("icons/skill-bill.svg"), null, Modifier.fillMaxSize())
          }.use { scene ->
            ready.complete(Unit)
            do {
              scene.render(System.nanoTime()).use { image ->
                assertTrue(image.toComposeImageBitmap().toPixelMap()[16, 16].alpha > 0f)
              }
              yield()
            } while (!finished.isCompleted)
          }
        },
        async(Dispatchers.Default) {
          ready.await()
          repeat(100) { update ->
            ImageComposeScene(64, 64, coroutineContext = coroutineContext) {
              TrayIconRenderer.Content(TrayStatus.RUNNING, update + 1, showOverlay = true)
            }.use { scene ->
              repeat(2) {
                scene.render(System.nanoTime()).use { image ->
                  assertTrue(image.toComposeImageBitmap().toPixelMap()[16, 16].alpha > 0f)
                }
              }
            }
          }
          finished.complete(Unit)
        },
      ).awaitAll()
    }
  }
}
