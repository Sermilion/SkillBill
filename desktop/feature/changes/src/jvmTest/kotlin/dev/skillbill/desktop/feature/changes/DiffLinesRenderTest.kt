package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.CommentSide
import dev.skillbill.desktop.core.domain.model.CommentState
import dev.skillbill.desktop.core.domain.model.CommentThread
import dev.skillbill.desktop.core.domain.model.DiffHunk
import dev.skillbill.desktop.core.domain.model.DiffLine
import dev.skillbill.desktop.core.domain.model.DiffLineKind
import dev.skillbill.desktop.core.domain.model.FileDiff
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.ui.diff.diffLongestChars
import dev.skillbill.desktop.core.ui.diff.diffRowCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DiffLinesRenderTest {
  @Test
  fun `a diff with one huge line renders instead of overflowing layout constraints`() {
    val huge = DiffLine(DiffLineKind.Added, null, 1, "x".repeat(400_000))
    val diff = FileDiff(listOf(DiffHunk("@@ -0,0 +1 @@", listOf(huge))), 1, 0)
    val file = ChangedFile("a/B.html", null, ChangeStatus.Added, 1, 0)
    val loaded = LoadedDiff(file, FileDiffState.Shown(diff), diffRowCount(diff), diffLongestChars(diff))
    val scene = ImageComposeScene(1200, 800, Density(2f)) {
      SkillBillTheme(reducedMotion = true) {
        FileDiffStream(listOf(file), mapOf(file.path to loaded), file.path, 0, emptyList(), {}, {})
      }
    }
    try {
      repeat(3) { scene.render(it * 16_000_000L) }
    } finally {
      scene.close()
    }
  }

  @Test
  fun `a resolved line thread fades out instead of vanishing at once`() = runComposeUiTest {
    var threads by mutableStateOf(listOf(lineThread))
    setContent { ThreadStream(threads, reducedMotion = false) }
    assertTrue(threadPixels() > 0)
    mainClock.autoAdvance = false

    threads = emptyList()
    mainClock.advanceTimeByFrame()
    mainClock.advanceTimeBy(48)
    assertTrue(threadPixels() > 0)

    mainClock.advanceTimeBy(1_000)
    assertEquals(0, threadPixels())
  }

  @Test
  fun `reduced motion removes a resolved line thread on the next frame`() = runComposeUiTest {
    var threads by mutableStateOf(listOf(lineThread))
    setContent { ThreadStream(threads, reducedMotion = true) }
    assertTrue(threadPixels() > 0)
    mainClock.autoAdvance = false

    threads = emptyList()
    mainClock.advanceTimeByFrame()
    mainClock.advanceTimeByFrame()
    assertEquals(0, threadPixels())
  }

  @Composable
  private fun ThreadStream(threads: List<CommentThread>, reducedMotion: Boolean) {
    SkillBillTheme(reducedMotion = reducedMotion) {
      FileDiffStream(listOf(threadFile), mapOf(threadFile.path to threadDiff), threadFile.path, 0, threads, { Box(Modifier.size(40.dp).background(Color.Magenta)) }, {})
    }
  }

  private fun ComposeUiTest.threadPixels(): Int {
    val pixels = onRoot().captureToImage().toPixelMap()
    var count = 0
    for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
      val color = pixels[x, y]
      if (color.red - color.green > 0.3f && color.blue - color.green > 0.3f) count++
    }
    return count
  }

  private companion object {
    val threadFile = ChangedFile("a/B.kt", null, ChangeStatus.Added, 1, 0)
    val threadDiff = FileDiff(listOf(DiffHunk("@@ -0,0 +1 @@", listOf(DiffLine(DiffLineKind.Added, null, 1, "val x = 1")))), 1, 0).let { diff ->
      LoadedDiff(threadFile, FileDiffState.Shown(diff), diffRowCount(diff), diffLongestChars(diff))
    }
    val lineThread = CommentThread("t1", threadFile.path, CommentSide.New, 1, null, CommentState.Open, false, null, null, null, emptyList())
  }
}
