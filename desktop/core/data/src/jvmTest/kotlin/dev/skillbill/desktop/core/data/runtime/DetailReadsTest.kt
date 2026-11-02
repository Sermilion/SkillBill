package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.data.repository.emptyDetail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.SubtaskPhaseResults
import dev.skillbill.desktop.core.domain.model.WorkSubtask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DetailReadsTest {
  @Test
  fun `validation pages keep one phase result per subtask`() {
    val retained = SubtaskPhaseResults(4, emptyList(), null, null)
    val head = emptyDetail().copy(phaseResults = listOf(retained))
    val page = emptyDetail(Instant.fromEpochMilliseconds(1)).copy(phaseResults = listOf(retained))
    val sections = DetailSections.Loaded(head, null, emptyMap(), setOf(DetailList.Validation), stale = false)

    val appended = sections.withList(DetailList.Validation, page, cursor = "next", append = true)

    assertEquals(listOf(retained), appended.detail.phaseResults)
  }

  @Test
  fun `a later page replaces a subtask's phase result in first-seen order on both lists`() {
    val first = SubtaskPhaseResults(1, emptyList(), null, null)
    val second = SubtaskPhaseResults(2, emptyList(), null, null)
    val degraded = first.copy(degradedReadSource = "subtask_phase_results")
    val sections = DetailSections.Loaded(emptyDetail().copy(phaseResults = listOf(first, second)), null, emptyMap(), emptySet(), stale = false)
    val page = emptyDetail().copy(phaseResults = listOf(degraded))

    listOf(DetailList.Subtasks, DetailList.Validation).forEach { list ->
      val merged = sections.withList(list, page, cursor = null, append = false)

      assertEquals(listOf(degraded, second), merged.detail.phaseResults, list.toString())
    }
  }

  @Test
  fun `a failed page replaces cached results for its subtasks`() {
    val old = SubtaskPhaseResults(1, emptyList(), null, null)
    val sections = DetailSections.Loaded(emptyDetail().copy(phaseResults = listOf(old)), null, emptyMap(), emptySet(), stale = false)
    val page = emptyDetail().copy(
      subtasks = listOf(WorkSubtask(1, "One", "blocked", null, null, emptyList())),
      degradedGoalStatusSource = "goal_status",
    )

    val merged = sections.withList(DetailList.Subtasks, page, cursor = null, append = true)

    assertEquals("goal_status", merged.detail.phaseResults.single().degradedReadSource)
  }
}
