package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.ActivityBlock
import dev.skillbill.desktop.core.domain.model.ActivityBlockKind
import dev.skillbill.desktop.core.domain.model.ActivityFact
import dev.skillbill.desktop.core.domain.model.ActivityGroup
import dev.skillbill.desktop.core.domain.model.ActivityPhase
import dev.skillbill.desktop.core.domain.model.ActivityStatus
import dev.skillbill.desktop.core.domain.model.IncidentProgress
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Instant

class ActivityProjectionTest {
  private fun at(minutes: Long) = Instant.fromEpochMilliseconds(minutes * 60_000)
  private fun phase(id: String, start: Long, end: Long?) = ActivityPhase(id, 1, at(start), end?.let(::at), "codex", null, null, null, null)
  private fun group(phases: List<ActivityPhase>, blocks: List<ActivityBlock> = emptyList()) =
    ActivityGroup("child", 1, "Child", ActivityStatus.COMPLETE, at(0), at(40), phases, blocks)

  @Test
  fun `blocks and long idle gaps split a repeated audit loop`() {
    val block = ActivityBlock("stop", at(5), ActivityBlockKind.BLOCKED, "audit", 1, "Decision needed", true)
    val entries = activityEntries(group(listOf(phase("audit", 0, 1), phase("audit_plan_fix", 2, 3), phase("audit", 6, 7), phase("audit_implement_fix", 20, 21)), listOf(block)))

    assertEquals(2, assertIs<ActivityEntry.Phases>(entries[0]).phases.size)
    assertIs<ActivityEntry.Block>(entries[1])
    assertEquals(1, assertIs<ActivityEntry.Phases>(entries[2]).phases.size)
    assertEquals(13 * 60_000L, assertIs<ActivityEntry.Wait>(entries[3]).milliseconds)
    assertEquals(1, assertIs<ActivityEntry.Phases>(entries[4]).phases.size)
  }

  @Test
  fun `untimed goal finish facts do not add phases time or idle time`() {
    val child = group(listOf(phase("implement", 0, 2)))
    val finish = ActivityGroup("finish", null, "Goal finish", ActivityStatus.RUNNING, null, null, emptyList(), emptyList(),
      after = listOf(ActivityFact("monitor", "latest update", at(100), "codex", "gpt-model", "high", "CI", true)))

    assertEquals(0, finish.activeMilliseconds(at(120)))
    val spans = activitySpans(listOf(child, finish), at(120))
    assertFalse(spans.any { it.groupId == null })
    assertEquals(true, spans.last().running)
  }

  @Test
  fun `block notices do not imply an idle interval and cannot shorten phase idle totals`() {
    val blocks = listOf(5L, 30L).map { ActivityBlock("block-$it", at(it), ActivityBlockKind.PULL_REQUEST_FAILED, null, null, null, false) }
    assertEquals(2, activityEntries(group(emptyList(), blocks)).size)
    val first = group(listOf(phase("implement", 0, 2)), blocks.take(1))
    val second = group(listOf(phase("implement", 30, 32))).copy(id = "second", subtaskId = 2)
    assertEquals(28 * 60_000L, activityIdleMilliseconds(listOf(first, second), at(40)))
  }

  @Test
  fun `block within a long phase gap does not shorten the displayed wait`() {
    val block = ActivityBlock("stop", at(16), ActivityBlockKind.BLOCKED, null, null, null, false)
    val entries = activityEntries(group(listOf(phase("implement", 0, 1), phase("review", 21, 22)), listOf(block)))

    assertEquals(20 * 60_000L, entries.filterIsInstance<ActivityEntry.Wait>().single().milliseconds)
    assertEquals(20 * 60_000L, activityIdleMilliseconds(listOf(group(listOf(phase("implement", 0, 1), phase("review", 21, 22)), listOf(block))), at(40)))
  }

  @Test
  fun `the open phase model leads and a finished run still leads with the longest model`() {
    val open = listOf(
      ActivityPhase("implement", 1, at(0), at(80), "codex", "gpt-6.1-sol", "high", null, null),
      ActivityPhase("simplify", 1, at(80), at(90), "codex", "gpt-6-luna", "high", null, null),
      ActivityPhase("review", 1, at(90), null, "cursor", "grok-4.7-high-fast", null, null, null),
    )
    assertEquals(listOf("grok-4.7-high-fast", "gpt-6.1-sol", "gpt-6-luna"), activityModelLabels(open, at(100)))
    val finished = open.dropLast(1)
    assertEquals(listOf("gpt-6.1-sol", "gpt-6-luna"), activityModelLabels(finished, at(100)))
  }

  @Test
  fun `running phase stays outside folded completed attempts`() {
    val entries = activityEntries(group(listOf(phase("review", 0, 1), phase("review_fix", 2, null))))
    assertEquals(2, entries.size)
    assertEquals(1, assertIs<ActivityEntry.Phases>(entries.last()).phases.size)
  }

  @Test
  fun `standalone timeline keeps every recorded incident once`() {
    fun block(token: String, at: Long, state: IncidentProgress) = incident(state).copy(incidentToken = token, observedAt = Instant.fromEpochMilliseconds(at))
    val live = block("live", 3000, IncidentProgress.Investigating)
    val detail = emptyDetail.copy(incident = live, incidentHistory = listOf(live, block("old", 1000, IncidentProgress.Resumed)))
    val entries = activityEntries(standaloneActivity(workItem("phase"), detail).single()).filterIsInstance<ActivityEntry.Block>()
    assertEquals(listOf("old", "live"), entries.map { it.block.id })
  }
}
