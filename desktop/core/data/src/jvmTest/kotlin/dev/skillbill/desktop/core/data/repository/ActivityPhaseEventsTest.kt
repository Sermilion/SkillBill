package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.domain.model.ActivityResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import skillbill.engine.work.timeline.model.WorkActivityBlock
import skillbill.engine.work.timeline.model.WorkActivityBlockKind
import skillbill.engine.work.timeline.model.WorkActivityGroup
import skillbill.engine.work.timeline.model.WorkActivityPhase
import skillbill.engine.work.timeline.model.WorkActivityResult
import skillbill.engine.work.timeline.model.WorkActivityStatus

class ActivityPhaseEventsTest {
  @Test
  fun `a block between phase start and settlement stays between them in the card timeline`() {
    val start = Instant.parse("2026-11-08T08:00:00Z")
    val block = start.plusSeconds(10)
    val finish = start.plusSeconds(20)
    val source = WorkActivityResult.Listed(listOf(WorkActivityGroup(
      "subtask-1", 1, "First subtask", WorkActivityStatus.COMPLETE, start, finish,
      listOf(WorkActivityPhase("implement", 1, start, finish, null, null, null, null, "completed")),
      listOf(
        WorkActivityBlock("missing-reason", block, WorkActivityBlockKind.BLOCKED, "implement", 1, null, false),
        WorkActivityBlock("block", block, WorkActivityBlockKind.BLOCKED, "implement", 1, "Needs input", false),
      ),
    )))
    val events = (source.toDomain() as ActivityResult.Listed).groups.single().phaseEvents
    assertEquals(listOf("Started · attempt 1", "Needs input", "Settled · completed"), events.map { it.label })
    assertEquals(listOf(false, true, false), events.map { it.blocked })
    assertEquals(listOf("implement", "implement", "implement"), events.map { it.phaseId })
    assertEquals(listOf(start, block, finish).map { it.toString() }, events.map { it.at.toString() })
  }
}
