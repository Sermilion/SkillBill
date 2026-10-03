package skillbill.infrastructure.contracts.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ReviewContextFailureCode

class ReviewContextStageDefinitionSeamTest {
  @Test
  fun `each new parse seam names its definition on an invalid payload`() {
    val verification =
      assertFailsWith<SkillBillRuntimeException> {
        ReviewContextSchemaValidator.validateVerificationLaunch(
          mapOf("contract_version" to REVIEW_CONTEXT_CONTRACT_VERSION, "kind" to "verification_launch"),
          "verification",
        )
      }.also { assertEquals(ReviewContextFailureCode.REVIEW_CONTEXT_SCHEMA, it.code) }
    val adjudication =
      assertFailsWith<SkillBillRuntimeException> {
        ReviewContextSchemaValidator.validateAdjudicationLaunch(
          mapOf("contract_version" to REVIEW_CONTEXT_CONTRACT_VERSION, "kind" to "adjudication_launch"),
          "adjudication",
        )
      }.also { assertEquals(ReviewContextFailureCode.REVIEW_CONTEXT_SCHEMA, it.code) }
    val verdict =
      assertFailsWith<SkillBillRuntimeException> {
        ReviewContextSchemaValidator.validateFindingVerdict(
          mapOf("contract_version" to REVIEW_CONTEXT_CONTRACT_VERSION, "kind" to "finding_verdict"),
          "verdict",
        )
      }.also { assertEquals(ReviewContextFailureCode.REVIEW_CONTEXT_SCHEMA, it.code) }
    val projection =
      assertFailsWith<SkillBillRuntimeException> {
        ReviewContextSchemaValidator.validateSpecIntentProjection(emptyMap(), "projection")
      }.also { assertEquals(ReviewContextFailureCode.REVIEW_CONTEXT_SCHEMA, it.code) }
    assertTrue("for definition 'verification_launch'" in verification.message.orEmpty())
    assertTrue("for definition 'adjudication_launch'" in adjudication.message.orEmpty())
    assertTrue("for definition 'finding_verdict'" in verdict.message.orEmpty())
    assertTrue("for definition 'spec_intent_projection'" in projection.message.orEmpty())
  }
}
