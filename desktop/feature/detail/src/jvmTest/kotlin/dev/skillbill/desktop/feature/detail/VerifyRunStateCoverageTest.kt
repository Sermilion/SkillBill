package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.VerifyRunDetails
import kotlin.test.Test
import kotlin.test.assertEquals

class VerifyRunStateCoverageTest {
  @Test
  fun `fixture verify cases cover every desktop verify state`() {
    val cases =
      listOf(
        VerifyCase("abandoned", "code_review", "fixture-verify-running"),
        VerifyCase("running", "code_review"),
        VerifyCase("pending", "extract_criteria"),
        VerifyCase("pending", "gather_diff"),
        VerifyCase("failed", "code_review"),
        VerifyCase("completed", "finish"),
      )

    // Keep this mirror in sync with skillbill.application.DesktopFixtureCatalog.verifies.
    val observed = cases.map { case -> verifyRunState(case.toDetails()) }.toSet()
    assertEquals(VerifyRunState.entries.toSet(), observed)
  }

  private fun VerifyCase.toDetails() =
    VerifyRunDetails(
      workflowId = "fixture-verify",
      issueKey = null,
      workflowStatus = status,
      currentStepId = step,
      startedAt = null,
      finishedAt = null,
      intake = null,
      target = null,
      repoRoot = null,
      baseRevision = null,
      headRevision = null,
      reviewMode = null,
      qaSelection = null,
      specPath = null,
      steps = emptyList(),
      criteria = emptyList(),
      criteriaRaw = null,
      nonGoals = emptyList(),
      technicalConstraints = emptyList(),
      rolloutExpectation = null,
      completenessAudit = null,
      reviewFindings = null,
      flagAudit = null,
      unitTestValue = null,
      changedFiles = emptyList(),
      verdict = null,
      qa = null,
      qaVerdict = null,
      qaOutcome = null,
      qaFindingLocations = emptyMap(),
      supersededBy = supersededBy,
      staleReason = null,
    )

  private data class VerifyCase(
    val status: String,
    val step: String,
    val supersededBy: String? = null,
  )
}
