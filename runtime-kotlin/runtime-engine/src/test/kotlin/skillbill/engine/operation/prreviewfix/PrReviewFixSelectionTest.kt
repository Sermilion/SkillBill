package skillbill.engine.operation.prreviewfix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PrReviewFixSelectionTest {
  private val ordinals = linkedMapOf("T1" to "PRRT_a", "T2" to "PRRT_b", "T3" to "PRRT_c")

  @Test
  fun `a selection resolves to exactly the threads and options it names`() {
    listOf(
      "all-recommended" to
        listOf(
          PrReviewFixSelectedThread("T1", "PRRT_a", "1"),
          PrReviewFixSelectedThread("T2", "PRRT_b", "1"),
          PrReviewFixSelectedThread("T3", "PRRT_c", "1"),
        ),
      "T2=3" to listOf(PrReviewFixSelectedThread("T2", "PRRT_b", "3")),
      "PRRT_b=3" to listOf(PrReviewFixSelectedThread("T2", "PRRT_b", "3")),
    ).forEach { (select, expected) ->
      assertEquals(PrReviewFixSelection.Selected(expected), parsePrReviewFixSelection(select, ordinals), select)
    }
  }

  @Test
  fun `an unknown, already-handled, repeated, or empty selection is a usage error`() {
    listOf("T9=1", "PRRT_d=1", "T1=1,T1=2", " ", "T1", "T1=banana").forEach { select ->
      assertIs<PrReviewFixSelection.Invalid>(parsePrReviewFixSelection(select, ordinals), select)
    }
  }
}
