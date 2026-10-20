package dev.skillbill.tray.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.skillbill.tray.core.designsystem.DesignLabel
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.designsystem.border
import dev.skillbill.tray.core.designsystem.canvas
import dev.skillbill.tray.core.designsystem.panel
import dev.skillbill.tray.core.domain.model.GoalQaFinding
import dev.skillbill.tray.core.domain.model.GoalQaResult
import dev.skillbill.tray.core.domain.model.PhaseConclusion
import dev.skillbill.tray.core.domain.model.QaCheckResult
import dev.skillbill.tray.core.domain.model.QaCriterionFinding
import dev.skillbill.tray.core.domain.model.QaCriterionResult
import dev.skillbill.tray.core.domain.model.ReviewFinding
import dev.skillbill.tray.core.domain.model.ReviewPass
import dev.skillbill.tray.core.domain.model.SubtaskPhaseResults
import kotlin.time.Instant

@Preview
@Composable
private fun PhaseResultsWithFindingsPreview() = PhaseResultsPreviewFrame(
  results = listOf(
    SubtaskPhaseResults(
      subtaskId = 1,
      reviewPasses = listOf(
        ReviewPass(
          passNumber = 1, verdict = "changes_requested", unresolvedFindingCount = 3,
          findings = listOf(
            ReviewFinding("blocker", "Pause is lost on restart", "The pause flag lives only in memory, so a runner restart resumes a goal the user paused."),
            ReviewFinding("major", "Unbounded detail query", "The detail read loads every phase record for the goal instead of the requested page."),
            ReviewFinding("nit", "Stale KDoc", "Stale KDoc"),
          ),
        ),
        ReviewPass(passNumber = 2, verdict = "approved", unresolvedFindingCount = 0, findings = emptyList()),
      ),
      audit = PhaseConclusion(
        verdict = "satisfied", summary = "All 4 acceptance criteria are met.",
        detail = "AC-001 met: pause survives restart.\nAC-002 met: detail reads one page.\nAC-003 met: tray badge counts paused goals.\nAC-004 met: docs updated.",
        attemptCount = 2,
      ),
    ),
    SubtaskPhaseResults(
      subtaskId = 2,
      reviewPasses = listOf(ReviewPass(passNumber = 1, verdict = "review_cap_reached", unresolvedFindingCount = 1,
        findings = listOf(ReviewFinding("minor", "Magic number for retry delay", "Use the shared backoff constant.")))),
      audit = PhaseConclusion(verdict = "gaps_found", summary = "AC-003 has no test.", detail = null, attemptCount = 1),
    ),
  ),
  qa = GoalQaResult(
    "findings", "findings left open: F-2", 3, Instant.parse("2026-10-20T10:15:30Z"),
    findings = listOf(
      GoalQaFinding("F-1", "The login button does nothing after a cold start.", "/home/me/.skill-bill/qa/repo/run-1/login.png", fixed = true),
      GoalQaFinding("F-2", "The home screen stays blank after sign-in.", "/home/me/.skill-bill/qa/repo/run-4/home.png", fixed = false),
    ),
    criteria = listOf(
      QaCriterionResult(
        "AC-001", false,
        listOf(QaCheckResult("The home screen shows the signed-in user.", false)),
        listOf(QaCriterionFinding("F-2", listOf("Open the app", "Log in"), "The home screen shows the user name.", "The home screen stays blank.")),
      ),
      QaCriterionResult("AC-002", true, listOf(QaCheckResult("The pause flag is written with run state.", true)), emptyList()),
    ),
  ),
  subtaskName = mapOf(1 to "Persist pause state", 2 to "Retry flaky store reads")::get,
)

@Preview
@Composable
private fun PhaseResultsAllClearPreview() = PhaseResultsPreviewFrame(
  results = listOf(
    SubtaskPhaseResults(
      subtaskId = 1,
      reviewPasses = listOf(ReviewPass(passNumber = 1, verdict = "approved", unresolvedFindingCount = 0, findings = emptyList())),
      audit = PhaseConclusion(verdict = "satisfied", summary = "All criteria met.", detail = null, attemptCount = 1),
      review = PhaseConclusion(
        verdict = "approved",
        summary = "No production-blocking findings; the retained field values survive a restart.",
        detail = "Reviewed the pause-state persistence and the detail query. The retained values are written with the rest of the run state and read back on restart.",
        attemptCount = 1,
      ),
    ),
  ),
  qa = GoalQaResult(
    "passed", null, 1, Instant.parse("2026-10-20T10:15:30Z"),
    findings = listOf(
      GoalQaFinding("F-1", "The login button does nothing after a cold start.", null, fixed = true),
      GoalQaFinding("F-2", "The home screen stays blank after sign-in.", null, fixed = true),
    ),
  ),
  subtaskName = { "Add phase results to goal activity" },
)

@Preview
@Composable
private fun PhaseResultsQaOnlyPreview() = PhaseResultsPreviewFrame(
  results = emptyList(),
  qa = GoalQaResult(
    "incomplete", "other", 0, Instant.parse("2026-10-20T16:14:00Z"),
    criteria = listOf(
      QaCriterionResult(
        "AC-001", false,
        listOf(QaCheckResult("Retained field values survive a restart.", false)),
        listOf(QaCriterionFinding("F-1", emptyList(), "Values are still there.", "Values are empty.")),
      ),
      QaCriterionResult("AC-002", true, listOf(QaCheckResult("The pause flag is written with run state.", true)), emptyList()),
    ),
  ),
  subtaskName = { null },
)

/** The goal activity card around the section, so previews match the detail pane. */
@Composable
private fun PhaseResultsPreviewFrame(results: List<SubtaskPhaseResults>, qa: GoalQaResult?, subtaskName: (Int) -> String?) {
  DesignSystemTheme {
    Column(Modifier.background(canvas).padding(16.dp)) {
      Column(
        Modifier.width(560.dp).background(panel, RoundedCornerShape(10.dp)).border(1.dp, border, RoundedCornerShape(10.dp))
          .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        DesignLabel("Goal activity")
        PhaseResultsSection(results, qa, subtaskName)
        QaFindingsSection(qa)
      }
    }
  }
}
