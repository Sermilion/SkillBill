package skillbill.engine.work.verify

import skillbill.ports.idestatus.model.VerifyGradeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerifyCriteriaParserTest {
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
