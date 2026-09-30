package skillbill.engine.featuretask.slot.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AcceptanceAuditProgressParsingTest {
  @Test
  fun `aliases deduplicate and explanation references do not invent open criteria`() {
    val catalog = assertIs<AcceptanceAuditCatalog.Known>(AcceptanceAuditCatalog.create(CRITERIA))
    val reports =
      listOf(
        "- AC-001 / S3-AC2: missing behavior dependent on AC-002.\n- s3-ac02: another gap under the same criterion",
        """[{"criterion":"AC-001 / S3-AC2","missing_production_behavior":"AC-002 is satisfied"}]""",
        "1. S3-AC2: behavior missing because AC-002 has already been satisfied",
      )
    reports.forEach { report ->
      val result =
        assertIs<AcceptanceAuditRemainingCriteria.Known>(AcceptanceAuditRemainingCriteriaParser.parse(report, catalog))
      assertEquals(setOf("AC-001"), result.identities)
    }
  }

  @Test
  fun `JSON finding counts equivalent ID and original criterion text once`() {
    val catalog =
      assertIs<AcceptanceAuditCatalog.Known>(
        AcceptanceAuditCatalog.create((1..8).map { "S3-AC$it. Required behavior $it" }),
      )
    val report =
      """[
      |  {
      |    "criterion_id": "AC-006",
      |    "criterion": "S3-AC6. A capability-boundary guard checks reachable types and operations.",
      |    "acceptance_criterion_ref": "s3-ac06",
      |    "missing_production_behavior": "Guard omits fully qualified helper calls dependent on AC-003."
      |  }
      |]
      """.trimMargin()

    val parsed =
      assertIs<AcceptanceAuditRemainingCriteria.Known>(
        AcceptanceAuditRemainingCriteriaParser.parse(report, catalog),
      )

    assertEquals(setOf("AC-006"), parsed.identities)
  }

  @Test
  fun `stored audit five counts three source labels and excludes satisfied summary`() {
    val catalog =
      assertIs<AcceptanceAuditCatalog.Known>(
        AcceptanceAuditCatalog.create(
          (1..8).map { "S3-AC$it. Required behavior $it" },
        ),
      )
    val report =
      """Remaining production acceptance criteria:
      |- S3-AC2. Transition ownership is incomplete.
      |- S3-AC3. Capabilities are broad.
      |- S3-AC4. Review access is broad.
      |
      |AC1, AC5, and AC8 have no remaining production gap: the production behavior is present. AC6 and AC7 are test-only and omitted.
      """.trimMargin()
    val parsed =
      assertIs<AcceptanceAuditRemainingCriteria.Known>(
        AcceptanceAuditRemainingCriteriaParser.parse(report, catalog),
      )
    assertEquals(setOf("AC-002", "AC-003", "AC-004"), parsed.identities)
  }

  @Test
  fun `bad catalogs and ambiguous reports cannot supply progress counts`() {
    listOf(listOf("AC-001. First", "AC-1. Duplicate"), listOf("S3-AC2. First", "S3-AC2. Conflicting alias"))
      .forEach { assertIs<AcceptanceAuditCatalog.Unusable>(AcceptanceAuditCatalog.create(it)) }
    val catalog = assertIs<AcceptanceAuditCatalog.Known>(AcceptanceAuditCatalog.create(CRITERIA))
    listOf(
      "- AC-001 / S3-AC3: conflicting aliases",
      "- AC-001: resolved",
      "- AC-001: gap\n- Unidentified missing behavior",
      "  - AC-001: gap\n  - Unidentified missing behavior",
      "- AC-001: gap\nAC1 has no remaining production gap.",
      "- AC-099: unknown criterion",
      """[{"criterion_id":"AC-001","criterion":"S3-AC3. Another criterion"}]""",
      """[{"criterion_id":"AC-001","criterion":"AC-099. Unknown criterion"}]""",
      """[{"criterion_id":"AC-001","criterion":"Unidentified criterion"}]""",
      """[{"criterion_id":"AC-001","criterion":"S3-AC2: resolved"}]""",
      """[{"criterion_id":"AC-001","criterion":null}]""",
      """[{"missing_production_behavior":"AC-001"}]""",
      """[{"criterion":"AC-001""",
      "A completely reworded uncountable audit report.",
    ).forEach { report ->
      assertIs<AcceptanceAuditRemainingCriteria.Unusable>(
        AcceptanceAuditRemainingCriteriaParser.parse(report, catalog),
        report,
      )
    }
  }

  @Test
  fun `completion marker must occupy its own final content line`() {
    listOf(
      "Deliberately not claiming audit_repair_complete: true",
      "audit_repair_complete: true\nMore gaps remain.",
      "audit_repair_complete: true.",
    ).forEach { assertEquals(false, AuditImplementFixPromptSections.endsWithCompletionMarker(it)) }
    listOf("audit_repair_complete: true", "Evidence.\naudit_repair_complete: true\n```\n ")
      .forEach { assertEquals(true, AuditImplementFixPromptSections.endsWithCompletionMarker(it)) }
  }

  private companion object {
    val CRITERIA = listOf("S3-AC2. First behavior", "S3-AC3. Second behavior")
  }
}
