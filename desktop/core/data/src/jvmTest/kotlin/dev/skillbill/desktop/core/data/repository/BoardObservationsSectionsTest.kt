package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkLiveSection
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class BoardObservationsSectionsTest {
  @Test
  fun `finished today follows liveSection only`() {
    val finishedAt = Instant.fromEpochMilliseconds(1)
    val onNeedsYou = workItem("needs", lifecycle = Lifecycle.Terminal, finishedAt = finishedAt)
      .copy(liveSection = WorkLiveSection.NeedsYou)
    val finished = workItem("done", lifecycle = Lifecycle.Terminal, finishedAt = finishedAt)
      .copy(liveSection = null)
    val noTimestamp = workItem("open", lifecycle = Lifecycle.Idle)
    val sections = sectionsOf(listOf(onNeedsYou, finished, noTimestamp))
    assertEquals(
      listOf(onNeedsYou),
      sections.first { it.kind == BoardSectionKind.NeedsYou }.items,
    )
    assertEquals(
      listOf(finished, noTimestamp),
      sections.first { it.kind == BoardSectionKind.Finished }.items,
    )
  }
}
