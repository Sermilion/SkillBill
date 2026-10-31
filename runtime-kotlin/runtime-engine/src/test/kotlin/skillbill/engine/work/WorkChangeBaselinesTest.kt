package skillbill.engine.work

import skillbill.contracts.JsonCodec
import skillbill.engine.work.changes.workChangeBaseline
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandaloneMonitorTarget
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.work.model.WorkItemKind
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
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
  fun `goal diffs against its manifest base branch`() {
    val baseline = resolveGoal(goalBaseBranch = "feat/SKILL-454")

    assertEquals(WorkChangeBaseline.TargetMergeBase("/repo", "feat/SKILL-454"), baseline)
  }

  @Test
  fun `goal without a manifest base branch has no baseline`() {
    assertNull(resolveGoal(goalBaseBranch = null))
    assertNull(resolveGoal(goalBaseBranch = " "))
  }

  @Test
  fun `feature task run diffs against its resolved base branch`() {
    val branch =
      FeatureTaskRuntimeResolvedBranch(
        branch = "feat/SKILL-455",
        baseBranch = "feat/SKILL-454",
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

    assertEquals(WorkChangeBaseline.TargetMergeBase("/repo", "feat/SKILL-454"), baseline)
  }

  @Test
  fun `feature task run with an undecodable branch falls back to the default branch and warns`() {
    val corrupt = mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH.label() to mapOf("x" to 1))
    val item = workItem("w-run", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")

    val baseline = workChangeBaseline(null, null, item, context(workflowsWith("w-run", corrupt)), diagnostics)

    assertEquals(WorkChangeBaseline.TargetMergeBase("/repo", null), baseline)
    assertTrue(warnings.single().contains("workflow=w-run"))
  }

  @Test
  fun `monitor diffs its own worktree against the default branch merge base`() {
    val baseline =
      workChangeBaseline(monitorRecord("/wt/12"), null, null, context(IdeStatusWorkflowStates()), diagnostics)

    assertEquals(WorkChangeBaseline.TargetMergeBase("/wt/12", null), baseline)
  }

  private fun resolveGoal(goalBaseBranch: String?): WorkChangeBaseline? {
    val goal = workItem("goal-1", WorkItemKind.FEATURE_GOAL, "running", "2026-08-06T10:00:00Z")
    val manifest =
      goalBaseBranch?.let { base ->
        DecompositionManifest(
          issueKey = "SKILL-455",
          featureName = "changes",
          parentSpecPath = "spec.md",
          baseBranch = base,
          featureBranch = "feat/SKILL-455",
          currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
          subtasks = emptyList(),
        )
      }
    return workChangeBaseline(null, manifest, goal, context(IdeStatusWorkflowStates()), diagnostics)
  }

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
