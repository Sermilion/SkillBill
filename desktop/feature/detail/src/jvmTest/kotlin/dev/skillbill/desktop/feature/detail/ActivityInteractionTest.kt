package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ActivityBlock
import dev.skillbill.desktop.core.domain.model.ActivityBlockKind
import dev.skillbill.desktop.core.domain.model.ActivityGroup
import dev.skillbill.desktop.core.domain.model.ActivityStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class ActivityInteractionTest {
  @Test
  fun `untimed blocked attempts show no phase duration and count one subtask`() = runComposeUiTest {
    val start = Instant.parse("2026-11-01T08:00:00Z")
    val end = Instant.parse("2026-11-01T10:00:00Z")
    val block = ActivityBlock("incident", end, ActivityBlockKind.BLOCKED, null, null, null, false)
    val attempt = ActivityGroup("first", 1, "Child", ActivityStatus.BLOCKED, start, end, emptyList(), listOf(block))
    val attempts = listOf(attempt, attempt.copy(id = "second"))
    setContent { SkillBillTheme(reducedMotion = true) {
      androidx.compose.foundation.layout.Column {
        ActivityCard(attempt, false, end, onToggle = {})
        ActivityStats(attempts, end)
      }
    } }

    onNodeWithText("2h 00m").assertDoesNotExist()
    onNodeWithText("1 subtask").assertExists()
  }

  @Test
  fun `blocks filter expands matching finished cards and permits collapsing them`() = runComposeUiTest {
    val at = Instant.parse("2026-11-01T08:00:00Z")
    val blocked = ActivityGroup("blocked", 1, "Recovered child", ActivityStatus.COMPLETE, at, at,
      emptyList(), listOf(ActivityBlock("incident", at, ActivityBlockKind.BLOCKED, null, null, "Needs a decision", false)))
    val clean = blocked.copy(id = "clean", subtaskId = 2, name = "Clean child", blocks = emptyList())
    var jumped: Float? = null
    setContent { SkillBillTheme(reducedMotion = true) { ActivityTimeline(listOf(blocked, clean), "goal", onJump = { jumped = it }) } }

    onNodeWithText("Needs a decision").assertDoesNotExist()
    onNodeWithText("Blocks 1").performClick()
    onNodeWithText("Clean child").assertDoesNotExist()
    onNodeWithText("Needs a decision").assertExists()
    onNode(hasText("Recovered child") and hasClickAction()).performClick()
    onNodeWithText("Needs a decision").assertDoesNotExist()
    onNodeWithText("All", substring = false).performClick()
    onNodeWithText("Clean child").assertExists()
    onNodeWithText("Needs a decision").assertDoesNotExist()
    onNodeWithText("Blocks 1").performClick()
    onNode(SemanticsMatcher("timeline jump") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Jump to Recovered child" }).performClick()
    onNodeWithText("Clean child").assertExists()
    onNodeWithText("Needs a decision").assertExists()
    runOnIdle { assertNotNull(jumped) }
  }

  @Test
  fun `decision block falls back to current reason and can copy it`() = runComposeUiTest {
    val at = Instant.parse("2026-11-01T08:00:00Z")
    val block = ActivityBlock("decision", at, ActivityBlockKind.AWAITING_DECISION, null, null, null, true)
    val group = ActivityGroup("goal", null, "Goal finish", ActivityStatus.INTERRUPTED, null, null, emptyList(), listOf(block))
    var copied: String? = null
    val reason = "The goal needs an operator decision before it can continue."

    setContent {
      SkillBillTheme(reducedMotion = true) {
        ActivityTimeline(listOf(group), "goal", blockReasonFallback = reason, onCopyReason = { copied = it })
      }
    }

    onNodeWithText(reason).assertExists()
    onNodeWithContentDescription("Copy block reason").performClick()
    runOnIdle { assertEquals(reason, copied) }
  }
}
