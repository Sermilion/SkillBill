package dev.skillbill.desktop.benchmark

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.ShadowBackdrop
import dev.skillbill.desktop.core.designsystem.layeredShadow
import dev.skillbill.desktop.core.domain.model.DiffHunk
import dev.skillbill.desktop.core.domain.model.DiffLine
import dev.skillbill.desktop.core.domain.model.DiffLineKind
import dev.skillbill.desktop.core.domain.model.FileDiff
import dev.skillbill.desktop.core.ui.diff.DiffHorizontalScroll
import dev.skillbill.desktop.core.ui.diff.diffLongestChars
import dev.skillbill.desktop.core.ui.diff.diffRows
import java.util.Locale
import kotlin.math.ceil
import kotlin.system.measureNanoTime

fun main() {
  println("Offscreen CPU drawing, 1440x900 pixels, density 1. No window presentation or GPU timings.")
  listOf("small" to 1, "many-files" to 66, "long-line" to 66).forEach { (name, files) ->
    val diffs = List(files) { file ->
      val lines = List(120) { line ->
        val text = if (name == "long-line" && file == files - 1 && line == 119) "x".repeat(5_000)
        else "  val value$line = readChangedFile(\"src/File$file.kt\")"
        DiffLine(if (line % 3 == 0) DiffLineKind.Added else DiffLineKind.Context, line + 1, line + 1, text)
      }
      FileDiff(listOf(DiffHunk("@@ -1,120 +1,120 @@", lines)), 40, 0)
    }
    listOf("none", "full", "outer").forEach { shadow -> measureDiff(name, diffs, shadow) }
  }
}

private fun measureDiff(name: String, diffs: List<FileDiff>, shadow: String) {
  val state = LazyListState()
  val longest = diffs.maxOf(::diffLongestChars)
  val scene = ImageComposeScene(1440, 900, Density(1f)) {
    SkillBillTheme(reducedMotion = true) {
      val shape = RoundedCornerShape(12.dp)
      val frame = if (shadow == "full") Modifier.layeredShadow(SkillBillTheme.shadows.modal, shape) else Modifier
      Box(Modifier.fillMaxSize().padding(32.dp)) {
        if (shadow == "outer") ShadowBackdrop(SkillBillTheme.shadows.modal, shape, Modifier.matchParentSize())
        Box(frame.fillMaxSize().clip(shape).background(SkillBillTheme.colors.bgWindow)) {
          DiffHorizontalScroll(longest) {
            LazyColumn(state = state) {
              diffs.forEachIndexed { index, diff -> diffRows(diff, "file:$index") }
            }
          }
        }
      }
    }
  }
  try {
    val samples = mutableListOf<Double>()
    repeat(180) { frame ->
      state.requestScrollToItem(frame % 50, (frame % 4) * 5)
      val elapsed = measureNanoTime { scene.render(frame * 16_666_667L).close() } / 1_000_000.0
      if (frame >= 60) samples += elapsed
    }
    val sorted = samples.sorted()
    println(String.format(Locale.ROOT, "%s shadow=%s median=%.2f ms p95=%.2f ms", name, shadow, sorted[sorted.size / 2], sorted[ceil(sorted.size * .95).toInt() - 1]))
  } finally {
    scene.close()
  }
}
