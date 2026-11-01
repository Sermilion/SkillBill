package dev.skillbill.desktop.feature.changes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class FileDiffStreamTest {
  private val files = listOf("a/First.kt", "b/Second.kt", "c/Third.kt").map { ChangedFile(it, null, ChangeStatus.Modified, 30, 0) }
  private val loaded = files.associate { file ->
    val lines = (1..30).map { DiffLine(DiffLineKind.Added, null, it, "line $it of ${file.path}") }
    val diff = FileDiff(listOf(DiffHunk("@@ -0,0 +1,30 @@", lines)), 30, 0)
    file.path to LoadedDiff(file, FileDiffState.Shown(diff), diffRowCount(diff), diffLongestChars(diff))
  }

  @Test
  fun `a wheel scroll past a file selects the next one and a request jumps back`() {
    val actions = mutableListOf<ChangesWindowAction>()
    var request by mutableIntStateOf(0)
    var now = 0L
    val scene = ImageComposeScene(900, 400, Density(1f)) {
      SkillBillTheme(reducedMotion = true) {
        FileDiffStream(files, loaded, files[0].path, request, { actions += it })
      }
    }
    fun settle() = repeat(60) {
      now += 16_000_000L
      scene.render(now)
    }
    try {
      settle()
      assertTrue(actions.filterIsInstance<ChangesWindowAction.EnsureDiffs>().first().paths.containsAll(listOf(files[0].path, files[1].path)))
      repeat(20) {
        scene.sendPointerEvent(PointerEventType.Scroll, Offset(400f, 200f), scrollDelta = Offset(0f, 5f))
        settle()
      }
      assertTrue(files[1].path in actions.filterIsInstance<ChangesWindowAction.FollowScroll>().map { it.path })

      val follows = actions.filterIsInstance<ChangesWindowAction.FollowScroll>().size
      request = 1
      settle()
      assertEquals(follows, actions.filterIsInstance<ChangesWindowAction.FollowScroll>().size)
    } finally {
      scene.close()
    }
  }
}
