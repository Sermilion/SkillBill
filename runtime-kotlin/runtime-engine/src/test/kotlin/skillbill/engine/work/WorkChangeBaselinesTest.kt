package skillbill.engine.work

import skillbill.contracts.JsonCodec
import skillbill.engine.work.changes.workChangeBaseline
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandaloneMonitorTarget
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.work.model.WorkItemKind
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkChangeBaselinesTest {
  private val repoRoot = Path.of("/repo")
  private val baseSha = "a".repeat(40)
  private val warnings = mutableListOf<String>()
  private val diagnostics =
    object : RuntimeDiagnostics {
      override fun warning(
        message: String,
        error: Throwable?,
      ) {
        warnings += message
      }

      override fun error(
        message: String,
        error: Throwable?,
      ) = Unit
    }

  @Test
  fun `goal with a current child reads the child's subtask start baseline`() {
    val state =
      GoalSubtaskReviewState.initial(baseSha, listOf("notes.txt"), CodeReviewExecutionMode.INLINE)
    val workflows = workflowsWith("w-child", goalChildArtifacts(state))

    val baseline = resolveGoal(workflows, currentChild = "w-child")

    assertEquals(WorkChangeBaseline.SubtaskStart("/repo", baseSha, listOf("notes.txt")), baseline)
  }

  @Test
  fun `goal between subtasks has no baseline`() {
    assertNull(resolveGoal(IdeStatusWorkflowStates(), currentChild = null))
  }

  @Test
  fun `goal child with an undecodable review state falls back to the merge base and warns`() {
    val corrupt = mapOf(DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.label() to mapOf("x" to 1))
    val workflows = workflowsWith("w-child", corrupt)

    val baseline = resolveGoal(workflows, currentChild = "w-child")

    assertEquals(WorkChangeBaseline.TargetMergeBase("/repo", null), baseline)
    assertTrue(warnings.single().contains("child=w-child"))
  }

  @Test
  fun `feature task run with a resolved branch yields its run start baseline`() {
    val branch =
      FeatureTaskRuntimeResolvedBranch(
        branch = "feat/SKILL-454",
        baseBranch = "main",
        reviewBaseSha = baseSha,
        baselineUntrackedPaths = listOf("scratch.md"),
      )
    val artifacts =
      mapOf(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH.label() to
          branch.asWorkflowArtifactEntry().toWorkflowArtifactMap(),
      )
    val workflows = workflowsWith("w-run", artifacts)
    val item = workItem("w-run", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")

    val baseline = workChangeBaseline(null, null, item, context(workflows), diagnostics)

    assertEquals(WorkChangeBaseline.RunStart("/repo", baseSha, listOf("scratch.md")), baseline)
  }

  @Test
  fun `monitor diffs its own worktree against the default branch merge base`() {
    val baseline =
      workChangeBaseline(monitorRecord("/wt/12"), null, null, context(IdeStatusWorkflowStates()), diagnostics)

    assertEquals(WorkChangeBaseline.TargetMergeBase("/wt/12", null), baseline)
  }

  private fun resolveGoal(
    workflows: IdeStatusWorkflowStates,
    currentChild: String?,
  ): WorkChangeBaseline? {
    val goal = workItem("goal-1", WorkItemKind.FEATURE_GOAL, "running", "2026-08-06T10:00:00Z")
    val status =
      GoalRunnerStatusProjection(
        issueKey = "SKILL-148",
        completeCount = 0,
        pendingCount = 1,
        blockedCount = 0,
        currentSubtaskId = currentChild?.let { 1 },
        currentStep = null,
        activeAgent = null,
        currentChildWorkflowId = currentChild,
      )
    return workChangeBaseline(null, status, goal, context(workflows), diagnostics)
  }

  private fun goalChildArtifacts(state: GoalSubtaskReviewState): Map<String, Any?> =
    mapOf(
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label() to
        FeatureTaskRuntimeGoalContinuationArtifact(
          issueKey = "SKILL-148",
          subtaskId = 1,
          suppressPr = true,
          goalBranch = "feat/SKILL-148",
          codeReviewMode = CodeReviewExecutionMode.INLINE,
        ).asWorkflowArtifactEntry().toWorkflowArtifactMap(),
      DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.label() to state.toPersistenceWire(),
    )

  private fun workflowsWith(
    workflowId: String,
    artifacts: Map<String, Any?>,
  ): IdeStatusWorkflowStates =
    IdeStatusWorkflowStates().apply {
      saveFeatureTaskWorkflow(
        runtimeRecord(workflowId, "2026-08-06T10:00:00Z").copy(artifactsJson = JsonCodec.mapToJsonString(artifacts)),
        FeatureTaskWorkflowMode.RUNTIME,
      )
    }

  private fun context(workflows: IdeStatusWorkflowStates): IdeStatusProjectionContext =
    TrackingDatabase(work = emptyList(), workflows = workflows).read { unitOfWork ->
      IdeStatusProjectionContext(unitOfWork, "repo", null, Instant.EPOCH, repoRoot)
    }

  private fun monitorRecord(worktreePath: String): StandalonePhaseStatusRecord =
    StandalonePhaseStatusRecord(
      repositoryIdentity = "repo",
      branchCorrelation = "feat/SKILL-454",
      issueKey = "SKILL-454",
      workflowId = null,
      invocationId = "invocation-12",
      phaseId = "monitor",
      executionId = "execution-12",
      statusStoreId = "store",
      runSequence = "1",
      statusRevision = "1",
      lifecycleState = "running",
      currentStep = "monitor",
      currentActivity = null,
      startedAt = Instant.EPOCH,
      updatedAt = Instant.EPOCH,
      finishedAt = null,
      activeDurationMs = null,
      activeDurationAsOf = null,
      leaseOwner = "owner",
      leaseGeneration = 1,
      leaseExpiresAt = Instant.EPOCH,
      terminalResult = null,
      monitorTarget =
        StandaloneMonitorTarget(
          pullRequestKey = "github.com/acme/repo#12",
          pullRequestUrl = "https://github.com/acme/repo/pull/12",
          headRepository = "github.com/acme/repo",
          headRef = "feat/SKILL-454",
          callerRoot = "/repo",
          worktreePath = worktreePath,
        ),
    )
}
