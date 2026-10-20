package skillbill.engine.featuretask.qa.receipt

import skillbill.contracts.qa.QaReceiptPayloadKeys
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.model.execution.QaSkipReason
import skillbill.engine.featuretask.phaserun.qa.InMemoryQaWorkspaces
import skillbill.infrastructure.contracts.qa.QaReceiptSchemaValidator
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.qa.QaWorkspacePort
import skillbill.ports.qa.model.QaReceiptSource
import skillbill.workflow.taskruntime.artifact.asQaRepairStateWireMap
import skillbill.workflow.taskruntime.artifact.toQaRepairState
import skillbill.workflow.taskruntime.model.qa.QaOpenFinding
import skillbill.workflow.taskruntime.model.qa.QaRepairAttempt
import skillbill.workflow.taskruntime.model.qa.QaRepairAttemptOutcome
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QaFindingSettlementTest {
  private val validator = QaReceiptValidator(QaReceiptSchemaValidator())
  private val workspaces = InMemoryQaWorkspaces()
  private val warnings = mutableListOf<String>()
  private val diagnostics =
    object : RuntimeDiagnostics {
      override fun warning(
        message: String,
        error: Throwable?,
      ) {
        warnings += message
      }

      override fun error(
        message: String,
        error: Throwable?,
      ) = Unit
    }
  private val settlement = QaReceiptSettlement(validator, workspaces, diagnostics)
  private val original = workspaces.open(Path.of("/tmp/qa-findings"), "original")
  private val current = workspaces.open(Path.of("/tmp/qa-findings"), "current")
  private val qa =
    QaExecutionContext(
      current,
      qaTestExpected("current").copy(treeIdentity = "4".repeat(64)),
      QaExecutionTarget.Skipped(QaSkipReason.UNSUPPORTED_TARGET, "unused target guidance"),
    )
  private val previous =
    QaRepairState(
      subtaskId = 1,
      attempts = listOf(QaRepairAttempt(1, QA_TEST_TREE, listOf("F-1"), "repair-1", QaRepairAttemptOutcome.COMPLETED)),
      openFindings = listOf(QaOpenFinding("F-1", listOf("AC-1"), listOf(original.receiptPath.toString()))),
      lastReceiptTreeIdentity = QA_TEST_TREE,
    )

  @Test
  fun `a passing unrelated flow or criterion cannot close a reproduced finding`() {
    workspaces.receipts[original.receiptPath] =
      qaTestJson(qaTestReceipt(QaVerdict.FINDINGS_OPEN, qaTestExpected("original")))
    val passed = qaTestReceipt(QaVerdict.PASSED, qa.expected)
    val unrelatedFlow = qaTestJson(passed).replace("login", "settings")
    val unrelatedCriterion = qaTestJson(passed).replace("AC-1", "AC-2")
    for (raw in listOf(unrelatedFlow, unrelatedCriterion)) {
      val result = settlement.observe(previous, validator.validate(raw, qa.expected), qa)
      assertEquals(previous.openFindings, result.openFindings)
      assertEquals(previous.attempts, result.attempts)
    }

    val verified = settlement.observe(previous, validator.validate(qaTestJson(passed), qa.expected), qa)

    assertTrue(verified.openFindings.isEmpty())
    assertEquals(qa.expected.treeIdentity, verified.lastReceiptTreeIdentity)
    assertEquals(previous.attempts, verified.attempts)
  }

  @Test
  fun `historical evidence uses the workspace adapter prefix instead of rebuilding it from the path`() {
    val identity = qaTestExpected("original").copy(evidencePrefix = qaTestEvidencePrefix("retained-evidence"))
    val raw = qaTestJson(qaTestReceipt(QaVerdict.FINDINGS_OPEN, identity))
    val storage =
      object : QaWorkspacePort by workspaces {
        override fun readRetainedReceipt(
          qaRoot: Path,
          receiptReference: String,
        ): QaReceiptSource? =
          if (receiptReference == original.receiptPath.toString()) {
            QaReceiptSource(raw, identity.evidencePrefix, receiptReference)
          } else {
            workspaces.readRetainedReceipt(qaRoot, receiptReference)
          }
      }
    val passed = validator.validate(qaTestJson(qaTestReceipt(QaVerdict.PASSED, qa.expected)), qa.expected)

    val result = QaReceiptSettlement(validator, storage, diagnostics).observe(previous, passed, qa)

    assertTrue(result.openFindings.isEmpty())
  }

  @Test
  fun `passing QA on the finding tree cannot close it after a repair claim`() {
    workspaces.receipts[original.receiptPath] =
      qaTestJson(qaTestReceipt(QaVerdict.FINDINGS_OPEN, qaTestExpected("original")))
    val unchanged = qa.copy(expected = qa.expected.copy(treeIdentity = QA_TEST_TREE))
    val passed = validator.validate(qaTestJson(qaTestReceipt(QaVerdict.PASSED, unchanged.expected)), unchanged.expected)

    val result = settlement.observe(previous, passed, unchanged)

    assertEquals(previous.openFindings, result.openFindings)
    assertEquals(previous.attempts, result.attempts)
  }

  @Test
  fun `repeated finding observations survive persistence and every reproduced flow needs passing QA`() {
    workspaces.receipts[original.receiptPath] =
      qaTestJson(qaTestReceipt(QaVerdict.FINDINGS_OPEN, qaTestExpected("original")))
    val repeatedWorkspace = workspaces.open(Path.of("/tmp/qa-findings"), "repeated")
    val repeatedIdentity = qaTestExpected("repeated").copy(treeIdentity = "4".repeat(64))
    val repeatedRaw =
      qaTestJson(qaTestReceipt(QaVerdict.FINDINGS_OPEN, repeatedIdentity)).replace("login", "settings")
    workspaces.receipts[repeatedWorkspace.receiptPath] = repeatedRaw
    val observed =
      settlement.observe(
        previous,
        validator.validate(repeatedRaw, repeatedIdentity),
        qa.copy(workspace = repeatedWorkspace, expected = repeatedIdentity),
      )
    val resumed = observed.asQaRepairStateWireMap().toQaRepairState()
    assertEquals(
      listOf(original.receiptPath.toString(), repeatedWorkspace.receiptPath.toString()).sorted(),
      resumed.openFindings.single().receiptRefs,
    )
    val verificationWorkspace = workspaces.open(Path.of("/tmp/qa-findings"), "verification")
    val verificationIdentity = qaTestExpected("verification").copy(treeIdentity = "6".repeat(64))
    val verificationQa = qa.copy(workspace = verificationWorkspace, expected = verificationIdentity)
    val passed = qaTestReceipt(QaVerdict.PASSED, verificationIdentity)
    val onlyOriginalFlow = validator.validate(qaTestJson(passed), verificationIdentity)
    assertEquals(resumed.openFindings, settlement.observe(resumed, onlyOriginalFlow, verificationQa).openFindings)
    val bothFlows =
      passed +
        mapOf(
          QaReceiptPayloadKeys.FLOWS to listOf(qaTestFlow(), qaTestFlow() + (QaReceiptPayloadKeys.ID to "settings")),
          QaReceiptPayloadKeys.ASSERTIONS to
            (passed.getValue(QaReceiptPayloadKeys.ASSERTIONS) as List<*>) +
            (qaTestReceipt(QaVerdict.PASSED)[QaReceiptPayloadKeys.ASSERTIONS] as List<*>).map {
              (it as Map<*, *>).entries.associate { entry -> entry.key.toString() to entry.value } +
                (QaReceiptPayloadKeys.FLOW_ID to "settings")
            },
        )

    val verified =
      settlement.observe(resumed, validator.validate(qaTestJson(bothFlows), verificationIdentity), verificationQa)

    assertTrue(verified.openFindings.isEmpty())
    assertEquals(previous.attempts, verified.attempts)
  }

  @Test
  fun `passing exercised assertions can close criteria findings without an original exercised flow`() {
    val originalReceipt =
      qaTestReceipt(QaVerdict.FINDINGS_OPEN, qaTestExpected("original")) +
        mapOf(
          QaReceiptPayloadKeys.FLOWS to emptyList<Any?>(),
          QaReceiptPayloadKeys.ASSERTIONS to emptyList<Any?>(),
        )
    workspaces.receipts[original.receiptPath] = qaTestJson(originalReceipt)
    val passed = validator.validate(qaTestJson(qaTestReceipt(QaVerdict.PASSED, qa.expected)), qa.expected)

    val result = settlement.observe(previous, passed, qa)

    assertTrue(result.openFindings.isEmpty())
  }

  @Test
  fun `skip and incomplete receipts keep unresolved findings even when they include passing observations`() {
    for (verdict in listOf(QaVerdict.SKIPPED, QaVerdict.INCOMPLETE)) {
      val receipt = validator.validate(qaTestJson(qaTestReceipt(verdict, qa.expected)), qa.expected)
      val result = settlement.observe(previous, receipt, qa)
      assertEquals(previous.openFindings, result.openFindings)
      assertEquals(previous.attempts, result.attempts)
    }
  }

  @Test
  fun `missing historical evidence retains findings and records the deferred closure without blocking`() {
    val receipt = validator.validate(qaTestJson(qaTestReceipt(QaVerdict.PASSED, qa.expected)), qa.expected)

    val result = settlement.observe(previous, receipt, qa)

    assertEquals(previous.openFindings, result.openFindings)
    assertTrue(warnings.single().contains("receipt_unavailable"))
  }

  @Test
  fun `a malformed historical receipt keeps its finding open and records the deferred closure`() {
    workspaces.receipts[original.receiptPath] = "{}"
    val receipt = validator.validate(qaTestJson(qaTestReceipt(QaVerdict.PASSED, qa.expected)), qa.expected)

    val result = settlement.observe(previous, receipt, qa)

    assertEquals(previous.openFindings, result.openFindings)
    assertTrue(warnings.single().contains("qa closure deferred"), warnings.toString())
  }
}
