package skillbill.engine.featuretask.qa.report

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.ports.featuretask.model.FeatureTaskQaFinding
import skillbill.ports.featuretask.model.FeatureTaskQaFindingStatus
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.qa.QaRepairAttempt
import skillbill.workflow.taskruntime.model.qa.QaRepairAttemptOutcome
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FeatureTaskQaOutcomeRecordsTest {
  private val expected = qaTestExpected()
  private val recordedAt = Instant.parse("2026-10-22T07:11:19Z")

  @Test
  fun `a findings report settles its prose findings as not fixed with the report text`() {
    val request =
      settledRequest(
        mapOf(
          QaPhaseOutputKeys.VERDICT to "findings_open",
          QaPhaseOutputKeys.REPORT_TEXT to qaTestReport(QaVerdict.FINDINGS_OPEN, expected),
          QaPhaseOutputKeys.REPORT_REF to "/qa/report.md",
          QaPhaseOutputKeys.EVIDENCE_PREFIX to expected.evidencePrefix,
          QaPhaseOutputKeys.TREE_IDENTITY to QA_TEST_TREE,
        ),
      )

    val outcome = assertNotNull(FeatureTaskQaOutcomeRecords.settled(request, recordedAt, null))

    assertEquals(StandaloneQaOutcome.FINDINGS, outcome.outcome)
    assertEquals(QA_TEST_TREE, outcome.testedTreeIdentity)
    assertEquals(qaTestReport(QaVerdict.FINDINGS_OPEN, expected), outcome.reportText)
    assertEquals(
      listOf(
        FeatureTaskQaFinding(
          "F-1",
          "Home screen stays blank",
          listOf("${expected.evidencePrefix}home-F-1.png"),
          "/qa/report.md",
          FeatureTaskQaFindingStatus.NOT_FIXED,
        ),
      ),
      outcome.findings,
    )
  }

  @Test
  fun `a passed report marks every previously reported finding fixed and lists no new one`() {
    val previous = outcome(listOf(finding("F-1", FeatureTaskQaFindingStatus.NOT_FIXED)))
    val request =
      settledRequest(
        mapOf(
          QaPhaseOutputKeys.VERDICT to "passed",
          QaPhaseOutputKeys.REPORT_TEXT to qaTestReport(QaVerdict.PASSED, expected, listOf("F-9")),
          QaPhaseOutputKeys.EVIDENCE_PREFIX to expected.evidencePrefix,
        ),
      )

    val settled = assertNotNull(FeatureTaskQaOutcomeRecords.settled(request, recordedAt, previous))

    assertEquals(StandaloneQaOutcome.PASSED, settled.outcome)
    assertEquals(listOf("F-1"), settled.findings.map { it.id })
    assertEquals(listOf(FeatureTaskQaFindingStatus.FIXED), settled.findings.map { it.status })
  }

  @Test
  fun `an incomplete report leaves earlier findings not fixed`() {
    val previous = outcome(listOf(finding("F-1", FeatureTaskQaFindingStatus.NOT_FIXED)))

    val merged = FeatureTaskQaOutcomeRecords.mergedFindings(previous.findings, retestsFindings = false, emptyList())

    assertEquals(listOf(FeatureTaskQaFindingStatus.NOT_FIXED), merged.map { it.status })
  }

  @Test
  fun `a conclusive findings report fixes the earlier finding it dropped and appends the new one`() {
    val previous = outcome(listOf(finding("F-1", FeatureTaskQaFindingStatus.NOT_FIXED)))
    val reported = listOf(finding("F-2", FeatureTaskQaFindingStatus.NOT_FIXED))

    val merged = FeatureTaskQaOutcomeRecords.mergedFindings(previous.findings, retestsFindings = true, reported)

    assertEquals(
      listOf("F-1" to FeatureTaskQaFindingStatus.FIXED, "F-2" to FeatureTaskQaFindingStatus.NOT_FIXED),
      merged.map { it.id to it.status },
    )
  }

  @Test
  fun `a legacy qa receipt envelope still settles its findings and verdict`() {
    val receipt =
      mapOf(
        "verdict" to "findings_open",
        "findings" to
          listOf(
            mapOf(
              "id" to "F-9",
              "observed" to "Home blank\nsecond line",
              "evidence_refs" to listOf(mapOf("path" to "/qa/old/home.png")),
            ),
          ),
      )
    val request =
      settledRequest(
        mapOf(
          QaPhaseOutputKeys.LEGACY_RECEIPT to receipt,
          QaPhaseOutputKeys.LEGACY_RECEIPT_REF to "/qa/old/receipt.json",
        ),
      )

    val settled = assertNotNull(FeatureTaskQaOutcomeRecords.settled(request, recordedAt, null))

    assertEquals(StandaloneQaOutcome.FINDINGS, settled.outcome)
    assertNull(settled.reportText)
    assertEquals(
      listOf(
        FeatureTaskQaFinding(
          "F-9",
          "Home blank",
          listOf("/qa/old/home.png"),
          "/qa/old/receipt.json",
          FeatureTaskQaFindingStatus.NOT_FIXED,
        ),
      ),
      settled.findings,
    )
  }

  @Test
  fun `a phase output without QA keys records nothing`() {
    val request = settledRequest(mapOf("unrelated" to "value"))

    assertNull(FeatureTaskQaOutcomeRecords.settled(request, recordedAt, null))
  }

  @Test
  fun `reportFrom reparses the stored text against the stored evidence prefix`() {
    val produced =
      mapOf(
        QaPhaseOutputKeys.REPORT_TEXT to qaTestReport(QaVerdict.FINDINGS_OPEN, expected),
        QaPhaseOutputKeys.EVIDENCE_PREFIX to expected.evidencePrefix,
      )

    val report = assertNotNull(FeatureTaskQaOutcomeRecords.reportFrom(produced))

    assertEquals(listOf("${expected.evidencePrefix}home-F-1.png"), report.findings.single().evidencePaths)
    assertNull(FeatureTaskQaOutcomeRecords.reportFrom(emptyMap()))
  }

  @Test
  fun `an incomplete reason carries the runtime reason and warnings`() {
    val produced =
      mapOf(
        QaPhaseOutputKeys.REASON to "QA agent wrote no report.md",
        QaPhaseOutputKeys.WARNINGS to listOf("QA agent wrote no report.md", "device offline"),
      )

    assertEquals(
      "QA agent wrote no report.md; device offline",
      qaOutcomeReason(QaVerdict.INCOMPLETE, produced),
    )
  }

  @Test
  fun `a legacy incomplete reason includes the receipt detail after the enum token`() {
    val produced =
      mapOf(
        QaPhaseOutputKeys.LEGACY_RECEIPT to
          mapOf(
            "verdict" to "incomplete",
            "incomplete_reason" to "device_lost",
            "reason_detail" to "The login flow crashed on launch.",
          ),
      )

    assertEquals("device_lost · The login flow crashed on launch.", qaOutcomeReason(QaVerdict.INCOMPLETE, produced))
  }

  private fun settledRequest(produced: Map<String, Any?>): FeatureTaskRuntimePhaseStateRequest {
    val envelope =
      mapOf(
        SharedPayloadKeys.PHASE_ID to "qa",
        SharedPayloadKeys.STATUS to "completed",
        SharedPayloadKeys.SUMMARY to "QA settled",
        SharedPayloadKeys.PRODUCED_OUTPUTS to produced,
      )
    val output =
      NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(JsonCodec.mapToJsonString(envelope), "test")
        .copy(qaRepairState = repairState())
    return FeatureTaskRuntimePhaseStateRequest(
      workflowId = WORKFLOW_ID,
      phaseId = "qa",
      status = "completed",
      attemptCount = 1,
      resolvedAgentId = "claude",
      finished = true,
      normalizedOutput = output,
    )
  }

  private fun repairState(): QaRepairState =
    QaRepairState(
      subtaskId = 1,
      attempts = listOf(QaRepairAttempt(1, QA_TEST_TREE, listOf("F-1"), "repair-1", QaRepairAttemptOutcome.COMPLETED)),
    )

  private fun outcome(findings: List<FeatureTaskQaFinding>): FeatureTaskQaOutcome =
    FeatureTaskQaOutcome(
      WORKFLOW_ID,
      StandaloneQaOutcome.FINDINGS,
      null,
      1,
      QA_TEST_TREE,
      "$recordedAt",
      findings,
    )

  private fun finding(
    id: String,
    status: FeatureTaskQaFindingStatus,
  ): FeatureTaskQaFinding = FeatureTaskQaFinding(id, "Title $id", emptyList(), "/qa/report.md", status)

  private companion object {
    const val WORKFLOW_ID = "wftr-qa-records"
  }
}
