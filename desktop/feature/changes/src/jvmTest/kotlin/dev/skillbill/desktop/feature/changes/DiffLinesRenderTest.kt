package dev.skillbill.desktop.feature.changes

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.DiffHunk
import dev.skillbill.desktop.core.domain.model.DiffLine
import dev.skillbill.desktop.core.domain.model.DiffLineKind
import dev.skillbill.desktop.core.domain.model.FileDiff
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.ui.diff.diffLongestChars
import dev.skillbill.desktop.core.ui.diff.diffRowCount
import kotlin.test.Test

class DiffLinesRenderTest {
  @Test
  fun `a diff with one huge line renders instead of overflowing layout constraints`() {
    val huge = DiffLine(DiffLineKind.Added, null, 1, "x".repeat(400_000))
    val diff = FileDiff(listOf(DiffHunk("@@ -0,0 +1 @@", listOf(huge))), 1, 0)
    val file = ChangedFile("a/B.html", null, ChangeStatus.Added, 1, 0)
    val loaded = LoadedDiff(file, FileDiffState.Shown(diff), diffRowCount(diff), diffLongestChars(diff))
    val scene = ImageComposeScene(1200, 800, Density(2f)) {
      SkillBillTheme(reducedMotion = true) { FileDiffStream(listOf(file), mapOf(file.path to loaded), file.path, 0, {}) }
    }
    try {
      repeat(3) { scene.render(it * 16_000_000L) }
    } finally {
      scene.close()
    }
  }
}
