package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.VerifyCompletenessAudit
import dev.skillbill.desktop.core.domain.model.VerifyCriterionGrade
import dev.skillbill.desktop.core.domain.model.VerifyReviewSection
import dev.skillbill.desktop.core.domain.model.VerifyRunCriterion
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails
import dev.skillbill.desktop.core.domain.model.VerifyTestValueRow
import dev.skillbill.desktop.core.domain.model.VerifyUnitTestValue
import dev.skillbill.desktop.core.domain.settings.CriterionMark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerifyFixSelectionTest {
  @Test
  fun `selection uses defaults and excludes keep while shared locations stay grouped`() {
    val shared = "VerifyStepSequence.kt:390"
    val run = VerifyRunDetails(
      workflowId = "verify-1", issueKey = null, workflowStatus = "completed", currentStepId = null,
      startedAt = null, finishedAt = null, intake = "SKILL-1", target = "feature/verify", repoRoot = "/repo",
      baseRevision = "base", headRevision = "head", reviewMode = null, qaSelection = null, specPath = null,
      steps = emptyList(), criteria = listOf(VerifyRunCriterion(1, "Partial"), VerifyRunCriterion(2, "Fail")), criteriaRaw = null,
      nonGoals = emptyList(), technicalConstraints = emptyList(), rolloutExpectation = null,
      completenessAudit = VerifyCompletenessAudit(null, emptyList(), listOf(
        VerifyCriterionGrade(1, "PARTIAL", "Partial", emptyList(), "missing", null, listOf(shared)),
        VerifyCriterionGrade(2, "FAIL", "Fail", listOf("evidence"), null, "not found", listOf(shared)),
      )),
      reviewFindings = VerifyReviewSection(null, emptyList(), emptyList()), flagAudit = null,
      unitTestValue = VerifyUnitTestValue(null, emptyList(), null, null, null, listOf(
        VerifyTestValueRow("Keep area", "weak", "high", emptyList(), "why", "Keep.", listOf("Keep.kt:1")),
        VerifyTestValueRow("Rewrite area", "weak", "high", emptyList(), "why", "Rewrite", listOf(shared)),
        VerifyTestValueRow("Rewrite area", "weak", "high", emptyList(), "why", "Rewrite", listOf(shared)),
      ), emptyList(), emptyList(), emptyList(), emptyList()), changedFiles = emptyList(), verdict = null, qa = null,
      qaVerdict = null, qaOutcome = null, qaFindingLocations = emptyMap(), supersededBy = null, staleReason = null,
    )
    val items = verifyFixItems(run, emptyMap())
    assertEquals(listOf("c:1", "c:2", "t:1", "t:2"), items.map { it.key })
    assertEquals(FixRoute.Chat, items[0].defaultRoute)
    assertEquals(FixRoute.Goal, items[1].defaultRoute)
    assertEquals(SelectAllState.Off, selectAllState(items, emptyMap()))
    val selected = toggleSelectAll(items.take(2), emptyMap())
    assertEquals(SelectAllState.On, selectAllState(items.take(2), selected))
    assertEquals("Same code as #2, Rewrite area · $shared", sharedHint(items[0], items))
    assertEquals("2 of them touch $shared, so one agent gets them together", sharedLocationNote(items.take(2)))
    val brief = verifyChatBrief(run, items.take(2))
    assertTrue(brief.contains("Branch: feature/verify"))
    assertTrue(brief.contains("Do not resume, continue or modify Verify run verify-1."))
    assertTrue(brief.indexOf("Partial") < brief.indexOf("Fail"))
    assertTrue(verifyGoalIntake(run, items.take(2)).endsWith("Build the fixes on branch feature/verify."))
    assertNull(selectAllState(items.filterIsInstance<FixItem.Criterion>().map { it.copy(mark = CriterionMark.SpecQuestion) }, emptyMap()))
  }
}
