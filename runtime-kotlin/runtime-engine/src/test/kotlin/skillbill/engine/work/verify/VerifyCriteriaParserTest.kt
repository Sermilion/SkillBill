package skillbill.engine.work.verify

import skillbill.ports.idestatus.model.VerifyGradeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerifyCriteriaParserTest {
  @Test
  fun `saved table audits project criterion grades and evidence without rewriting receipts`() {
    val findings =
      listOf(
        """
        | # | Criterion | Status | Evidence / gap |
        | --- | --- | --- | --- |
        | 1 | Local edits survive | **PARTIAL** | SyncWorkerTest.kt:322 misses the second write. |
        | 2 | Echo reconciliation | PASS | SyncWorker.kt:290 |
        | 3 | Device race | FAIL | SyncScreen.kt:42 crashes during the write. |
        | 4 | Unrecognized result | UNKNOWN | Requires another audit. |
        """.trimIndent(),
      )
    val receipt = mapOf("verdict" to "all_pass", "findings" to findings)

    val audit = assertNotNull(VerifyCriteriaParser.completeness(receipt))

    assertEquals(listOf(1, 2, 3), audit.grades.map { it.number })
    assertEquals(
      listOf(VerifyGradeStatus.PARTIAL, VerifyGradeStatus.PASS, VerifyGradeStatus.FAIL),
      audit.grades.map { it.status },
    )
    assertEquals("Local edits survive", audit.grades[0].text)
    assertEquals(listOf("SyncWorkerTest.kt:322 misses the second write."), audit.grades[0].evidence)
    assertEquals("SyncWorkerTest.kt:322 misses the second write.", audit.grades[0].missing)
    assertEquals(listOf("SyncWorkerTest.kt:322"), audit.grades[0].locations)
    assertEquals("SyncScreen.kt:42 crashes during the write.", audit.grades[2].notFound)
    assertEquals(findings, audit.findings)
    assertEquals(findings, receipt["findings"])
  }

  @Test
  fun `table criterion cells with embedded numbers retain original criterion identities`() {
    val receipt =
      mapOf(
        "verdict" to "had_gaps",
        "findings" to
          listOf(
            "| Criterion | Status | Evidence |\n| --- | --- | --- |\n| #9 Device race | [PASS] | SyncTest.kt:19 |",
          ),
      )

    val grade = assertNotNull(VerifyCriteriaParser.completeness(receipt)).grades.single()

    assertEquals(9, grade.number)
    assertEquals("Device race", grade.text)
    assertEquals(VerifyGradeStatus.PASS, grade.status)
    assertEquals(listOf("SyncTest.kt:19"), grade.locations)
  }

  @Test
  fun `real criteria and completeness receipt parse into one grade per criterion with locations`() {
    val (criteria, raw) =
      VerifyCriteriaParser.criteria(VerifyReceiptFixtures.field("criteria_summary_0ac85", "acceptance_criteria"))
    val receipt = VerifyReceiptFixtures.artifact("completeness_audit_receipt_0ac85")
    val audit = assertNotNull(VerifyCriteriaParser.completeness(receipt))

    assertEquals((1..8).toList(), criteria.map { it.number })
    assertNull(raw)
    assertEquals("had_gaps", audit.verdict)
    assertEquals((1..8).toList(), audit.grades.map { it.number })
    val first = audit.grades.first()
    assertEquals(VerifyGradeStatus.PARTIAL, first.status)
    assertEquals("ContactsViewModel.kt:176", first.evidence.first())
    assertEquals("ContactsScreen.kt:475", first.evidence.last())
    assertEquals(listOf("ContactsScreen.kt:147"), first.locations)
    val third = audit.grades[2]
    assertEquals(VerifyGradeStatus.FAIL, third.status)
    assertTrue(third.notFound.orEmpty().startsWith("The diff contains no server-side mutual matching"))
    assertNull(third.missing)
    assertEquals(listOf("ZeroContactsRepository.kt:87"), third.locations)
  }

  @Test
  fun `criteria without numbered items keep the raw text`() {
    val text = VerifyReceiptFixtures.field("criteria_summary_fp5175", "acceptance_criteria")

    val (criteria, raw) = VerifyCriteriaParser.criteria(text)

    assertTrue(criteria.isEmpty())
    assertEquals(text.trim(), raw)
  }

  @Test
  fun `a prose-only completeness receipt keeps its raw lines and yields no grades`() {
    val findings = listOf("All criteria look implemented.", "See the diff for details.")

    val audit = assertNotNull(VerifyCriteriaParser.completeness(mapOf("verdict" to "all_pass", "findings" to findings)))

    assertTrue(audit.grades.isEmpty())
    assertEquals(findings, audit.findings)
  }
}
