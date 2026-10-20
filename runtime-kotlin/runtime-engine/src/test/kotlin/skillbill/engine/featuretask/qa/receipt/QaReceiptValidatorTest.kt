package skillbill.engine.featuretask.qa.receipt

import skillbill.engine.featuretask.model.execution.QaSkipReason
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
  fun `a self-contradicting receipt is downgraded to its honest verdict with a warning and keeps its evidence`() {
    val passed = qaTestReceipt(QaVerdict.PASSED)
    val findings = qaTestReceipt(QaVerdict.FINDINGS_OPEN)
    val untestedFlow = qaTestFlow() + mapOf("id" to "logout", "status" to "untested", "untested_reason" to "No time.")
    val cases =
      listOf(
        passed + ("flows" to listOf(qaTestFlow(), untestedFlow)) to QaVerdict.INCOMPLETE,
        passed + ("findings" to findings.getValue("findings")) to QaVerdict.FINDINGS_OPEN,
        findings + ("findings" to emptyList<Any?>()) to QaVerdict.INCOMPLETE,
        passed + ("assertions" to emptyList<Any?>()) to QaVerdict.INCOMPLETE,
      )

    cases.forEach { (wire, honest) ->
      val receipt = validator.validate(qaTestJson(wire), expected)

      assertEquals(honest, receipt.verdict)
      assertEquals(honest.wireValue, receipt.wire["verdict"])
      assertEquals(1, receipt.warnings.size, receipt.warnings.toString())
      assertEquals((wire.getValue("flows") as List<*>).size, receipt.flows.size)
      assertEquals((wire.getValue("findings") as List<*>).size, receipt.findings.size)
      assertEquals(receipt, validator.validate(qaTestJson(receipt.wire), expected).copy(warnings = receipt.warnings))
    }
  }

  @Test
  fun `an untested flow keeps passed when every admitted criterion passed on an exercised flow`() {
    val untestedFlow = qaTestFlow() + mapOf("id" to "extra", "status" to "untested", "untested_reason" to "No time.")
    val wire = qaTestReceipt(QaVerdict.PASSED) + ("flows" to listOf(qaTestFlow(), untestedFlow))

    val receipt = validator.validate(qaTestJson(wire), expected.copy(acceptanceCriteria = setOf("AC-1")))

    assertEquals(QaVerdict.PASSED, receipt.verdict)
    assertEquals(1, receipt.warnings.size, receipt.warnings.toString())
    assertTrue(receipt.warnings.single().endsWith("kept passed."))
  }

  @Test
  fun `an untested flow records incomplete with untested_flows when an admitted criterion has no passing assertion`() {
    val untestedFlow = qaTestFlow() + mapOf("id" to "logout", "status" to "untested", "untested_reason" to "No time.")
    val wire = qaTestReceipt(QaVerdict.PASSED) + ("flows" to listOf(qaTestFlow(), untestedFlow))

    listOf(emptySet(), setOf("AC-1", "AC-2")).forEach { criteria ->
      val receipt = validator.validate(qaTestJson(wire), expected.copy(acceptanceCriteria = criteria))

      assertEquals(QaVerdict.INCOMPLETE, receipt.verdict)
      assertEquals(QaIncompleteReason.UNTESTED_FLOWS, receipt.incompleteReason)
    }
  }

  @Test
  fun `an incomplete untested_flows claim records passed when every admitted criterion passed on an exercised flow`() {
    val untestedFlow = qaTestFlow() + mapOf("id" to "extra", "status" to "untested", "untested_reason" to "No fixture.")
    val wire =
      qaTestReceipt(QaVerdict.INCOMPLETE) +
        mapOf("incomplete_reason" to "untested_flows", "flows" to listOf(qaTestFlow(), untestedFlow))

    val receipt = validator.validate(qaTestJson(wire), expected.copy(acceptanceCriteria = setOf("AC-1")))

    assertEquals(QaVerdict.PASSED, receipt.verdict)
    assertEquals(null, receipt.incompleteReason)
    assertEquals(null, receipt.wire["incomplete_reason"])
    assertTrue(receipt.warnings.single().endsWith("recorded passed."))
  }

  @Test
  fun `an incomplete claim stays incomplete unless only untested flows kept every admitted criterion from passing`() {
    val untestedFlow = qaTestFlow() + mapOf("id" to "extra", "status" to "untested", "untested_reason" to "No fixture.")
    val untested = qaTestReceipt(QaVerdict.INCOMPLETE) + ("flows" to listOf(qaTestFlow(), untestedFlow))
    val cases =
      listOf(
        untested + ("incomplete_reason" to "untested_flows") to emptySet(),
        untested + ("incomplete_reason" to "untested_flows") to setOf("AC-1", "AC-2"),
        untested + ("incomplete_reason" to "device_lost") to setOf("AC-1"),
        untested + mapOf("incomplete_reason" to "untested_flows", "target" to null) to setOf("AC-1"),
      )

    cases.forEach { (wire, criteria) ->
      val receipt = validator.validate(qaTestJson(wire), expected.copy(acceptanceCriteria = criteria))

      assertEquals(QaVerdict.INCOMPLETE, receipt.verdict)
      assertEquals(wire["incomplete_reason"], receipt.incompleteReason?.wireValue)
    }
  }

  @Test
  fun `production_only is an accepted skip reason`() {
    val receipt =
      validator.validate(qaTestJson(qaTestReceipt(QaVerdict.SKIPPED) + ("skip_reason" to "production_only")), expected)

    assertEquals(QaSkipReason.PRODUCTION_ONLY, receipt.skipReason)
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
  fun `a simulator run that claims a hardware-only flow records it untested and cannot pass`() {
    val passed = qaTestReceipt(QaVerdict.PASSED)
    val wire =
      passed +
        mapOf(
          "target" to passed.getValue("target").asReceiptObject() + mapOf("kind" to "simulator"),
          "flows" to listOf(qaTestFlow(hardwareOnly = true)),
        )

    val receipt = validator.validate(qaTestJson(wire), expected)

    assertEquals(QaVerdict.INCOMPLETE, receipt.verdict)
    assertEquals(QaFlowStatus.UNTESTED, receipt.flows.single().status)
    assertEquals(2, receipt.warnings.size, receipt.warnings.toString())
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
