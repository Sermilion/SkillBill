package skillbill.engine.featuretask.qa.receipt

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.infrastructure.contracts.qa.QaReceiptSchemaValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QaReceiptValidatorTest {
  private val validator = QaReceiptValidator(QaReceiptSchemaValidator())
  private val expected = qaTestExpected()

  @Test
  fun `the schema and the decoder accept one receipt per verdict`() {
    QaVerdict.entries.forEach { verdict ->
      val receipt = validator.validate(qaTestJson(qaTestReceipt(verdict)), expected)

      assertEquals(verdict, receipt.verdict)
    }
  }

  @Test
  fun `the schema rejects a missing skip reason, an unknown skip reason, and an inline evidence body`() {
    val skipped = qaTestReceipt(QaVerdict.SKIPPED)
    val findings = qaTestReceipt(QaVerdict.FINDINGS_OPEN)
    val finding = (findings.getValue("findings") as List<*>).single().asReceiptObject()
    val ref = (finding.getValue("evidence_refs") as List<*>).single().asReceiptObject()
    val inlineBody =
      findings + ("findings" to listOf(finding + ("evidence_refs" to listOf(ref + ("body" to "raw logcat lines")))))

    listOf(skipped - "skip_reason", skipped + ("skip_reason" to "no_device"), inlineBody).forEach { receipt ->
      assertRejected(FeatureTaskRuntimeFailureCode.INVALID_QA_RECEIPT_SCHEMA, receipt)
    }
  }

  @Test
  fun `a passed receipt needs a passing assertion on an exercised flow`() {
    val receipt = qaTestReceipt(QaVerdict.PASSED) + ("assertions" to emptyList<Any?>())

    assertRejected(FeatureTaskRuntimeFailureCode.QA_RECEIPT_UNSUPPORTED_CLAIM, receipt)
  }

  @Test
  fun `a receipt for a different tree is rejected`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(qaTestJson(qaTestReceipt(QaVerdict.PASSED)), expected.copy(treeIdentity = "f".repeat(64)))
      }

    assertEquals(FeatureTaskRuntimeFailureCode.QA_RECEIPT_IDENTITY_MISMATCH, error.code)
    assertTrue(error.message.orEmpty().startsWith("QA receipt tree_identity "))
  }

  @Test
  fun `a simulator cannot exercise a hardware-only flow`() {
    val passed = qaTestReceipt(QaVerdict.PASSED)
    val receipt =
      passed +
        mapOf(
          "target" to passed.getValue("target").asReceiptObject() + mapOf("kind" to "simulator"),
          "flows" to listOf(qaTestFlow(hardwareOnly = true)),
        )

    assertRejected(FeatureTaskRuntimeFailureCode.QA_RECEIPT_UNSUPPORTED_CLAIM, receipt)
  }

  private fun assertRejected(
    code: FeatureTaskRuntimeFailureCode,
    receipt: Map<String, Any?>,
  ) {
    val error = assertFailsWith<SkillBillRuntimeException> { validator.validate(qaTestJson(receipt), expected) }
    assertEquals(code, error.code)
  }
}

private fun Any?.asReceiptObject(): Map<String, Any?> =
  (this as Map<*, *>).entries.associate { (key, value) -> key as String to value }
