package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.AuditAttempt
import dev.skillbill.desktop.core.domain.model.AuditCriterion
import dev.skillbill.desktop.core.domain.model.AuditOutcome
import dev.skillbill.desktop.core.domain.model.CiCheck
import dev.skillbill.desktop.core.domain.model.CiCycle
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.GoalAssessmentFinding
import dev.skillbill.desktop.core.domain.model.GoalAssessmentFindingState
import dev.skillbill.desktop.core.domain.model.GoalAssessmentResult
import dev.skillbill.desktop.core.domain.model.GoalAssessmentState
import dev.skillbill.desktop.core.domain.model.GoalAssessmentVerification
import dev.skillbill.desktop.core.domain.model.GoalQaFinding
import dev.skillbill.desktop.core.domain.model.GoalQaResult
import dev.skillbill.desktop.core.domain.model.PhaseCardEdit
import dev.skillbill.desktop.core.domain.model.PhaseCardHistoryReceipt
import dev.skillbill.desktop.core.domain.model.PhaseCardOutput
import dev.skillbill.desktop.core.domain.model.PhaseCardPlanRecord
import dev.skillbill.desktop.core.domain.model.PhaseConclusion
import dev.skillbill.desktop.core.domain.model.PlanRunReport
import dev.skillbill.desktop.core.domain.model.PlannedSubtask
import dev.skillbill.desktop.core.domain.model.ReviewFindingStatus
import dev.skillbill.desktop.core.domain.model.ReviewPass
import dev.skillbill.desktop.core.domain.model.ReviewReportFinding
import dev.skillbill.desktop.core.domain.model.SubtaskAuditResult
import dev.skillbill.desktop.core.domain.model.SubtaskPhaseResults
import dev.skillbill.desktop.core.domain.model.ValidationGateRun
import dev.skillbill.desktop.core.domain.model.ValidationResult
import dev.skillbill.desktop.core.domain.model.WorkSubtask

internal val snapshotSubtaskPhases = listOf("preplan", "plan", "plan_subtasks", "implement", "simplify", "audit", "review", "build", "validate", "write_history", "commit_push", "audit_plan_fix", "audit_implement_fix", "verify_findings", "implement_fix")
internal val snapshotChecks = listOf("Unit tests", "Lint", "Format", "Contract tests", "Static analysis", "Integration tests", "Schema parity", "Migration tests")
internal val snapshotReceipt = ValidationResult("passed", snapshotChecks, 1, listOf(ValidationGateRun(74000, "passed", "warm", 24, snapshotChecks, "./gradlew check --continue", 0)))
internal val snapshotOutput = PhaseCardOutput(
  "satisfied", snapshotReceipt, snapshotReceipt,
  PhaseCardHistoryReceipt(listOf("agent/history.md", "agent/decisions.md"), true, true),
  "5be0d1133c7a87af226d71285529aede9885638e", "feat/standalone-phase-records", true, "https://github.com/example/sona/pull/482", 482,
  summary = "Retain standalone phase records on the work board.",
  text = "## Evidence\nThe board keeps the recorded outcome after the phase finishes.\n## Validation\nPopulated-store and interrupted-write tests passed.",
  planRecord = PhaseCardPlanRecord(0, "spec_subtask_1.md", "ab0123456789".repeat(5), "cd0123456789".repeat(5), "json · closed unterminated array"),
)

internal fun snapshotCard(id: String) = finishCard(id).copy(
  round = 1, agentId = "codex", model = "gpt-6.1-sol", effort = "high", startedAt = epoch, finishedAt = epoch,
  durationMillis = 140000, output = when (id) {
    "preplan" -> snapshotOutput.copy(text = "## History and decisions\nKeep previous migration steps for supported records.\n## Reusable patterns\nUse the existing transaction owner.\n## Add-ons\nbill-kotlin-code-review-persistence\n## Validation strategy\nRun populated-store and interrupted-write tests.")
    "plan" -> snapshotOutput.copy(text = "## Steps\n1. Read the phase query and its ownership rules.\n2. Retain saved results for AC-001.\n3. Cover AC-001 and AC-002 with regression tests.\n## Criteria coverage\nAC-001 · steps 2, 3\nAC-002 · step 3")
    else -> snapshotOutput
  },
  edit = if (id in setOf("implement", "simplify", "qa_fix")) PhaseCardEdit(epoch, 3, 42, 18, listOf("BoardQuery.kt")) else null,
  pullRequestUrl = snapshotOutput.pullRequestUrl, pullRequestNumber = 482,
  ciCycles = listOf(CiCycle(1, epoch, epoch, epoch, "passed", "5be0d113", listOf(CiCheck("Desktop checks", "pass", null, epoch, epoch, 74000)))),
  repairedChecks = listOf(CiCheck("Screenshot diff", "pass", null, epoch, epoch, 23000)), fixCommitSha = "5be0d113",
)

internal fun phaseSnapshotDetail(): Detail {
  val criteria = listOf(
    AuditCriterion("AC-001", "Standalone phases remain on the board after completion, including saved results and links to their evidence.", true, null),
    AuditCriterion("AC-002", "An interrupted write preserves the previous result and its original identity.", true, 2),
  )
  val reviews = listOf(ReviewPass(1, "approved", 0, listOf(ReviewReportFinding("F-001", "major", "high", "bill-kotlin-code-review-persistence", "src/BoardQuery.kt:88", "A phase finished at midnight is counted in both days.", ReviewFindingStatus.Fixed)), listOf("bill-kotlin-code-review-persistence", "bill-kotlin-code-review-testing")))
  return emptyDetail.copy(
    goalSpecBundle = PlanRunReport("Standalone phase records", "/repo/spec.md", listOf(PlannedSubtask(1, "Standalone phase records", "/repo/spec_subtask_1.md", emptyList())), listOf("/repo/spec.md", "/repo/traceability.md", "/repo/decomposition-manifest.yaml")),
    stepPipeline = snapshotSubtaskPhases,
    goalFinishPipeline = GOAL_FINISH_PHASES,
    subtasks = listOf(WorkSubtask(1, "Retain standalone phase records", "completed", "5be0d113", null, emptyList())),
    phaseResults = listOf(SubtaskPhaseResults(1, reviews, PhaseConclusion("satisfied", "All criteria map to changes.", null, 2), null,
      auditResult = SubtaskAuditResult(AuditOutcome.Passed, 2, 2, criteria, listOf(AuditAttempt(1, false, 1, "audit_plan_fix"), AuditAttempt(2, true, 0, null)), 2),
      phaseCards = snapshotSubtaskPhases.map(::snapshotCard))),
    goalFinishCards = GOAL_FINISH_PHASES.map(::snapshotCard),
    goalAssessment = GoalAssessmentResult(GoalAssessmentState.Completed, 1, "completed", true, listOf(GoalAssessmentFinding("GA-001", "major", "BoardQuery.kt", 88, "A phase finished at midnight is counted in both days.", GoalAssessmentVerification.Confirmed, GoalAssessmentFindingState.Fixed, "The date boundary now uses the saved finish time."))),
    goalQa = GoalQaResult("passed", null, 1, epoch, listOf(GoalQaFinding("QA-001", "The phase clock kept counting after completion.", true, observed = "The finished row kept counting.", expected = "The saved duration stays fixed.", steps = "Open a completed phase on the board.")), "## Coverage\nThe completed phase and saved duration passed.", null, null),
  )
}
