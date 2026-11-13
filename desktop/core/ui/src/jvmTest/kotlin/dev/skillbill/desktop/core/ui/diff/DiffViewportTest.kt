package dev.skillbill.desktop.core.ui.diff

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.DiffHunk
import dev.skillbill.desktop.core.domain.model.DiffLine
import dev.skillbill.desktop.core.domain.model.DiffLineKind
import dev.skillbill.desktop.core.domain.model.FileDiff
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DiffViewportTest {
  @Test
  fun `clicking a horizontally scrolled word selects the visible occurrence`() = runComposeUiTest {
    val scroll = ScrollState(0)
    val highlight = DiffWordHighlight()
    val line = DiffLine(DiffLineKind.Added, null, 1, "padding ".repeat(20) + "target tail")
    val diff = FileDiff(listOf(DiffHunk("hunk", listOf(line))), 1, 0)
    val text = "+" + line.text
    val target = text.indexOf("target")
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        SkillBillTheme(reducedMotion = true) {
          Box(Modifier.size(320.dp, 200.dp).testTag("viewport")) {
            DiffHorizontalScroll(diffLongestChars(diff), scrollState = scroll) {
              LazyColumn { diffRows(diff, "file", highlight = highlight) }
            }
          }
        }
      }
    }
    lateinit var layout: TextLayoutResult
    onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
      val results = mutableListOf<TextLayoutResult>()
      get(results)
      layout = results.single()
    }
    runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue) } }
    val wordX = 116f + layout.getBoundingBox(target + 2).center.x - scroll.value
    val rowY = onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center.y
    onNodeWithTag("viewport").performMouseInput { click(Offset(wordX, rowY)) }
    runOnIdle { assertEquals(DiffWordSelection("file", "target", DiffLineAddress(0, 0), target), highlight.selection) }
  }

  @Test
  fun `a long offscreen line does not widen or move the diff viewport when scrolling horizontally`() = runComposeUiTest {
    val scroll = ScrollState(0)
    var measured = IntSize.Zero
    var position = Offset.Zero
    var expectedWidth = 0
    setContent {
      SkillBillTheme(reducedMotion = true) {
        expectedWidth = with(LocalDensity.current) { 320.dp.roundToPx() }
        Box(Modifier.size(320.dp, 200.dp)) {
          DiffHorizontalScroll(5_000, scrollState = scroll) {
            Box(Modifier.fillMaxSize().onGloballyPositioned {
              measured = it.size
              position = it.positionInRoot()
            })
          }
        }
      }
    }
    var original = Offset.Zero
    runOnIdle {
      assertEquals(expectedWidth, measured.width)
      assertTrue(scroll.maxValue > expectedWidth)
      original = position
      runBlocking { scroll.scrollTo(scroll.maxValue) }
    }
    runOnIdle {
      assertEquals(expectedWidth, measured.width)
      assertEquals(original, position)
    }
  }
}
