package skillbill.review.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ReviewContextFailureCode
import skillbill.review.context.model.packet.ReviewLaneSegmentAccounting
import skillbill.review.context.model.packet.ReviewRunLaneSegmentAccountingJson

class ReviewRunLaneSegmentAccountingJsonTest {
  @Test
  fun `empty and legacy encoded rows preserve the storage boundary`() {
    assertNull(ReviewRunLaneSegmentAccountingJson.encode(emptyList()))
    assertEquals(emptyList(), ReviewRunLaneSegmentAccountingJson.decode(null))
    assertEquals(emptyList(), ReviewRunLaneSegmentAccountingJson.decode("[]"))

    val legacy = """[{"segment_id":"legacy","measured_bytes":42,"entry_count":3,"composition_digest":"${"d".repeat(
      64,
    )}"}]"""
    assertEquals(
      listOf(
        ReviewLaneSegmentAccounting(
          segmentId = "legacy",
          measuredBytes = 42,
          entryCount = 3,
          compositionDigest = "d".repeat(64),
        ),
      ),
      ReviewRunLaneSegmentAccountingJson.decode(legacy),
    )
  }

  @Test
  fun `segment accounting json round trips control characters and unicode escapes`() {
    val segment =
      ReviewLaneSegmentAccounting(
        segmentId = "seg-\u000A\t\"\\u0041",
        measuredBytes = 42,
        entryCount = 3,
        compositionDigest = "d".repeat(64),
      )
    val encoded = ReviewRunLaneSegmentAccountingJson.encode(listOf(segment))
    val decoded = ReviewRunLaneSegmentAccountingJson.decode(encoded).single()
    assertEquals(segment, decoded)
  }

  @Test
  fun `malformed segment accounting json raises typed review context schema error`() {
    assertFailsWith<SkillBillRuntimeException> {
      ReviewRunLaneSegmentAccountingJson.decode("""{"segment_id":"x"}""")
    }.also { assertEquals(ReviewContextFailureCode.REVIEW_CONTEXT_SCHEMA, it.code) }
  }
}
