package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.data.repository.emptyDetail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.SubtaskPhaseResults
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
}
