package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.rememberSectionCollapse
import dev.skillbill.desktop.core.domain.model.PlanAgentActivity
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class PlanActivityTest {
  @Test
  fun `consecutive reads fold without hiding a note or crossing planning units`() {
    val read = PlanActivityEvent("read-1", epoch, "a.kt", "preplan", "read", subtaskId = 1)
    val note = read.copy(key = "note", kind = "note", text = "Read is only prose here")
    val events = listOf(read, read.copy(key = "read-2", text = "b.kt"), note, read.copy(key = "read-3"), read.copy(key = "read-4", subtaskId = 2))
    val rows = planActivityRows(events, PlanActivityFilter.All)
    assertEquals(4, rows.size)
    assertEquals(listOf("b.kt"), rows.first().children.map { it.text })
    assertEquals(listOf(note), planActivityRows(events, PlanActivityFilter.Notes))
    assertEquals("Write failed", planActivityVerb(read.copy(kind = "write", state = "failed")))
  }

  @Test
  fun `scrolling up pauses follow and jump to latest restores it`() = runComposeUiTest {
    val item = workItem("plan", kind = WorkKind.Phase)
    val events = (1..40).map { id ->
      PlanAgentActivity("note-$id", "plan_subtasks", "note", "Planning note $id", epoch + id.seconds, epoch + id.seconds, "claude", null, "completed", null, null, null)
    }
    val detail = emptyDetail.copy(planActivity = events)
    val state = DetailUiState(item, SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch)
    setContent { SkillBillTheme(reducedMotion = true) { PlanActivityPanel(item, detail, state, rememberSectionCollapse()) {} } }
    onNodeWithText("Planning note 40").assertExists()
    onNodeWithTag("plan-activity-feed").performTouchInput { swipeDown() }
    onNodeWithText("Jump to latest").performClick()
    onNodeWithText("Planning note 40").assertExists()
    onNodeWithText("Jump to latest").assertDoesNotExist()
    onNodeWithText("Notes").performClick()
    onNodeWithText("Planning note 40").assertExists()
  }
}
