package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.designsystem.detail.PeekCheckState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class MonitorCardTest {
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
