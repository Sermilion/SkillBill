package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.PeekCheckState
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.CiCheck
import dev.skillbill.desktop.core.domain.model.CiCycle
import dev.skillbill.desktop.core.domain.model.PhaseCardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class MonitorCardTest {
  @Test
  fun `completed goal with unfinished monitor evidence never appears live`() = runComposeUiTest {
    val at = Instant.parse("2026-11-13T15:44:00Z")
    val cycle = CiCycle(2, at, at, null, null, null, listOf(CiCheck("CI build", "pending", null, null, null, null)))
    val stale = finishCard("monitor", PhaseCardState.Running).copy(ciCycles = listOf(cycle))
    val item = workItem("completed").copy(
      lifecycle = Lifecycle.Terminal, finishedAt = at,
    )
    assertEquals(StatusKind.Completed,
      goalFinishStatus(item, emptyDetail.copy(goalFinishCards = listOf(stale))))
    assertEquals(PeekCheckState.Waiting, checkState("pending", watching = false))

    setContent { SkillBillTheme(reducedMotion = true) { MonitorCard(stale.copy(state = PhaseCardState.Done), null, at) } }
    onNodeWithText("CI build").assertExists()
    onNodeWithText("last poll 0s ago").assertDoesNotExist()
    onNodeWithText("Cycle 2 · Running").assertDoesNotExist()
    onNodeWithText("Cycle 2 · Final outcome not retained").assertExists()
    onNodeWithText("Final CI outcome was not retained. Checks show the last recorded observation.").assertExists()
  }

  @Test
  fun `restarted cycle supplies current checks and poll age while prior cycle stays in history`() = runComposeUiTest {
    val oldTime = Instant.parse("2026-11-08T08:00:00Z")
    val now = Instant.parse("2026-11-08T08:00:45Z")
    val older = CiCycle(2, oldTime, oldTime, oldTime, "success", null, listOf(CiCheck("Old check", "pass", null, null, null, null)))
    val running = CiCycle(1, now, oldTime, null, null, null, listOf(CiCheck("Current check", "pending", null, null, null, null)))
    val card = finishCard("monitor", PhaseCardState.Running).copy(ciCycles = listOf(older, running))
    setContent { SkillBillTheme(reducedMotion = true) { MonitorCard(card, null, now) } }
    onNodeWithText("Current check").assertExists()
    onNodeWithText("Old check").assertDoesNotExist()
    onNodeWithText("last poll 45s ago").assertExists()
    onNodeWithText("Cycle 2 · Success").assertExists()

    val finished = running.copy(finishedAt = now, outcome = "success")
    setContent { SkillBillTheme(reducedMotion = true) { MonitorCard(card.copy(ciCycles = listOf(older, finished)), null, now) } }
    onNodeWithText("Current check").assertExists()
    onNodeWithText("last poll 45s ago").assertDoesNotExist()
    onNodeWithText("Cycle 1 · Success").assertExists()

    val conflicted = finished.copy(outcome = "conflicted", checks = emptyList())
    setContent { SkillBillTheme(reducedMotion = true) { MonitorCard(card.copy(ciCycles = listOf(older, conflicted)), null, now) } }
    onNodeWithText("Current check").assertDoesNotExist()
    onNodeWithText("Cycle 1 · Conflicted").assertExists()

    val conflictedWithPassingCheck = conflicted.copy(checks = listOf(CiCheck("Passing check", "pass", null, null, null, null)))
    setContent { SkillBillTheme(reducedMotion = true) { MonitorCard(card.copy(ciCycles = listOf(older, conflictedWithPassingCheck)), null, now) } }
    onNodeWithText("Passing check").assertExists()
    onNodeWithText("Cycle 1 · Conflicted").assertExists()

    setContent { SkillBillTheme(reducedMotion = true) { MonitorCard(card.copy(ciCycles = listOf(older, conflictedWithPassingCheck, running.copy(cycle = 2))), null, now) } }
    onNodeWithText("Cycle 1 · Conflicted").assertExists()
    onNodeWithText("Cycle 2 · Running").assertExists()

    val failed = running.copy(cycle = 3, finishedAt = now, outcome = "failure")
    setContent { SkillBillTheme(reducedMotion = true) { MonitorCard(card.copy(ciCycles = listOf(older, conflictedWithPassingCheck, failed)), null, now) } }
    onNodeWithText("Cycle 3 · Failure").assertExists()
  }

  @Test
  fun `CI buckets retain failure and pending states with recorded durations and poll age`() {
    assertEquals(PeekCheckState.Ok, checkState("pass"))
    assertEquals(PeekCheckState.Bad, checkState("fail"))
    assertEquals(PeekCheckState.Run, checkState("pending"))
    assertEquals(PeekCheckState.Waiting, checkState("skipping"))
    assertEquals(PeekCheckState.Waiting, checkState("cancel"))
    val started = Instant.parse("2026-11-08T08:00:00Z")
    val completed = Instant.parse("2026-11-08T08:03:51Z")
    assertEquals("3m 51s", checkDurationLabel((completed - started).inWholeMilliseconds))
    assertEquals("41s", checkDurationLabel(41000))
    assertEquals("1h 2m", checkDurationLabel(3720000))
    assertEquals("last poll 45s ago", lastPollLabel(started, Instant.parse("2026-11-08T08:00:45Z")))
  }
}
