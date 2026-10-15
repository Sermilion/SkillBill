package dev.skillbill.tray.feature.history

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.loadedList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class HistoryContentTest {
  @Test
  fun `history entries beyond the first few remain reachable by scrolling the list`() = runBlocking {
    val rows = (0 until 20).map { workItem("entry-$it", Lifecycle.TERMINAL) }
    val state = HistoryUiState(list = loadedList(rows, hasMore = false))

    ImageComposeScene(250, 698, coroutineContext = coroutineContext) {
      DesignSystemTheme {
        val scroll = rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize(), state = scroll) { historySection(state, selected = null, scroll = scroll, onAction = {}) }
      }
    }.use { scene ->
      scene.render(System.nanoTime()).close()
      repeat(15) {
        scene.sendPointerEvent(PointerEventType.Scroll, Offset(120f, 400f), scrollDelta = Offset(0f, 120f))
        delay(25)
        scene.render(System.nanoTime()).close()
      }
      val visibleTexts = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }.flatMap {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text }
      }
      assertTrue("entry-19" in visibleTexts, visibleTexts.joinToString())
    }
  }
}
