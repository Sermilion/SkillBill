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
  fun `auto opens only running blocked or failed in priority and preserves recorded order`() {
    val cards = listOf(
      card("plan", PhaseCardState.Failed), card("preplan", PhaseCardState.Blocked),
      card("implement", PhaseCardState.Running), card("validate", PhaseCardState.Paused),
    )
    val tabs = phaseTabs(listOf("preplan", "plan", "implement", "validate", "review"), cards)
    assertEquals("implement", selectedPhase(tabs, cards, null))
    val withoutRunning = cards.filterNot { it.state == PhaseCardState.Running }
    assertEquals("preplan", selectedPhase(phaseTabs(tabs.map { it.id }, withoutRunning), withoutRunning, PhaseDisclosure.Auto))
    val onlyFailed = withoutRunning.filterNot { it.state == PhaseCardState.Blocked }
    assertEquals("plan", selectedPhase(phaseTabs(tabs.map { it.id }, onlyFailed), onlyFailed, PhaseDisclosure.Auto))
    assertEquals("preplan", selectedPhase(tabs, cards, PhaseDisclosure.Open("preplan")))
    assertEquals(null, selectedPhase(tabs, cards, PhaseDisclosure.Folded))
    assertEquals("4 of 5", phasesNote(tabs, running = true))
  }

  @Test
  fun `done skipped and paused cards stay folded and unlisted cards remain reachable`() {
    val cards = listOf(card("old_phase", PhaseCardState.Done), card("implement", PhaseCardState.Paused))
    val tabs = phaseTabs(listOf("preplan", "implement"), cards)
    assertEquals(listOf("preplan", "implement", "old_phase"), tabs.map { it.id })
    assertEquals(null, selectedPhase(tabs, cards, PhaseDisclosure.Auto))
    assertEquals("old_phase", selectedPhase(tabs, cards, PhaseDisclosure.Open("old_phase")))
    assertEquals("2", phasesNote(tabs, running = false))
  }
}
