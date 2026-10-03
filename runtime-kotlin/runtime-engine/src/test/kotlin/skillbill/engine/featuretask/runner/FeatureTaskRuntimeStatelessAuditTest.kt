package skillbill.engine.featuretask.runner

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.lifecycle.continuation.GoalContinuationStateRecordRequest
import skillbill.engine.featuretask.lifecycle.continuation.reviewState
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.model.goalreview.GoalSubtaskReviewDisposition
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_RESULTS.label()
private val FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS.label()
private val FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS.label()

class FeatureTaskRuntimeStatelessAuditTest {
  private fun seedPlanningUpstreamPhases(harness: RunnerHarness) {
    harness.seedPhase("preplan", "completed", 1, "claude", PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, "claude", PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, "claude", IMPLEMENT_OUTPUT)
  }

  @Test
  fun `satisfied audit advances without audit_gap loop edge`() {
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = satisfiedAuditLauncher()))
    val report = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "audit" })
    assertTrue(
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
        .none { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" },
    )
  }

  @Test
  fun `completed audit remains completed when obsolete audit progress is unreadable`() {
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = satisfiedAuditLauncher()))
    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    val auditRecord = harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("audit")
    val auditLaunchCount = harness.launchedPromptPhaseOrder().count { it == "audit" }
    val retiredProgressKey = "feature_task_runtime_audit_gap_progress"
    harness.repository.replaceTaskRuntimeArtifacts(
      WORKFLOW_ID,
      harness.repository.taskRuntimeArtifacts(WORKFLOW_ID) + (retiredProgressKey to "unreadable old progress"),
    )
    val terminalRetry = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertTrue(terminalRetry.blockedReason.contains("Terminal workflows"))
    assertEquals(auditLaunchCount, harness.launchedPromptPhaseOrder().count { it == "audit" })
    assertEquals(auditRecord, harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("audit"))
    assertEquals("unreadable old progress", harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)[retiredProgressKey])
  }

  @Test
  fun `gaps_found audit output is rejected and does not re-enter implement`() {
    var auditLaunches = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "audit") {
          auditLaunches += 1
          facts(auditGapsFoundOutput())
        } else {
          facts(defaultPhaseOutput(request))
        }
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(launcher = launcher),
      )
    val report = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals(1, auditLaunches)
    assertTrue("review" !in harness.launchOrder())
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "implement" })
    assertTrue(
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
        .none { it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID },
    )
  }

  @Test
  fun `audit launch does not write audit-specific gap progress or pause artifacts`() {
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = satisfiedAuditLauncher()))
    val report = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertTrue(harness.repository.taskRuntimeArtifacts(WORKFLOW_ID).keys.none { it.contains("audit_gap") })
  }

  @Test
  fun `legacy audit gap loop edge does not resume implement`() {
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = satisfiedAuditLauncher()))
    seedPlanningUpstreamPhases(harness)
    harness.seedLoopEdge(
      phaseId = "implement",
      loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
      edgeIteration = 1,
    )
    val report = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(0, harness.launchedPromptPhaseOrder().count { it == "implement" })
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "audit" })
  }

  @Test
  fun `legacy completed gaps_found audit is discarded and relaunched fresh`() {
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = satisfiedAuditLauncher()))
    seedPlanningUpstreamPhases(harness)
    harness.seedPhase(
      "audit",
      "completed",
      1,
      "claude",
      auditGapsFoundOutput(),
    )
    harness.seedPhase("review", "completed", 1, "claude", VALID_REVIEW_OUTPUT)
    val report = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "audit" })
    val launched = harness.launchOrder()
    assertEquals(1, launched.count { it == "review" })
    assertTrue(launched.indexOf("audit") < launched.indexOf("review"))
  }

  @Test
  fun `legacy blocked audit with only loop_id marker is discarded and relaunched fresh`() {
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = satisfiedAuditLauncher()))
    seedPlanningUpstreamPhases(harness)
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "audit",
        status = "blocked",
        attemptCount = 1,
        resolvedAgentId = "claude",
        finished = false,
        outputArtifact = null,
        blockedReason = "legacy audit-gap block",
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
        edgeIteration = 1,
      ),
    )
    val report = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "audit" })
  }

  @Test
  fun `legacy blocked gaps_found audit is discarded and relaunched fresh`() {
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = satisfiedAuditLauncher()))
    seedPlanningUpstreamPhases(harness)
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "audit",
        status = "blocked",
        attemptCount = 1,
        resolvedAgentId = "claude",
        finished = false,
        outputArtifact = auditGapsFoundOutput(),
        blockedReason = "legacy audit-gap pause",
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
        edgeIteration = 1,
      ),
    )
    val report = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "audit" })
  }

  @Test
  fun `legacy paused gaps_found audit is discarded and relaunched fresh`() {
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = satisfiedAuditLauncher()))
    seedPlanningUpstreamPhases(harness)
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "audit",
        status = "paused",
        attemptCount = 1,
        resolvedAgentId = "claude",
        finished = false,
        outputArtifact = auditGapsFoundOutput(),
        blockedReason = "legacy audit-gap pause",
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
        edgeIteration = 1,
      ),
    )
    val report = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "audit" })
  }

  @Test
  fun `interrupted legacy implement remediation resumes the audit using retained implementation output`() {
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = satisfiedAuditLauncher()))
    seedPlanningUpstreamPhases(harness)
    harness.seedLoopEdge(
      phaseId = "implement",
      loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
      edgeIteration = 1,
    )
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "implement",
        status = "blocked",
        attemptCount = 2,
        resolvedAgentId = "claude",
        finished = false,
        blockedReason = "Interrupted legacy gap repair",
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
        edgeIteration = 1,
      ),
    )
    val legacyArtifacts =
      mapOf(
        "feature_task_runtime_audit_gap_pause" to "unreadable pause",
        "feature_task_runtime_audit_gap_progress" to "unreadable progress",
        "feature_task_runtime_audit_generations" to "dangling generation",
      )
    harness.repository.replaceTaskRuntimeArtifacts(
      WORKFLOW_ID,
      harness.repository.taskRuntimeArtifacts(WORKFLOW_ID) + legacyArtifacts +
        mapOf(
          FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY to mapOf("audit" to "retired unreadable briefing"),
          FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY to
            mapOf("$WORKFLOW_ID|audit|1|plan#1|legacy" to "retired unreadable projection"),
        ),
    )
    val result = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(result)
    assertEquals(0, harness.launchedPromptPhaseOrder().count { it == "implement" })
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "audit" })
    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)
    assertEquals(legacyArtifacts, artifacts.filterKeys { it in legacyArtifacts })
    assertTrue(harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty().keys.none { it == "audit" })
    assertTrue(harness.recorder.loadDeliveredProjections(WORKFLOW_ID).orEmpty().keys.none { it == "audit" })
  }

  @Test
  fun `review cap carries forward its durable result without launching another review`() {
    val root = Files.createTempDirectory("goal-review-carry-forward")
    try {
      val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      val harness =
        goalContinuationHarness(
          repoRoot = root,
          git = git,
          launcher =
            RuntimeRecordingLauncher { request ->
              val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
              facts(
                when {
                  phaseId == "audit" -> auditSatisfiedOutput()
                  phaseId == "commit_push" -> validJsonOutput("commit_push")
                  else -> validJsonOutput(phaseId)
                },
              )
            },
        )
      harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
      recordReviewCap(harness)
      seedPlanningUpstreamPhases(harness)

      harness.runner.run(harness.request())

      assertReviewCapWasCarriedForward(harness)
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun recordReviewCap(harness: RunnerHarness) {
    check(
      harness.goalContinuationRecorder.recordGoalContinuationState(
        GoalContinuationStateRecordRequest(
          workflowId = WORKFLOW_ID,
          continuation =
            FeatureTaskRuntimeGoalContinuationArtifact(
              issueKey = RUNNER_TEST_ISSUE_KEY,
              subtaskId = 5,
              suppressPr = true,
              goalBranch = "feat/existing-runtime-branch",
              parentWorkflowId = "wfl-parent",
              codeReviewMode = CodeReviewExecutionMode.DEFAULT,
            ),
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )
    val capped =
      requireNotNull(
        harness.goalContinuationRecorder.updateReviewState(WORKFLOW_ID) { state ->
          state.reserveNextPass().completeReservedPass(
            verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
            unresolvedFindingCount = 1,
            findings =
              listOf(
                GoalSubtaskReviewCompactFinding(
                  severity = "blocker",
                  label = "Missing behavior",
                  text = "The required behavior is still absent.",
                  findingId = "F-001",
                ),
              ),
          ).copy(disposition = GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED)
        },
      )
    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap()
    artifacts[GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY] =
      capped.passResults.associate { it.passNumber.toString() to VALID_REVIEW_OUTPUT }
    harness.repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)
  }

  private fun assertReviewCapWasCarriedForward(harness: RunnerHarness) {
    assertEquals(0, harness.launchedPromptPhaseOrder().count { it == "review" })
    assertEquals(
      WorkflowStepStatus.COMPLETED,
      harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("review")?.status,
    )
    assertEquals(
      1,
      harness.goalContinuationRecorder.reviewState(WORKFLOW_ID)?.completedPassCount,
    )
  }

  @Test
  fun `malformed audit output blocks after one agent session`() {
    var auditLaunches = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "audit") {
          auditLaunches += 1
          facts("{not valid json")
        } else {
          facts(defaultPhaseOutput(request))
        }
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(launcher = launcher),
      )
    val report = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals(1, auditLaunches)
  }
}
