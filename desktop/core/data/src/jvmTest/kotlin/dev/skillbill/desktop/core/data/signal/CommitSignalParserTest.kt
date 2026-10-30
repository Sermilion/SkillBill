package dev.skillbill.desktop.core.data.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommitSignalParserTest {
  private val payload = """
    generation=g1
    goals=12
    phases=7
    activity=3
    specs=1
    goals.changed=12:a,b
    phases.changed=7:*
  """.trimIndent()

  @Test
  fun `a full payload yields revisions and changed ids, and a moved topic without a changed line is All`() {
    val snapshot = SignalSnapshot.Initial.advance(parseCommitSignal(payload))

    assertEquals("g1", snapshot.generation)
    assertEquals(mapOf("goals" to 12L, "phases" to 7L, "activity" to 3L, "specs" to 1L), snapshot.revisions)
    assertEquals(ChangedIds.Ids(12, listOf("a", "b")), snapshot.changed["goals"])
    assertEquals(ChangedIds.All, snapshot.changed["phases"])
    assertEquals(ChangedIds.All, snapshot.changed["activity"])
  }

  @Test
  fun `a payload without a generation is invalid`() {
    assertNull(parseCommitSignal(payload.lines().drop(1).joinToString("\n")))
  }
}
