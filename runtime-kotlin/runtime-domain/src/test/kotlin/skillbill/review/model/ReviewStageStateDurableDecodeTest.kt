package skillbill.review.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ReviewContextFailureCode

class ReviewStageStateDurableDecodeTest {
  @Test
  fun `unknown review stage wire token fails with review typed error`() {
    assertFailsWith<SkillBillRuntimeException> {
      ReviewStage.fromWire("not-a-stage")
    }.also { assertEquals(ReviewContextFailureCode.REVIEW_CONTEXT_SCHEMA, it.code) }
  }

  @Test
  fun `unknown durable review tokens fail with review typed errors`() {
    assertFailsWith<SkillBillRuntimeException> {
      ReviewClaimVerdict.fromWire("not-a-verdict")
    }.also { assertEquals(ReviewContextFailureCode.REVIEW_CONTEXT_SCHEMA, it.code) }
    assertFailsWith<SkillBillRuntimeException> {
      ReviewScopeDisposition.fromWire("not-a-disposition")
    }.also { assertEquals(ReviewContextFailureCode.REVIEW_CONTEXT_SCHEMA, it.code) }
    assertFailsWith<SkillBillRuntimeException> {
      ReviewSeverityAdjustmentDirection.fromWire("not-a-direction")
    }.also { assertEquals(ReviewContextFailureCode.REVIEW_CONTEXT_SCHEMA, it.code) }
    assertFailsWith<SkillBillRuntimeException> {
      ReviewStageReached.fromWire("not-a-reached-state")
    }.also { assertEquals(ReviewContextFailureCode.REVIEW_CONTEXT_SCHEMA, it.code) }
  }

  @Test
  fun `citation decodeList maps non-numeric line to review typed error`() {
    assertFailsWith<SkillBillRuntimeException> {
      ReviewFindingCitation.decodeList("src/A.kt\tnot-a-line")
    }.also { assertEquals(ReviewContextFailureCode.REVIEW_CONTEXT_SCHEMA, it.code) }
  }
}
