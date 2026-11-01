package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.IncidentProgress
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ActivityLinesTest {
  private fun block(token: String, summary: String, atMillis: Long, progress: IncidentProgress = IncidentProgress.Resumed) =
    incident(progress).copy(incidentToken = token, summary = summary, observedAt = Instant.fromEpochMilliseconds(atMillis))

  @Test
  fun `every block appears once, in time order, with the live one included`() {
    val live = block("live", "Validation failed", 3_000, IncidentProgress.Investigating)
    val detail = emptyDetail.copy(
      incident = live,
      incidentHistory = listOf(live, block("old-2", "Merge conflict", 2_000), block("old-1", "Agent stalled", 1_000)),
    )

    val blocks = activityLines(workItem("G"), detail).filter { it.actor == "Blocked" }

    assertEquals(listOf("Agent stalled", "Merge conflict", "Validation failed"), blocks.map { it.action })
  }
}
