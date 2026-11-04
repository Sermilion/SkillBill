package skillbill.engine.work

import skillbill.contracts.JsonCodec
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.work.changes.scope.WorkChangeScopesService
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.work.model.WorkGoalSubtaskPage
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workchanges.WorkChangesPort
import skillbill.ports.workchanges.model.WorkChangeListResult
import skillbill.ports.workchanges.model.WorkChangedFile
import skillbill.ports.workchanges.model.WorkCommitTimelineResult
import skillbill.ports.workchanges.model.WorkFileDiffResult
import skillbill.ports.workchanges.model.WorkGoalScopesResult
import skillbill.ports.workchanges.model.WorkGoalTimelineResult
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkChangeScopesServiceTest {
  private var lastService: WorkChangeScopesService? = null
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
  fun `more than one page of subtasks comes back complete and in manifest order`() {
    val subtasks = (1..120).map { subtask(it, "complete", commitSha = sha(it)) }

    val listed = assertIs<WorkGoalScopesResult.Listed>(scopes(subtasks))

    assertEquals((1..120).toList(), listed.subtasks.map { it.id })
    assertEquals(sha(120), listed.subtasks.last().recordedSha)
  }

  @Test
  fun `recorded sha prefers manifest then commit push artifact and keeps child paths`() {
    val workflows = IdeStatusWorkflowStates()
    saveChild(workflows, "w-2", reviewBase = sha(1), commitPushSha = sha(2))
    saveChild(workflows, "w-3", reviewBase = sha(2), commitPushSha = null, untracked = listOf("notes.txt"))
    val subtasks =
      listOf(
        subtask(1, "complete", commitSha = sha(1)),
        subtask(2, "complete", workflowId = "w-2"),
        subtask(3, "completed_no_change", workflowId = "w-3"),
        subtask(4, "complete", workflowId = "w-2", commitSha = sha(4)),
      )

    val listed = assertIs<WorkGoalScopesResult.Listed>(scopes(subtasks, workflows))

    assertEquals(listOf(sha(1), sha(2), null, sha(4)), listed.subtasks.map { it.recordedSha })
    assertEquals(listOf("notes.txt"), listed.subtasks[2].baselineUntrackedPaths)
    assertTrue(warnings.isEmpty(), warnings.toString())
  }

  @Test
  fun `timeline from a different branch is reported without attribution`() {
    EmptyWorkChangesPort.timelineResult =
      WorkCommitTimelineResult.Listed(
        "base",
        "head",
        "other",
        emptyList(),
        emptySet(),
      )
    val listed = assertIs<WorkGoalScopesResult.Listed>(scopes(listOf(subtask(1, "complete", commitSha = sha(1)))))

    val result =
      assertIs<WorkGoalTimelineResult.OnOtherBranch>(
        lastService!!.goalSubtaskTimeline(listed),
      )

    assertEquals("other", result.currentBranch)
    assertEquals(1, warnings.size)
    EmptyWorkChangesPort.timelineResult = WorkCommitTimelineResult.Failed("unused")
  }

  @Test
  fun `only the current in-progress child is running`() {
    val subtasks =
      listOf(
        subtask(1, "in_progress", workflowId = "w-1"),
        subtask(2, "in_progress", workflowId = "w-2"),
      )

    val listed = assertIs<WorkGoalScopesResult.Listed>(scopes(subtasks, currentWorkflowId = "w-2"))

    assertEquals(listOf(false, true), listed.subtasks.map { it.running })
    assertEquals(DecompositionStatus.IN_PROGRESS, listed.subtasks.last().status)
  }

  @Test
  fun `stacked branch goals do not support commit ranges`() {
    val listed =
      assertIs<WorkGoalScopesResult.Listed>(
        scopes(listOf(subtask(1, "pending")), executionModel = DecompositionExecutionModel.STACKED_BRANCHES),
      )

    assertEquals(false, listed.rangesSupported)
    assertEquals("main", listed.targetBranch)
  }

  @Test
  fun `a workflow that is not a goal is NotAGoal`() {
    val database =
      TrackingDatabase(
        work = listOf(workItem("task-1", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
        workflows = IdeStatusWorkflowStates(),
      )

    assertEquals(WorkGoalScopesResult.NotAGoal("task-1"), service(database).goalSubtaskScopes("task-1"))
  }

  private fun scopes(
    subtasks: List<DecompositionSubtask>,
    workflows: IdeStatusWorkflowStates = IdeStatusWorkflowStates(),
    currentWorkflowId: String? = null,
    executionModel: DecompositionExecutionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
  ): WorkGoalScopesResult {
    val identity = testGoalRepositoryIdentity(gitRepoFixture("work-change-scopes"))
    val controls =
      object : GoalRunnerControlRepository by EmptyGoalRunnerControlRepository {
        override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
          GoalRunnerControlState(repositoryIdentity = identity)
      }
    val database =
      TrackingDatabase(
        work = listOf(workItem("goal-1", WorkItemKind.FEATURE_GOAL, "running", "2026-08-06T10:00:00Z")),
        workflows = workflows,
        controls = controls,
        goalPages = { after, limit ->
          val page = subtasks.filter { after == null || it.id > after }.take(limit)
          WorkGoalSubtaskPage(manifest(page, executionModel), subtasks.size, currentWorkflowId)
        },
      )
    return service(database).goalSubtaskScopes("goal-1")
  }

  private fun service(database: TrackingDatabase): WorkChangeScopesService =
    WorkChangeScopesService(
      database,
      testWorkBoardItemProjection(database),
      testGoalRunnerStatusService(EmptyManifestStore, EmptyOutcomeStore),
      diagnostics,
      EmptyWorkChangesPort,
    ).also { lastService = it }

  private fun manifest(
    subtasks: List<DecompositionSubtask>,
    executionModel: DecompositionExecutionModel,
  ): DecompositionManifest =
    DecompositionManifest(
      issueKey = "SKILL-148",
      featureName = "scopes",
      parentSpecPath = "spec.md",
      executionModel = executionModel,
      baseBranch = "main",
      featureBranch = "feat/SKILL-148",
      currentSubtaskIntent = CurrentSubtaskIntent(1, "start"),
      subtasks = subtasks,
    )

  private fun subtask(
    id: Int,
    status: String,
    workflowId: String? = null,
    commitSha: String? = null,
  ) = DecompositionSubtask(
    id = id,
    name = "subtask $id",
    specPath = "spec_$id.md",
    status = status,
    commitSha = commitSha,
    workflowId = workflowId,
  )

  private fun saveChild(
    workflows: IdeStatusWorkflowStates,
    workflowId: String,
    reviewBase: String?,
    commitPushSha: String?,
    untracked: List<String> = emptyList(),
  ) {
    val continuation =
      DurableWorkflowArtifacts.fromMap(
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label() to
            FeatureTaskRuntimeGoalContinuationArtifact(
              issueKey = "SKILL-148",
              subtaskId = 1,
              suppressPr = true,
              goalBranch = "feat/SKILL-148",
              codeReviewMode = CodeReviewExecutionMode.INLINE,
            ),
        ),
      )
    val artifacts =
      buildMap<String, Any?> {
        reviewBase?.let {
          put(
            DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.label(),
            GoalSubtaskReviewState.initial(it, untracked, CodeReviewExecutionMode.INLINE).toPersistenceWire(),
          )
        }
        commitPushSha?.let {
          put(
            DecompositionManifestPayloadKeys.COMMIT_PUSH_RESULT,
            mapOf(DecompositionManifestPayloadKeys.COMMIT_SHA to it),
          )
        }
      }
    workflows.saveFeatureTaskWorkflow(
      runtimeRecord(workflowId, "2026-08-06T10:00:00Z")
        .copy(artifactsJson = JsonCodec.mapToJsonString(artifacts), typedArtifacts = continuation),
      FeatureTaskWorkflowMode.RUNTIME,
    )
  }

  private fun sha(n: Int): String = n.toString().padStart(40, '0')

  private object EmptyWorkChangesPort : WorkChangesPort {
    var timelineResult: WorkCommitTimelineResult = WorkCommitTimelineResult.Failed("unused")

    override fun changes(baseline: WorkChangeBaseline) = WorkChangeListResult.Failed("unused")

    override fun fileDiff(
      worktreeRoot: String,
      baseSha: String,
      file: WorkChangedFile,
    ) = WorkFileDiffResult.Failed("unused")

    override fun committedChanges(
      worktreeRoot: String,
      fromSha: String,
      toSha: String,
    ) = WorkChangeListResult.Failed("unused")

    override fun committedFileDiff(
      worktreeRoot: String,
      fromSha: String,
      toSha: String,
      file: WorkChangedFile,
    ) = WorkFileDiffResult.Failed("unused")

    override fun uncommittedChanges(worktreeRoot: String) = WorkChangeListResult.Failed("unused")

    override fun commitTimeline(
      worktreeRoot: String,
      targetBranch: String?,
      recordedShas: List<String>,
      headBranch: String?,
    ) = timelineResult
  }
}
