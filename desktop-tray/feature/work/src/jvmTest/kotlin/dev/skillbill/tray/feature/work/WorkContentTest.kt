package dev.skillbill.tray.feature.work

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.loadedList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@OptIn(ExperimentalComposeUiApi::class)
class WorkContentTest {
  private val today = Instant.parse("2026-10-18T00:00:00Z")

  private fun finished(id: String, at: Instant) =
    workItem(id, Lifecycle.TERMINAL, issueKey = id).copy(finishedAt = at)

  private fun texts(state: WorkUiState): List<String> = runBlocking {
    ImageComposeScene(320, 900, coroutineContext = coroutineContext) {
      DesignSystemTheme {
        val scroll = rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize(), state = scroll) {
          workSections(state, today, filtering = false, selected = null, scroll = scroll, onAction = {})
        }
      }
    }.use { scene ->
      scene.render(System.nanoTime()).close()
      scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }.flatMap {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text }
      }
    }
  }

  @Test
  fun `the finished section lists only work that finished since the start of today`() {
    val state = WorkUiState(
      finished = loadedList(listOf(finished("TODAY-1", today + 2.hours), finished("YESTERDAY-1", today - 1.hours)), hasMore = false),
    )

    val shown = texts(state)

    assertTrue("FINISHED TODAY · 1" in shown, shown.joinToString())
    assertTrue(shown.any { "TODAY-1" in it }, shown.joinToString())
    assertFalse(shown.any { "YESTERDAY-1" in it }, shown.joinToString())
  }

  @Test
  fun `a finished section with nothing from today says so`() {
    val state = WorkUiState(finished = loadedList(listOf(finished("YESTERDAY-1", today - 1.hours)), hasMore = true))

    val shown = texts(state)

    assertTrue("FINISHED TODAY · 0" in shown, shown.joinToString())
    assertTrue("Nothing finished today" in shown, shown.joinToString())
  }
}
