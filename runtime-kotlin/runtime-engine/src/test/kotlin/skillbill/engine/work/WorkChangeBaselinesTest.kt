package skillbill.engine.work

import skillbill.engine.work.changes.WorkChangeBaselineEnvironment
import skillbill.engine.work.changes.workChangeBaseline
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.GoalWorktreeState
import skillbill.ports.idestatus.model.GoalWorktreeView
import skillbill.ports.idestatus.model.StandaloneMonitorTarget
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import java.lang.reflect.Proxy
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

      override fun info(message: String) = Unit
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
  fun `active and retained goals use the worktree while removed goals use the committed branch range`() {
    val goal = workItem("goal-1", WorkItemKind.FEATURE_GOAL)
    val manifest = manifest()
    val active = GoalWorktreeView("/goal-worktree", GoalWorktreeState.ACTIVE)
    val retained = GoalWorktreeView("/goal-worktree", GoalWorktreeState.RETAINED, "unpublished_commits", "keep edits")
    val removed = GoalWorktreeView("/goal-worktree", GoalWorktreeState.REMOVED)

    assertEquals(
      WorkChangeBaseline.TargetMergeBase("/goal-worktree", "main"),
      workChangeBaseline(null, manifest, goal, environment(emptyWorkflowStates(), active)),
    )
    assertEquals(
      WorkChangeBaseline.TargetMergeBase("/goal-worktree", "main"),
      workChangeBaseline(null, manifest, goal, environment(emptyWorkflowStates(), retained)),
    )
    assertEquals(
      WorkChangeBaseline.CommittedBranchRange("/repo", "main", "feat/SKILL-455"),
      workChangeBaseline(null, manifest, goal, environment(emptyWorkflowStates(), removed)),
    )
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
    val workflows = workflowsWith(artifacts)
    val item = workItem("w-run", WorkItemKind.FEATURE_TASK_RUNTIME)

    val baseline = workChangeBaseline(null, null, item, environment(workflows))

    assertEquals(WorkChangeBaseline.TargetMergeBase("/repo", "feat/SKILL-454"), baseline)
  }

  @Test
  fun `feature task run with an undecodable branch falls back to the default branch and warns`() {
    val corrupt = mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH.label() to mapOf("x" to 1))
    val item = workItem("w-run", WorkItemKind.FEATURE_TASK_RUNTIME)

    val baseline = workChangeBaseline(null, null, item, environment(workflowsWith(corrupt)))

    assertEquals(WorkChangeBaseline.TargetMergeBase("/repo", null), baseline)
    assertTrue(warnings.single().contains("workflow=w-run"))
  }

  @Test
  fun `monitor diffs its own worktree against the default branch merge base`() {
    val baseline =
      workChangeBaseline(monitorRecord("/wt/12"), null, null, environment(emptyWorkflowStates()))

    assertEquals(WorkChangeBaseline.TargetMergeBase("/wt/12", null), baseline)
  }

  private fun resolveGoal(goalBaseBranch: String?): WorkChangeBaseline? {
    val goal = workItem("goal-1", WorkItemKind.FEATURE_GOAL)
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
    return workChangeBaseline(null, manifest, goal, environment(emptyWorkflowStates()))
  }

  private fun workflowsWith(artifacts: Map<String, Any?>): WorkflowStateRepository =
    workflowStates { method, arguments ->
      if (method == "readFeatureTaskArtifacts") {
        val families = arguments[1] as Set<*>
        DurableWorkflowArtifacts.fromMap(
          artifacts.filterKeys { key -> families.any { it is DurableWorkflowArtifactFamily && it.label() == key } },
        )
      } else {
        null
      }
    }

  private fun emptyWorkflowStates(): WorkflowStateRepository = workflowStates { _, _ -> null }

  private fun environment(
    workflows: WorkflowStateRepository,
    goalWorktree: GoalWorktreeView? = null,
  ) = WorkChangeBaselineEnvironment(repoRoot, workflows, diagnostics, goalWorktree)

  private fun manifest() =
    DecompositionManifest(
      issueKey = "SKILL-455",
      featureName = "changes",
      parentSpecPath = "spec.md",
      baseBranch = "main",
      featureBranch = "feat/SKILL-455",
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
      subtasks = emptyList(),
    )

  private fun workflowStates(handler: (String, Array<out Any?>) -> Any?): WorkflowStateRepository =
    Proxy.newProxyInstance(
      WorkflowStateRepository::class.java.classLoader,
      arrayOf(WorkflowStateRepository::class.java),
    ) { _, method, arguments -> handler(method.name, arguments.orEmpty()) } as WorkflowStateRepository

  private fun workItem(
    workflowId: String,
    kind: WorkItemKind,
  ) = WorkItem(null, kind, workflowId, Instant.EPOCH, "running", Instant.EPOCH, false)

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
