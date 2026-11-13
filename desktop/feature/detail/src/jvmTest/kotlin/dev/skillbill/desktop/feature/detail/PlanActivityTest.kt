package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.UiTicker
import dev.skillbill.desktop.core.designsystem.detail.rememberSectionCollapse
import dev.skillbill.desktop.core.domain.model.PlanAgentActivity
import dev.skillbill.desktop.core.domain.model.StandalonePlanProgress
import dev.skillbill.desktop.core.domain.model.StandalonePlanStage
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

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
  fun `search and command rows retain their subject when a path is present`() = runComposeUiTest {
    val search = PlanActivityEvent("search", epoch, "TODO", kind = "search", path = "src/Plan.kt")
    val command = PlanActivityEvent("command", epoch, "git status", kind = "command", path = "/checkout")
    setContent { SkillBillTheme(reducedMotion = true) {
      Column {
        PlanActivityRow(search)
        PlanActivityRow(command)
      }
    } }

    onNodeWithText("Searched TODO").assertExists()
    onNodeWithText("Ran git status").assertExists()
  }

  @Test
  fun `filters and follow remain reachable in a narrow activity panel`() = runComposeUiTest {
    val item = workItem("plan", kind = WorkKind.Phase)
    setContent { SkillBillTheme(reducedMotion = true) {
      Box(Modifier.width(240.dp)) {
        PlanActivityPanel(item, emptyDetail, rememberSectionCollapse(), rememberPlanScreenState(item.id)) {}
      }
    } }

    onNodeWithText("Notes").assertIsDisplayed().performClick().assertIsSelected()
    onNodeWithText("Follow").assertIsDisplayed().performClick()
    onNodeWithText("Jump to latest").assertIsDisplayed()
  }

  @Test
  fun `scrolling up pauses follow and jump to latest restores it`() = runComposeUiTest {
    val item = workItem("plan", kind = WorkKind.Phase)
    val events = (1..40).map { id ->
      PlanAgentActivity("note-$id", "plan_subtasks", "note", "Planning note $id", epoch + id.seconds, epoch + id.seconds, "claude", null, "completed", null, null, null)
    }
    val detail = emptyDetail.copy(planActivity = events)
    setContent { SkillBillTheme(reducedMotion = true) {
      PlanActivityPanel(item, detail, rememberSectionCollapse(), rememberPlanScreenState(item.id)) {}
    } }
    onNodeWithText("Planning note 40").assertExists()
    onNodeWithTag("plan-activity-feed").performTouchInput { swipeDown() }
    onNodeWithText("Jump to latest").performClick()
    onNodeWithText("Planning note 40").assertExists()
    onNodeWithText("Jump to latest").assertDoesNotExist()
    onNodeWithText("Notes").performClick()
    onNodeWithText("Planning note 40").assertExists()
  }

  @Test
  fun `tail shows only while the live run is not quiet`() = runComposeUiTest {
    val base = workItem("plan", kind = WorkKind.Phase)
    val item = base.copy(running = true)
    val event = PlanAgentActivity("note-1", "plan_subtasks", "note", "Planning", epoch + 390.seconds, epoch + 390.seconds, "claude", null, "completed", null, null, null)
    val detail = mutableStateOf(emptyDetail.copy(planActivity = listOf(event), observedAt = epoch + 400.seconds, planOutputUpdatedAt = epoch + 390.seconds))
    setContent { SkillBillTheme(reducedMotion = true) {
      PlanActivityPanel(item, detail.value, rememberSectionCollapse(), rememberPlanScreenState(item.id)) {}
    } }

    onNodeWithText("Agent is working").assertExists()
    runOnIdle { detail.value = detail.value.copy(planOutputUpdatedAt = epoch + 99.seconds) }
    waitForIdle()
    onNodeWithText("Agent is working").assertDoesNotExist()
  }

  @Test
  fun `started stage opens its Activity group and pauses follow`() = runTest {
    val ticker = UiTicker(object : Clock {
      override fun now(): Instant = epoch + 50.seconds
    }, backgroundScope)
    val item = workItem("plan", kind = WorkKind.Phase).copy(standaloneExecution = true)
    val events = (1..40).map { id ->
      PlanAgentActivity("pre-$id", "preplan", "note", "Earlier note $id", epoch + id.seconds, epoch + id.seconds, "claude", null, "completed", null, null, null)
    } + PlanAgentActivity("stage-note", "plan_subtasks", "note", "Current stage note", epoch + 50.seconds, epoch + 50.seconds, "claude", null, "completed", null, null, null)
    val progress = StandalonePlanProgress(
      null,
      listOf(
        StandalonePlanStage("preplan", epoch, epoch + 10.seconds, null, "completed"),
        StandalonePlanStage("plan", epoch + 11.seconds, epoch + 20.seconds, null, "completed"),
        StandalonePlanStage("plan_subtasks", epoch + 21.seconds, null, null, "running"),
      ),
      1, 1, 1, listOf(1), 1, null, null, emptyList(), null,
    )
    val detail = emptyDetail.copy(standalonePlan = progress, planActivity = events)
    val base = item
    val liveItem = base.copy(running = true, control = base.control.copy(phaseId = "plan", invocationId = "plan"))
    runComposeUiTest {
      setContent {
        SkillBillTheme(reducedMotion = true) {
          CompositionLocalProvider(LocalUiTicker provides ticker) {
            CompositionLocalProvider(LocalShellInput provides remember { ShellInput() }) {
              DetailScreen(DetailUiState(liveItem, SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch), DetailScreenHost(false, "Ctrl I", {}, {})) {}
            }
          }
        }
      }

      onNodeWithTag("plan-stage-preplan").performClick()
      onNodeWithText("Jump to latest").assertIsDisplayed()
      onNodeWithText("Earlier note 1").assertIsDisplayed()
    }
  }

  @Test
  fun `phase start briefing link opens that stage raw briefing`() = runComposeUiTest {
    val base = workItem("plan", kind = WorkKind.Phase)
    val item = base.copy(standaloneExecution = true, control = base.control.copy(phaseId = "plan", invocationId = "plan"))
    val raw = "The exact plan stage briefing"
    val progress = StandalonePlanProgress(
      null,
      listOf(
        StandalonePlanStage("preplan", epoch, epoch + 10.seconds, null, "completed"),
        StandalonePlanStage("plan", epoch + 11.seconds, epoch + 20.seconds, raw, "completed"),
        StandalonePlanStage("plan_subtasks", epoch + 21.seconds, null, null, "running"),
      ),
      1, 1, 1, listOf(1), 1, null, null, emptyList(), null,
    )
    val detail = emptyDetail.copy(standalonePlan = progress)
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides remember { ShellInput() }) {
          DetailScreen(DetailUiState(item, SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch), DetailScreenHost(false, "Ctrl I", {}, {})) {}
        }
      }
    }

    onNodeWithTag("plan-stage-plan").performClick()
    onNodeWithText("briefing").performClick()
    onNodeWithText(raw).assertIsDisplayed()
    onNodeWithText("Exactly what the agent received").assertIsDisplayed()
  }

  @Test
  @OptIn(kotlin.time.ExperimentalTime::class)
  fun `quiet Now block reports elapsed time from the UI ticker`() = runTest {
    val ticker = UiTicker(object : Clock {
      override fun now(): Instant = epoch + 301.seconds
    }, backgroundScope)
    val item = workItem("plan", kind = WorkKind.Phase).copy(running = true, startedAt = epoch)
    val activity = PlanAgentActivity("read-1", "preplan", "read", "Plan.kt", epoch, epoch, "claude", "Plan.kt", "completed", null, null, null)
    val detail = emptyDetail.copy(planActivity = listOf(activity), planOutputUpdatedAt = epoch, observedAt = epoch + 301.seconds)
    runComposeUiTest {
      setContent {
        SkillBillTheme(reducedMotion = true) {
          CompositionLocalProvider(LocalUiTicker provides ticker) { PlanNowBlock(item, detail) }
        }
      }

      onNodeWithText("No output for 5m 01s").assertExists()
    }
  }
}
