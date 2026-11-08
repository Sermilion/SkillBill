package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.PhaseCard
import dev.skillbill.desktop.core.domain.model.PhaseCardState
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtaskPhasesTest {
  private fun card(id: String, state: PhaseCardState) = PhaseCard(
    phaseId = id, state = state, round = null, startedAt = null, finishedAt = null, durationMillis = null,
    agentId = null, model = null, effort = null, blockedReason = null, files = emptyList(), edit = null,
    activityLabel = null, activityAt = null, output = null, pullRequestUrl = null, pullRequestNumber = null,
    ciCycles = emptyList(), repairedChecks = emptyList(), conflictBaseBranch = null, fixCommitSha = null,
  )

  @Test
  fun `selection prefers active failure or waiting then the last done and ignores stale choices`() {
    val tabs = phaseTabs(listOf("preplan", "plan", "implement", "validate", "review"), listOf(
      card("preplan", PhaseCardState.Done), card("plan", PhaseCardState.Done), card("implement", PhaseCardState.Skipped), card("validate", PhaseCardState.Blocked),
    ))
    assertEquals("validate", selectedPhase(tabs, null))
    assertEquals("validate", selectedPhase(tabs, "review"))
    assertEquals("implement", selectedPhase(tabs, "implement"))
    assertEquals("4 of 5", phasesNote(tabs, running = true))
  }

  @Test
  fun `completed phases open on the last done tab`() {
    val tabs = phaseTabs(listOf("preplan", "plan", "implement"), listOf(card("preplan", PhaseCardState.Done), card("plan", PhaseCardState.Done)))
    assertEquals("plan", selectedPhase(tabs, null))
    assertEquals("2", phasesNote(tabs, running = false))
  }
}
