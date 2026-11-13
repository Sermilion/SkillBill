package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.data.repository.phaseCardProjectionRuntimeFixture
import dev.skillbill.desktop.core.data.repository.phaseCardRuntimeFixture
import dev.skillbill.desktop.core.domain.model.PhaseCardState
import dev.skillbill.desktop.core.domain.model.AuditOutcome
import dev.skillbill.desktop.core.domain.model.ReviewFindingStatus
import dev.skillbill.desktop.core.domain.model.ReviewReportFinding
import skillbill.goalrunner.model.GoalRunnerSubtaskAuditAttempt
import skillbill.goalrunner.model.GoalRunnerSubtaskAuditCriterion
import skillbill.goalrunner.model.GoalRunnerSubtaskAuditOutcome
import skillbill.goalrunner.model.GoalRunnerSubtaskAuditResult
import skillbill.goalrunner.model.GoalRunnerReviewFinding
import skillbill.goalrunner.model.GoalRunnerReviewPassResult
import skillbill.goalrunner.model.GoalRunnerRunningReviewLane
import skillbill.goalrunner.model.GoalRunnerRunningReviewPass
import skillbill.goalrunner.model.GoalRunnerSubtaskPhaseResults
import skillbill.goalrunner.model.GoalRunnerSubtaskExecution
import skillbill.idestatus.model.IdeStatusFreshness
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import skillbill.ports.idestatus.model.StandalonePlanProgress
import skillbill.ports.idestatus.model.StandalonePlanReport
import skillbill.ports.idestatus.model.StandalonePlanStage
import skillbill.ports.idestatus.model.StandalonePlanSubtask
import skillbill.ports.idestatus.model.StandalonePlanUnit
import skillbill.ports.idestatus.model.StandalonePlanUnitState
import skillbill.ports.idestatus.model.WorkDetailItem
import java.time.Instant
import skillbill.review.model.ReviewFindingStatus as RuntimeReviewFindingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuntimeDetailMapperTest {
  @Test
  fun `detail maps all goal finish cards in runtime order`() {
    val now = Instant.parse("2026-11-08T10:00:00Z")
    val item = WorkDetailItem(
      snapshot = IdeStatusSnapshot("repo", IdeStatusLifecycleState.ACTIVE, IdeStatusStep("implement", "Implement"), now, IdeStatusFreshness.FRESH, "Running", "Feature"),
      recoveryWorkId = "workflow", repositoryRoot = "/repo", stepPipeline = emptyList(), goalStatus = phaseCardProjectionRuntimeFixture(),
    )

    val detail = item.toDetail(now)

    assertEquals(listOf("goal_assessment", "qa", "qa_fix", "validate", "pr", "monitor", "monitor_fix"), detail.goalFinishCards.map { it.phaseId })
    assertEquals("feature/527", detail.goalFinishCards.first().output?.pushedBranch)
    assertEquals(7, detail.goalFinishCards.first().output?.pullRequestNumber)
  }

  @Test
  fun `phase card mapper preserves recorded facts and keeps missing facts null`() {
    val mapped = GoalRunnerSubtaskPhaseResults(2, phaseCards = listOf(phaseCardRuntimeFixture())).toDomain().phaseCards.single()
    assertEquals("implement", mapped.phaseId)
    assertEquals(PhaseCardState.Running, mapped.state)
    assertEquals(2, mapped.round)
    assertEquals("agent", mapped.agentId)
    assertEquals(listOf("src/Example.kt"), mapped.files)
    assertEquals(4, mapped.edit?.insertions)
    assertEquals("Read the project context", mapped.output?.summary)
    assertEquals("## Validation strategy\nRun module checks.", mapped.output?.text)
    assertEquals("spec_subtask_1.md", mapped.output?.planRecord?.specPath)
    assertEquals("payload-hash", mapped.output?.planRecord?.payloadHash)
    assertEquals("json repair", mapped.output?.planRecord?.repair)
    assertEquals("passed", mapped.output?.verdict)
    assertEquals("abc123", mapped.output?.commitSha)
    assertEquals(7, mapped.pullRequestNumber)
    assertEquals("passed", mapped.ciCycles.single().outcome)
    assertEquals(0L, mapped.ciCycles.single().checks.single().durationMillis)

    val sparse = GoalRunnerSubtaskPhaseResults(2, phaseCards = listOf(phaseCardRuntimeFixture(nullFacts = true))).toDomain().phaseCards.single()
    assertEquals(PhaseCardState.Blocked, sparse.state)
    assertNull(sparse.agentId)
    assertNull(sparse.model)
    assertNull(sparse.effort)
    assertNull(sparse.blockedReason)
    assertNull(sparse.round)
    assertNull(sparse.startedAt)
    assertNull(sparse.finishedAt)
    assertNull(sparse.durationMillis)
    assertNull(sparse.activityAt)
    assertNull(sparse.activityLabel)
    assertNull(sparse.pullRequestUrl)
    assertNull(sparse.pullRequestNumber)
    assertNull(sparse.conflictBaseBranch)
    assertNull(sparse.fixCommitSha)
    assertEquals(emptyList(), sparse.files)
    assertEquals(emptyList(), sparse.repairedChecks)
    assertNull(sparse.output)
    assertNull(sparse.edit)
    assertEquals(emptyList(), sparse.ciCycles)
  }

  @Test
  fun `standalone plan detail preserves stage output and saved unit projection without inventing output time`() {
    val started = Instant.parse("2026-10-01T09:00:00Z")
    val stageFinished = started.plusSeconds(30)
    val progress = StandalonePlanProgress(
      report = StandalonePlanReport("Feature", "/repo/specs/SKILL-1/spec.md", listOf(StandalonePlanSubtask(1, "Detail", "/repo/specs/SKILL-1/spec_subtask_1.md", emptyList())), listOf("/repo/specs/SKILL-1/decomposition-manifest.yaml", "/repo/specs/SKILL-1/spec.md")),
      stages = listOf(StandalonePlanStage("preplan", started, stageFinished, "Read the repository")),
      plannedSubtaskCount = 1,
      totalSubtaskCount = 1,
      currentPlanningSubtaskId = null,
      planningWaveSubtaskIds = emptyList(),
      blockedSubtaskId = null,
      blockedReason = null,
      units = listOf(StandalonePlanUnit(1, "Detail", "/repo/specs/SKILL-1/spec_subtask_1.md", emptyList(), StandalonePlanUnitState.SAVED, started, stageFinished, null, null)),
      stalledSince = null,
    )
    val item = WorkDetailItem(
      snapshot = IdeStatusSnapshot("repo", IdeStatusLifecycleState.ACTIVE, IdeStatusStep("plan", "Plan"), started, IdeStatusFreshness.FRESH, "Planning", "Feature"),
      recoveryWorkId = "invocation",
      repositoryRoot = "/repo",
      stepPipeline = listOf("preplan", "plan", "plan_subtasks"),
      planOutputTail = listOf("planning output"),
      planOutputUpdatedAt = null,
      planSubtaskLatestLines = mapOf(1 to "[subtask 1] saved"),
      standalonePlanProgress = progress,
    )

    val detail = item.toDetail(started)

    assertEquals(listOf("planning output"), detail.planOutputTail)
    assertEquals(null, detail.planOutputUpdatedAt)
    assertEquals("preplan", detail.standalonePlan?.stages?.single()?.phaseId)
    assertEquals(kotlin.time.Instant.parse(stageFinished.toString()), detail.standalonePlan?.stages?.single()?.finishedAt)
    assertEquals("[subtask 1] saved", detail.planSubtaskLatestLines[1])
    assertEquals("saved", detail.standalonePlan?.units?.single()?.state)
    assertEquals(listOf("/repo/specs/SKILL-1/decomposition-manifest.yaml", "/repo/specs/SKILL-1/spec.md"), detail.standalonePlan?.report?.bundleFiles)
  }

  @Test
  fun `phase results keep the degraded source, the lanes and the finding fields`() {
    val finding =
      GoalRunnerReviewFinding("F-1", "major", "high", "lane-a", "src/A.kt:3", "text", RuntimeReviewFindingStatus.FIXED)
    val result = GoalRunnerSubtaskPhaseResults(
      subtaskId = 3,
      reviewPasses = listOf(GoalRunnerReviewPassResult(1, "approved", 0, listOf(finding), listOf("lane-a"))),
      degradedReadSource = "subtask_review_findings",
    ).toDomain()

    assertEquals("subtask_review_findings", result.degradedReadSource)
    val pass = result.reviewPasses.single()
    assertEquals(listOf("lane-a"), pass.lanes)
    assertEquals(
      ReviewReportFinding("F-1", "major", "high", "lane-a", "src/A.kt:3", "text", ReviewFindingStatus.Fixed),
      pass.findings.single(),
    )
  }

  @Test
  fun `phase results map the running pass and lane completion`() {
    val result = GoalRunnerSubtaskPhaseResults(
      subtaskId = 3,
      runningReviewPass = GoalRunnerRunningReviewPass(
        2,
        listOf(GoalRunnerRunningReviewLane("lane-a", true), GoalRunnerRunningReviewLane("lane-b", false)),
      ),
    ).toDomain()

    assertEquals(2, result.runningReviewPass?.passNumber)
    assertEquals(
      listOf("lane-a" to true, "lane-b" to false),
      result.runningReviewPass?.lanes?.map { it.name to it.finished },
    )
  }

  @Test
  fun `phase results map audit counts criteria notes and attempts`() {
    val result = GoalRunnerSubtaskPhaseResults(
      subtaskId = 3,
      auditResult = GoalRunnerSubtaskAuditResult(
        outcome = GoalRunnerSubtaskAuditOutcome.PASSED,
        metCount = 2,
        totalCount = 2,
        criteria = listOf(GoalRunnerSubtaskAuditCriterion("AC-001", "Retains state", true, 2)),
        attempts = listOf(GoalRunnerSubtaskAuditAttempt("audit:1", 1, false, 1, "audit_plan_fix")),
        attemptTotal = 2,
      ),
    ).toDomain().auditResult

    assertEquals(AuditOutcome.Passed, result?.outcome)
    assertEquals(2, result?.metCount)
    assertEquals(2, result?.totalCount)
    assertEquals("Retains state", result?.criteria?.single()?.text)
    assertEquals(2, result?.criteria?.single()?.fixedInAttempt)
    assertEquals("audit_plan_fix", result?.attempts?.single()?.nextPhaseId)
    assertEquals(2, result?.attemptTotal)
  }

  @Test
  fun `phase results map subtask execution timestamps and counts`() {
    val result = GoalRunnerSubtaskPhaseResults(
      subtaskId = 3,
      execution = GoalRunnerSubtaskExecution(
        workflowId = "wf-sub-3",
        agentId = "claude",
        model = "claude-opus-5-5",
        effort = "high",
        startedAt = Instant.parse("2026-10-01T09:00:00Z"),
        updatedAt = Instant.parse("2026-10-01T11:00:00Z"),
        phaseAttemptCounts = mapOf("implement" to 2),
      ),
    ).toDomain().execution

    assertEquals("wf-sub-3", result?.workflowId)
    assertEquals("claude", result?.agentId)
    assertEquals("claude-opus-5-5", result?.model)
    assertEquals("high", result?.effort)
    assertEquals(kotlin.time.Instant.parse("2026-10-01T09:00:00Z"), result?.startedAt)
    assertEquals(kotlin.time.Instant.parse("2026-10-01T11:00:00Z"), result?.updatedAt)
    assertEquals(mapOf("implement" to 2), result?.phaseAttemptCounts)
  }
}
