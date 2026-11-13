package dev.skillbill.desktop.core.ui.diff

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.DiffHunk
import dev.skillbill.desktop.core.domain.model.DiffLine
import dev.skillbill.desktop.core.domain.model.DiffLineKind
import dev.skillbill.desktop.core.domain.model.FileDiff
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DiffRowsTest {
  @Test
  fun `comments stay after their anchored lines across hunk boundaries and identical lines`() = runComposeUiTest {
    val line = DiffLine(DiffLineKind.Context, 1, 1, "same text")
    val diff = FileDiff(listOf(DiffHunk("first hunk", List(3) { line }), DiffHunk("second hunk", listOf(line))), 0, 0)
    val slots = DiffLineSlots(linkedMapOf(
      DiffLineAddress(1, 0) to listOf("c"),
      DiffLineAddress(0, 2) to listOf("b"),
      DiffLineAddress(0, 0) to listOf("a1", "a2"),
    )) { BasicText("thread:$it") }
    val state = LazyListState()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Box(Modifier.size(640.dp, 600.dp)) {
          LazyColumn(state = state) { diffRows(diff, "file", slots) }
        }
      }
    }
    onNodeWithText("thread:c").assertIsDisplayed()
    runOnIdle {
      assertEquals(listOf("file:h0", "file:l0:0", "thread:a1", "thread:a2", "file:l0:1", "file:l0:2", "thread:b", "file:h1", "file:l1:0", "thread:c"), state.layoutInfo.visibleItemsInfo.map { it.key })
      assertEquals(diffRowCount(diff, slots), state.layoutInfo.totalItemsCount)
    }
  }
}
