package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import dev.skillbill.desktop.core.domain.model.ActivityGroupKind
import dev.skillbill.desktop.core.domain.model.ActivityStatus
import dev.skillbill.desktop.core.domain.model.ActivityPhase
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
  fun `loop disclosure exposes its state and expands the recorded attempts`() = runComposeUiTest {
    val at = Instant.parse("2026-11-01T08:00:00Z")
    val phases = listOf("audit", "audit_plan_fix").map { id ->
      ActivityPhase(id, 1, at, at, "codex", null, null, null, null)
    }
    val group = ActivityGroup("child", 1, "Child", ActivityStatus.COMPLETE, at, at, phases, emptyList())
    setContent { SkillBillTheme(reducedMotion = true) { ActivityCard(group, true, at, onToggle = {}) } }

    val expand = SemanticsMatcher("expand loop") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Expand loop" }
    onNode(expand).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")).performClick()
    onNodeWithText("audit", substring = false).assertExists()
    onNodeWithText("audit_plan_fix", substring = false).assertExists()
    val collapse = SemanticsMatcher("collapse loop") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Collapse loop" }
    onNode(collapse).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")).performClick()
    onNodeWithText("audit_plan_fix", substring = false).assertDoesNotExist()
  }

  @Test
  fun `only clipped summaries offer text expansion and remain collapsible`() = runComposeUiTest {
    val at = Instant.parse("2026-11-01T08:00:00Z")
    val short = "Short summary."
    val long = "Details about the phase result. ".repeat(80)
    val phases = listOf("implement" to short, "simplify" to long).map { (id, summary) ->
      ActivityPhase(id, 1, at, at, "codex", null, null, summary, null)
    }
    val group = ActivityGroup("child", 1, "Child", ActivityStatus.COMPLETE, at, at, phases, emptyList())
    setContent { SkillBillTheme(reducedMotion = true) { ActivityCard(group, true, at, onToggle = {}) } }

    onNode(hasText(short) and hasClickAction()).assertDoesNotExist()
    val expand = SemanticsMatcher("read full text") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Read full text" }
    onNode(expand).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")).performClick()
    val collapse = SemanticsMatcher("collapse text") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Collapse text" }
    onNode(collapse).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")).performClick()
    onNode(expand).assertExists()
  }

  @Test
  fun `historical parent decisions appear in general blocks without a current decision request`() = runComposeUiTest {
    val at = Instant.parse("2026-11-01T08:00:00Z")
    val group = ActivityGroup("parent", null, "General blocks", ActivityStatus.INTERRUPTED, null, null,
      emptyList(), listOf(ActivityBlock("old-decision", at, ActivityBlockKind.AWAITING_DECISION, null, null, null, true)),
      kind = ActivityGroupKind.GENERAL_BLOCKS)
    setContent { SkillBillTheme(reducedMotion = true) { ActivityTimeline(listOf(group), "goal") } }

    onNodeWithText("General blocks").assertExists()
    onNode(hasText("General blocks") and hasClickAction()).assertDoesNotExist()
    onNodeWithText("Decision requested").assertExists()
    onNodeWithText("Waiting for your decision").assertDoesNotExist()
    onNodeWithText("needs your decision").assertDoesNotExist()
    onNodeWithText("No recorded activity.").assertDoesNotExist()
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
    val blocksTab = hasText("Blocks") and hasText("1") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    onNode(blocksTab).performClick()
    onNodeWithText("Clean child").assertDoesNotExist()
    onNodeWithText("Needs a decision").assertExists()
    onNode(hasText("Recovered child") and hasClickAction()).performClick()
    onNodeWithText("Needs a decision").assertDoesNotExist()
    onNodeWithText("All", substring = false).performClick()
    onNodeWithText("Clean child").assertExists()
    onNodeWithText("Needs a decision").assertDoesNotExist()
    onNode(blocksTab).performClick()
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
