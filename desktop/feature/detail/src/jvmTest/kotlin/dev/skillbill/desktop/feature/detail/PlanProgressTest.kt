package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.PlanOutputLine
import dev.skillbill.desktop.core.domain.model.StandalonePlanProgress
import dev.skillbill.desktop.core.domain.model.StandalonePlanUnit
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class PlanProgressTest {
  @Test
  fun `a quiet writer remains visible while a sibling refreshes the global heartbeat`() {
    val now = epoch + 600.seconds
    val quiet = unit(1).copy(startedAt = epoch, latestOutputAt = epoch + 100.seconds)
    val busy = unit(2).copy(startedAt = epoch, latestOutputAt = now)
    assertEquals(epoch + 100.seconds, planUnitQuietSince(quiet, now))
    assertNull(planUnitQuietSince(busy, now))
    assertNull(planUnitQuietSince(quiet.copy(startedAt = now), now))
    assertNull(planUnitQuietSince(quiet.copy(state = "saved"), now))
  }

  @Test
  fun `progress names every writer and hides none behind the first active subtask`() {
    val item = workItem("plan", kind = WorkKind.Phase).copy(running = true)
    val progress = progress(listOf(unit(3), unit(4), unit(5))).copy(totalSubtaskCount = 7, plannedSubtaskCount = 2)
    assertEquals("Writing specs for subtasks 3 to 5. 2 of 7 saved.", planStatusText(item, emptyDetail.copy(standalonePlan = progress)))
    assertEquals("wave 1 of 2", planWaveLabel(progress.copy(units = progress.units + unit(6).copy(state = "queued") + unit(7).copy(state = "queued"))))
  }

  @Test
  fun `the full output filter preserves attribution and restores interleaved lines`() = runComposeUiTest {
    val lines = listOf(
      PlanOutputLine("a", "[subtask 1] Reading the mapper", epoch, 1, 1),
      PlanOutputLine("b", "[subtask 2] Writing acceptance criteria", epoch, 2, 2),
    )
    val detail = emptyDetail.copy(planOutputLines = lines, standalonePlan = progress(listOf(unit(1), unit(2))))
    val item = workItem("plan", kind = WorkKind.Phase)
    val state = DetailUiState(item, SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch)
    setContent { SkillBillTheme(reducedMotion = true) { PlanOutputLog(state, detail, onAction = {}) } }
    onNodeWithText("[subtask 1] Reading the mapper").assertExists()
    onNodeWithText("Subtask 2").performClick()
    onNodeWithText("[subtask 1] Reading the mapper").assertDoesNotExist()
    onNodeWithText("[subtask 2] Writing acceptance criteria").assertExists()
    onNodeWithText("All output").performClick()
    onNodeWithText("[subtask 1] Reading the mapper").assertExists()
  }

  private fun unit(id: Int) = StandalonePlanUnit(id, "Subtask $id", "/specs/$id.md", emptyList(), "writing", epoch, null, null, null)
  private fun progress(units: List<StandalonePlanUnit>) = StandalonePlanProgress(null, emptyList(), 0, units.size, units.firstOrNull()?.subtaskId, units.map { it.subtaskId }, 5, null, null, units, null, 1, true)
}
