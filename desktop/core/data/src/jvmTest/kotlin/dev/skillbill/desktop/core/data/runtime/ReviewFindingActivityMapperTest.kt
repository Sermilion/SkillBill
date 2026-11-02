package dev.skillbill.desktop.core.data.runtime

import skillbill.review.model.ReviewFindingAction
import skillbill.review.model.ReviewFindingActionKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewFindingActivityMapperTest {
  @Test
  fun `verification distinguishes a start an incomplete outcome and all findings fixed`() {
    val started = ReviewFindingAction(1, ReviewFindingActionKind.VERIFY, Instant.EPOCH)
    assertEquals(listOf("Findings verification started"), started.toActivityFacts().map { it.name })
    val partial = started.copy(finishedAt = Instant.EPOCH, completed = true, fixed = 2, open = 1)
    assertEquals("2 fixed · 1 still open", partial.toActivityFacts().last().summary)
    assertEquals("All 3 findings fixed", partial.copy(fixed = 3, open = 0).toActivityFacts().last().summary)
    assertEquals("Stopped before completion", partial.copy(completed = false).toActivityFacts().last().summary)
  }
}
