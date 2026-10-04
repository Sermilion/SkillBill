package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.runloop.core.resolveReviewPassNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeReviewPassNumberDerivationTest {
  @Test
  fun `review pass number is always one`() {
    assertEquals(1, resolveReviewPassNumber(reservedPassNumber = null, completedReviewPassCount = 0))
    assertEquals(1, resolveReviewPassNumber(reservedPassNumber = 1, completedReviewPassCount = 0))
    assertEquals(1, resolveReviewPassNumber(reservedPassNumber = 1, completedReviewPassCount = 1))
  }

  @Test
  fun `review pass reservation rejects pass numbers above one`() {
    assertFailsWith<IllegalArgumentException> {
      resolveReviewPassNumber(reservedPassNumber = 2, completedReviewPassCount = 1)
    }
  }

  @Test
  fun `review completed pass count cannot exceed one`() {
    assertFailsWith<IllegalArgumentException> {
      resolveReviewPassNumber(reservedPassNumber = null, completedReviewPassCount = 2)
    }
  }
}
