package skillbill.engine.work.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerifyReceiptParsersTest {
  @Test
  fun `real review register lines parse with severity, specialist prefix removed and one location`() {
    val section = assertNotNull(VerifyReviewParser.parse(VerifyReceiptFixtures.artifact("code_review_receipt_0ac85")))

    assertEquals((1..7).map { "F-00$it" }, section.items.map { it.ref })
    val first = section.items.first()
    assertEquals("Major", first.severity)
    assertEquals("High", first.confidence)
    assertEquals(listOf("ZeroContactsRepository.kt:87"), first.locations)
    assertTrue(first.specialists.isEmpty())
    val last = section.items.last()
    assertEquals("Minor", last.severity)
    assertTrue(last.description.startsWith("Related setup and content values"), last.description)
    assertEquals(listOf("bill-kmp-code-review-code-quality"), last.specialists)
    assertEquals(listOf("project.pbxproj:264"), section.items[5].locations)
  }

  @Test
  fun `a flag receipt with leading narration parses from the audit heading onward`() {
    val audit =
      assertNotNull(VerifyFlagAuditParser.parse(VerifyReceiptFixtures.artifact("feature_flag_audit_receipt_0ac86")))

    assertEquals("KmpKycEnabled", audit.flagName)
    assertEquals("Simple Conditional", audit.pattern)
    assertEquals("None", audit.issues)
    assertTrue(audit.checks.isNotEmpty())
    assertTrue(audit.checks.all { it.passed })
    assertEquals("Flag defined in codebase", audit.checks.first().text)
    assertTrue(audit.checks.none { "I'll audit" in it.text })
  }

  @Test
  fun `narration before the flag audit heading never becomes a label or check`() {
    val findings =
      listOf(
        "Flag name: GuessedFlag",
        "[ PASS ] guessed check I'll audit next.FEATURE FLAG AUDIT",
        "Flag name: RealFlag",
        "[ FAIL ] Default value is false",
      )

    val audit = assertNotNull(VerifyFlagAuditParser.parse(mapOf("verdict" to "fail", "findings" to findings)))

    assertEquals("RealFlag", audit.flagName)
    assertEquals(listOf(false), audit.checks.map { it.passed })
    assertEquals("Default value is false", audit.checks.single().text)
  }

  @Test
  fun `a skipped flag receipt carries its reason and no structured fields`() {
    val audit =
      assertNotNull(
        VerifyFlagAuditParser.parse(VerifyReceiptFixtures.artifact("feature_flag_audit_receipt_0ac85_skipped")),
      )

    assertTrue(audit.skipReason.orEmpty().startsWith("The criteria do not require a feature-flagged rollout"))
    assertNull(audit.flagName)
    assertTrue(audit.checks.isEmpty())
  }

  @Test
  fun `the real unit test value receipt parses its table and recommendation lists`() {
    val value =
      assertNotNull(VerifyUnitTestValueParser.parse(VerifyReceiptFixtures.artifact("unit_test_value_receipt_0ac85")))

    assertEquals("Mixed", value.overall)
    assertTrue(value.summary.orEmpty().startsWith("The suite has useful coverage"), value.summary)
    assertEquals(8, value.rows.size)
    val menu = value.rows.first()
    assertEquals("Menu item lists", menu.area)
    assertEquals("Weak", menu.verdict)
    assertEquals(listOf("OverviewMenuItemTest.kt:15", "line 26"), menu.files)
    assertEquals("Delete both.", menu.action)
    assertTrue("OverviewMenuItemTest.kt:15" in menu.locations)
    assertEquals(1, value.keep.size)
    assertEquals(1, value.rewrite.size)
    assertEquals(1, value.delete.size)
    assertEquals(1, value.missingCases.size)
  }
}
