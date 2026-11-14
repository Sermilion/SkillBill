package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.WorkLiveSection
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class BoardObservationsSectionsTest {
  @Test
  fun `finished today uses operator finalization or goal completion not bare live section null`() {
    val finishedAt = Instant.fromEpochMilliseconds(1)
    val onNeedsYou = workItem("needs", lifecycle = Lifecycle.Terminal, finishedAt = finishedAt)
      .copy(liveSection = WorkLiveSection.NeedsYou)
    val finalized = workItem("done", kind = WorkKind.Phase, lifecycle = Lifecycle.Terminal, finishedAt = finishedAt)
      .copy(liveSection = null, recentlyFinishedPhase = true)
    val goalFinished = workItem("goal-done", lifecycle = Lifecycle.Terminal, finishedAt = finishedAt)
      .copy(liveSection = null, doneWithinDay = true)
    val canceled = workItem("canceled", kind = WorkKind.Phase, lifecycle = Lifecycle.Canceled, finishedAt = finishedAt)
      .copy(liveSection = null)
    val failedNeedsYou = workItem("failed", kind = WorkKind.Phase, lifecycle = Lifecycle.Failed, finishedAt = finishedAt)
      .copy(liveSection = WorkLiveSection.NeedsYou, requiresAttention = true)
    val open = workItem("open", lifecycle = Lifecycle.Idle)
    val sections = sectionsOf(listOf(onNeedsYou, finalized, goalFinished, canceled, failedNeedsYou, open))
    assertEquals(
      listOf(onNeedsYou, failedNeedsYou),
      sections.first { it.kind == BoardSectionKind.NeedsYou }.items,
    )
    assertEquals(
      listOf(finalized, goalFinished),
      sections.first { it.kind == BoardSectionKind.Finished }.items,
    )
    assertEquals(emptyList(), sections.first { it.kind == BoardSectionKind.Phases }.items)
  }
}
