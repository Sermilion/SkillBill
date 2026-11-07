package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import kotlin.test.assertTrue
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
  fun `the transcript shows the newest line last and copies raw text oldest first`() = runComposeUiTest {
    val newest = "[subtask 3] Tool Bash {\"command\":\"./gradlew :desktop:check\"}"
    val oldest = "[subtask 1] Reading the mapper"
    val lines = listOf(
      PlanOutputLine("b", newest, epoch, 3, 2),
      PlanOutputLine("a", oldest, epoch, 1, 1),
    )
    val detail = emptyDetail.copy(planOutputLines = lines, standalonePlan = progress(listOf(unit(1), unit(3))))
    val base = workItem("plan", kind = WorkKind.Phase)
    val item = base.copy(standaloneExecution = true, control = base.control.copy(phaseId = "plan", invocationId = "plan"))
    val actions = mutableListOf<DetailAction>()
    setContent { SkillBillTheme(reducedMotion = true) {
      DetailScreen(DetailUiState(item, SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch), DetailScreenHost(false, "Ctrl I", {}), actions::add)
    } }

    val shown = "[subtask 3] Bash(./gradlew :desktop:check)"
    onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(shown))
    val olderTop = onNodeWithText("[subtask 1] Reading the mapper").fetchSemanticsNode().boundsInRoot.top
    val newerTop = onNodeWithText(shown).fetchSemanticsNode().boundsInRoot.top
    assertTrue(newerTop > olderTop)
    onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Copy"))
    onNodeWithText("Copy").performClick()
    runOnIdle { assertEquals(listOf<DetailAction>(DetailAction.Copy("$oldest\n$newest")), actions) }
  }

  private fun unit(id: Int) = StandalonePlanUnit(id, "Subtask $id", "/specs/$id.md", emptyList(), "writing", epoch, null, null, null)
  private fun progress(units: List<StandalonePlanUnit>) = StandalonePlanProgress(null, emptyList(), 0, units.size, units.firstOrNull()?.subtaskId, units.map { it.subtaskId }, 5, null, null, units, null, 1, true)
}
