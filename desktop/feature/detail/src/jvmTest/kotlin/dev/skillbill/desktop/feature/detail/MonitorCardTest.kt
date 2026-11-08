package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.PeekCheckState
import dev.skillbill.desktop.core.domain.model.CiCheck
import dev.skillbill.desktop.core.domain.model.CiCycle
import dev.skillbill.desktop.core.domain.model.PhaseCardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class MonitorCardTest {
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
