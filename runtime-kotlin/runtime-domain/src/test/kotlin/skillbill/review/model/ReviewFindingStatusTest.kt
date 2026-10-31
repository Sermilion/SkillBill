package skillbill.review.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewFindingStatusTest {
  @Test
  fun `settling feedback outranks the loop outcome, which outranks an acceptance`() {
    assertEquals(ReviewFindingStatus.FIXED, ReviewFindingStatus.of("fix_applied", "carried"))
    assertEquals(ReviewFindingStatus.DECLINED, ReviewFindingStatus.of("fix_rejected", "addressed"))
    assertEquals(ReviewFindingStatus.FALSE_POSITIVE, ReviewFindingStatus.of("false_positive", null))
    assertEquals(ReviewFindingStatus.FIXED, ReviewFindingStatus.of("finding_accepted", "addressed"))
    assertEquals(ReviewFindingStatus.REJECTED, ReviewFindingStatus.of(null, "rejected"))
    assertEquals(ReviewFindingStatus.ACCEPTED, ReviewFindingStatus.of("finding_accepted", "carried"))
    assertEquals(ReviewFindingStatus.OPEN, ReviewFindingStatus.of(null, "carried"))
    assertEquals(ReviewFindingStatus.OPEN, ReviewFindingStatus.of(null, null))
  }
}
