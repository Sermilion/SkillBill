package skillbill.engine.featuretask.slot.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AcceptanceAuditProgressParsingTest {
  @Test
  fun `operator resume cannot reopen a previously satisfied criterion`() {
    val input =
      AcceptanceAuditProgressInput(
        criteria = CRITERIA,
        text = "AC-001: missing behavior",
        priorText = "AC-002: missing behavior",
        repaired = true,
        operatorReopened = true,
        nonShrinkingRounds = 0,
      )
    val result = assertIs<AcceptanceAuditProgressOutcome.Rejected>(AcceptanceAuditProgress.outcome(input))
    assertTrue(result.reason.contains("Previously satisfied criteria stay closed"))
  }

  @Test
  fun `operator resume cannot bypass the second missing baseline event`() {
    val input =
      AcceptanceAuditProgressInput(
        criteria = CRITERIA,
        text = "AC-001: missing",
        priorText = null,
        repaired = true,
        operatorReopened = true,
        nonShrinkingRounds = 0,
        missingBaselineRounds = 1,
      )
    assertEquals(AcceptanceAuditProgressOutcome.MissingBaselineLimitReached, AcceptanceAuditProgress.outcome(input))
  }

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
  fun `explanation lines under an open criterion do not block the count`() {
    val catalog = assertIs<AcceptanceAuditCatalog.Known>(AcceptanceAuditCatalog.create(CRITERIA))
    listOf(
      "- AC-001: gap\n- Unidentified missing behavior",
      "  - AC-001: gap\n  - Unidentified missing behavior",
      "- AC-001: gap\nAC2 has no remaining production gap.",
      "AC-001: export wiring is missing.\n\nPaths:\n- runtime-engine/src/main/kotlin/Export.kt: 6",
    ).forEach { report ->
      val parsed =
        assertIs<AcceptanceAuditRemainingCriteria.Known>(AcceptanceAuditRemainingCriteriaParser.parse(report, catalog))
      assertEquals(setOf("AC-001"), parsed.identities, report)
    }
  }

  @Test
  fun `prose without the completion line does not complete the audit`() {
    val catalog = assertIs<AcceptanceAuditCatalog.Known>(AcceptanceAuditCatalog.create(CRITERIA))
    listOf(
      "Both AC1 and AC2 are satisfied. Nothing remains to repair.",
      "There are no remaining production gaps.",
      "No production criteria remain except the export path",
    ).forEach { report ->
      assertIs<AcceptanceAuditRemainingCriteria.Unusable>(
        AcceptanceAuditRemainingCriteriaParser.parse(report, catalog),
        report,
      )
    }
  }

  @Test
  fun `the completion line completes the audit`() {
    val catalog = assertIs<AcceptanceAuditCatalog.Known>(AcceptanceAuditCatalog.create(CRITERIA))
    listOf(
      AcceptanceAuditRemainingCriteriaParser.COMPLETION_LINE,
      "```\nno production criteria remain\n```",
      "All acceptance criteria are met; no production criteria remain.",
      "No production criteria remain.\nAC-001 is satisfied.\n- AC-002: resolved",
    ).forEach { report ->
      assertIs<AcceptanceAuditRemainingCriteria.Complete>(
        AcceptanceAuditRemainingCriteriaParser.parse(report, catalog),
        report,
      )
      assertEquals(null, AcceptanceAuditProgress.rejectionReason(CRITERIA, report, "- AC-002: gap", true, false))
    }
  }

  @Test
  fun `SKILL-389 completed audit rationale advances past audit`() {
    val criteria = (1..8).map { "AC-$it. Required behavior $it" }
    val report = checkNotNull(javaClass.getResource("/featuretask/audit/skill-389-completed-audit.txt")).readText()

    assertTrue(AcceptanceAuditProgress.declaresComplete(criteria, report))
    assertEquals(null, AcceptanceAuditProgress.rejectionReason(criteria, report, "- AC-002: gap", true, false))
  }

  @Test
  fun `SKILL-393 completed audit with bulleted rationale advances past audit`() {
    val criteria = (1..6).map { "AC-00$it. Required behavior $it" }
    val report = checkNotNull(javaClass.getResource("/featuretask/audit/skill-393-completed-audit.txt")).readText()

    assertTrue(AcceptanceAuditProgress.declaresComplete(criteria, report))
    assertEquals(null, AcceptanceAuditProgress.rejectionReason(criteria, report, null, false, false))
  }

  @Test
  fun `satisfied notes are skipped and completion beside open criteria is rejected`() {
    val catalog = assertIs<AcceptanceAuditCatalog.Known>(AcceptanceAuditCatalog.create(CRITERIA))
    val report = "AC-001 is satisfied. Its implementation meets the requirement.\nAC-002: export wiring is missing."

    val parsed =
      assertIs<AcceptanceAuditRemainingCriteria.Known>(AcceptanceAuditRemainingCriteriaParser.parse(report, catalog))
    assertEquals(setOf("AC-002"), parsed.identities)
    listOf(
      "No production criteria remain.\n- AC-002: export wiring is missing.",
      "AC-002: export wiring is missing.\nAll acceptance criteria are met; no production criteria remain.",
    ).forEach {
      assertIs<AcceptanceAuditRemainingCriteria.Unusable>(AcceptanceAuditRemainingCriteriaParser.parse(it, catalog), it)
    }
  }

  @Test
  fun `prose naming the same or a larger set after repair blocks as stalled`() {
    val before = "- AC1: first production behavior is missing.\n- AC2: second production behavior is missing."
    listOf(
      "- AC1: the gap remains.\n- AC2: the gap remains.",
      "- AC1: open\n- AC2: open\n- AC1: open again without an admission path.",
    ).forEach { after ->
      val reason = AcceptanceAuditProgress.rejectionReason(CRITERIA, after, before, true, false)
      assertTrue(reason.orEmpty().contains("did not shrink"), after)
    }
    val shrunk = "AC2: still open."
    assertEquals(null, AcceptanceAuditProgress.rejectionReason(CRITERIA, shrunk, before, true, false))
  }

  @Test
  fun `vague prose without identities or a clear completion statement is ambiguous`() {
    val catalog = assertIs<AcceptanceAuditCatalog.Known>(AcceptanceAuditCatalog.create(CRITERIA))
    listOf(
      "The implementation looks mostly fine.",
      "Some work is still missing in the export path.",
      "All criteria are met, but the export path is not wired.",
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
