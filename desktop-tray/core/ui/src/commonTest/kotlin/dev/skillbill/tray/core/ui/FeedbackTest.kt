package dev.skillbill.tray.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FeedbackTest {
  @Test
  fun `ids are monotonic within one source`() {
    val ids = FeedbackIds()

    assertEquals(listOf(1L, 2L, 3L), List(3) { ids.next() })
  }

  @Test
  fun `pending feedback keeps the newest entries and their ids stay increasing`() {
    val ids = FeedbackIds()
    var pending = emptyList<Feedback>()
    repeat(MAX_PENDING_FEEDBACK + 4) { pending = pending.withFeedback(Feedback(ids.next(), FeedbackTone.PROBLEM, "problem $it")) }

    assertEquals(MAX_PENDING_FEEDBACK, pending.size)
    assertEquals((5L..12L).toList(), pending.map { it.id })
  }

  @Test
  fun `acknowledging removes only the entry with that id`() {
    val pending = listOf(
      Feedback(1, FeedbackTone.PROBLEM, "first"),
      Feedback(2, FeedbackTone.CONFIRMATION, "second"),
    )

    assertEquals(listOf(2L), pending.acknowledged(1).map { it.id })
    assertEquals(pending, pending.acknowledged(99))
  }
}
