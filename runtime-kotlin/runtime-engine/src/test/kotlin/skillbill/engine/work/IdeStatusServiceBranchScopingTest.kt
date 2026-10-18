package skillbill.engine.work

import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.monitoring.GOAL_FINALIZATION_OPERATION_KIND
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.status.liveLease
import skillbill.engine.work.model.IdeStatusRequest
import skillbill.engine.work.model.toStatusWireMap
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.idestatus.model.IdeStatusFreshness
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.idestatus.StandalonePhaseStatusRepository
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.ports.idestatus.model.IdeStatusProblemCode
import skillbill.ports.idestatus.model.IdeStatusWorkflowExecution
import skillbill.ports.idestatus.model.StandaloneEligiblePage
import skillbill.ports.idestatus.model.StandaloneMonitorTarget
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode.PROSE
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class IdeStatusServiceBranchScopingTest {
  @Test
  fun `stale feature branch registration follows the branch owned by the goal manifest`() {
    val featureBranch = "feat/SKILL-148-status-fix"
    val fixture = gitRepoFixture("ide-status-goal-feature-branch-transition", branch = featureBranch)
    val identity = testGoalRepositoryIdentity(fixture)
    val initial = completedGoalManifestState(fixture, identity)
    val state = initial.copy(manifest = initial.manifest.copy(featureBranch = featureBranch))
    val database = registeredGoalDatabase(identity, "feat/SKILL-148-initial")
    val result =
      ideStatusService(
        database,
        manifestStore =
          StubGoalManifestStore(
            state,
            planning = planningSnapshot(GoalPlanningStatusState.PREPARED),
            lease = liveLease(),
          ),
      ).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))

    assertNull(result.snapshot.problem)
    assertEquals("goal-1", result.snapshot.workflowId)
    assertEquals(featureBranch, result.snapshot.branchCorrelation)
    assertEquals("parent-execution", result.snapshot.execution?.executionId)
    assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
    assertEquals(0, database.writeCalls)
  }

  @Test
  fun `base branch registration follows the goal feature branch through live finalization`() {
    val featureBranch = "feat/SKILL-148-status-fix"
    val fixture = gitRepoFixture("ide-status-goal-branch-transition", branch = featureBranch)
    val identity = testGoalRepositoryIdentity(fixture)
    val initial = completedGoalManifestState(fixture, identity)
    val state = initial.copy(manifest = initial.manifest.copy(featureBranch = featureBranch))
    val database = registeredGoalDatabase(identity, "main", goalState = "paused")
    val parentProgress =
      GoalRunnerWorkflowProgress(
        workflowId = "goal-1",
        workflowStatus = WorkflowStatus.PAUSED,
        currentStepId = "plan",
        progressToken = "monitor-heartbeat",
        latestDeclaredProgressEvent =
          GoalProgressEvent(
            eventKind = GoalProgressEventKind.OPERATION_HEARTBEAT,
            workflowId = "goal-1",
            workflowPhase = "monitor",
            processAlive = true,
            sequenceNumber = 1,
            timestamp = ideStatusObservedAt,
            stepId = "monitor",
            operationName = "monitor",
            operationKind = GOAL_FINALIZATION_OPERATION_KIND,
          ),
      )
    val result =
      ideStatusService(
        database,
        manifestStore =
          StubGoalManifestStore(
            state,
            planning = planningSnapshot(GoalPlanningStatusState.PREPARED),
            lease = liveLease(),
          ),
        outcomeStore =
          object : GoalRunnerWorkflowOutcomeStore by EmptyOutcomeStore {
            override fun progress(workflowId: String): GoalRunnerWorkflowProgress? =
              parentProgress.takeIf { workflowId == "goal-1" }
          },
      ).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))

    assertNull(result.snapshot.problem)
    assertEquals("goal-1", result.snapshot.workflowId)
    assertEquals(featureBranch, result.snapshot.branchCorrelation)
    assertEquals("parent-execution", result.snapshot.execution?.executionId)
    assertEquals("2", result.snapshot.execution?.runSequence)
    assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
    assertEquals("monitor", result.snapshot.currentStep.id)
    assertEquals(2, result.snapshot.progress?.completed)
    assertEquals("Goal SKILL-148 is active on monitor.", result.snapshot.summary)
    assertEquals(0, database.writeCalls)
  }

  @Test
  fun `goal registered on a branch it does not own keeps its execution identity on any checkout`() {
    val checkout = "feat/OTHER-9-stack-base"
    val fixture = gitRepoFixture("ide-status-goal-unowned-branch", branch = checkout)
    val identity = testGoalRepositoryIdentity(fixture)
    val result =
      ideStatusService(
        registeredGoalDatabase(identity, "feat/OTHER-8-launch-branch"),
        manifestStore = StubGoalManifestStore(goalManifestState(fixture, identity, childWorkflowId = "w-child")),
      ).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))

    assertNull(result.snapshot.problem)
    assertEquals("goal-1", result.snapshot.workflowId)
    assertEquals("parent-execution", result.snapshot.execution?.executionId)
    assertEquals(checkout, result.snapshot.branchCorrelation)
  }

  @Test
  fun `live feature goal on the checkout branch outranks a later terminal standalone`() {
    val featureBranch = "feat/SKILL-148-status-fix"
    val fixture = gitRepoFixture("ide-status-live-goal-beats-terminal-standalone", branch = featureBranch)
    val identity = testGoalRepositoryIdentity(fixture)
    val result =
      ideStatusService(
        registeredGoalDatabase(
          identity,
          featureBranch,
          eligibleStandalones =
            listOf(
              StandalonePhaseStatusRecord(
                repositoryIdentity = identity,
                branchCorrelation = featureBranch,
                issueKey = "SKILL-415",
                workflowId = "standalone-415",
                invocationId = "standalone-invocation",
                phaseId = "review",
                executionId = "standalone-execution",
                statusStoreId = "standalone-store",
                runSequence = "11",
                statusRevision = "1",
                lifecycleState = "completed",
                currentStep = "review",
                currentActivity = null,
                startedAt = ideStatusObservedAt.minusSeconds(300),
                updatedAt = ideStatusObservedAt,
                finishedAt = ideStatusObservedAt,
                activeDurationMs = null,
                activeDurationAsOf = null,
                leaseOwner = "owner",
                leaseGeneration = 1L,
                leaseExpiresAt = ideStatusObservedAt.plusSeconds(3600),
                terminalResult = "completed",
              ),
            ),
          goalUpdatedAt = "2026-08-06T11:58:00Z",
        ),
      ).status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals("goal-1", result.snapshot.workflowId)
  }

  @Test
  fun `live feature goal on the checkout branch outranks a later blocked standalone`() {
    val featureBranch = "feat/SKILL-148-status-fix"
    val fixture = gitRepoFixture("ide-status-live-goal-beats-blocked-standalone", branch = featureBranch)
    val identity = testGoalRepositoryIdentity(fixture)
    val result =
      ideStatusService(
        registeredGoalDatabase(
          identity,
          featureBranch,
          eligibleStandalones =
            listOf(
              StandalonePhaseStatusRecord(
                repositoryIdentity = identity,
                branchCorrelation = featureBranch,
                issueKey = "SKILL-415",
                workflowId = "standalone-415",
                invocationId = "standalone-invocation",
                phaseId = "validation",
                executionId = "standalone-execution",
                statusStoreId = "standalone-store",
                runSequence = "11",
                statusRevision = "1",
                lifecycleState = "blocked",
                currentStep = "validation",
                currentActivity = null,
                startedAt = ideStatusObservedAt.minusSeconds(300),
                updatedAt = ideStatusObservedAt,
                finishedAt = ideStatusObservedAt,
                activeDurationMs = null,
                activeDurationAsOf = null,
                leaseOwner = "owner",
                leaseGeneration = 1L,
                leaseExpiresAt = ideStatusObservedAt.plusSeconds(3600),
                terminalResult = "blocked",
              ),
            ),
          goalUpdatedAt = "2026-08-06T11:58:00Z",
        ),
      ).status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals("goal-1", result.snapshot.workflowId)
  }

  @Test
  fun `pull request monitor is reported from an unrelated checkout with its exact target`() {
    val fixture = gitRepoFixture("ide-status-monitor-target", branch = "feat/OTHER-9-unrelated")
    val identity = testGoalRepositoryIdentity(fixture)
    val record =
      StandalonePhaseStatusRecord(
        repositoryIdentity = identity,
        branchCorrelation = "feat/SKILL-427-shared",
        issueKey = null,
        workflowId = null,
        invocationId = "monitor-invocation",
        phaseId = "monitor",
        executionId = "monitor-execution",
        statusStoreId = "store",
        runSequence = "1",
        statusRevision = "1",
        lifecycleState = "active",
        currentStep = "monitor",
        currentActivity = null,
        startedAt = ideStatusObservedAt.minusSeconds(60),
        updatedAt = ideStatusObservedAt,
        finishedAt = null,
        activeDurationMs = null,
        activeDurationAsOf = null,
        leaseOwner = "owner",
        leaseGeneration = 1L,
        leaseExpiresAt = ideStatusObservedAt.plusSeconds(3600),
        terminalResult = null,
        monitorTarget =
          StandaloneMonitorTarget(
            pullRequestKey = "github.com/acme/repo#7",
            pullRequestUrl = "https://github.com/acme/repo/pull/7",
            headRepository = "github.com/fork-a/repo",
            headRef = "feat/SKILL-427-shared",
            callerRoot = fixture.toString(),
            worktreePath = "/wt/7",
          ),
      )
    val base = TrackingDatabase(work = emptyList(), workflows = IdeStatusWorkflowStates())
    val statuses =
      object : StandalonePhaseStatusRepository by base.unitOfWork().standalonePhaseStatuses {
        override fun readEligible(
          repositoryIdentity: String,
          branchCorrelation: String?,
          now: Instant,
          page: StandaloneEligiblePage,
          monitorRepositoryIdentity: String,
        ): List<StandalonePhaseStatusRecord> = listOf(record).eligiblePage(page)
      }

    val result =
      ideStatusService(TrackingDatabase(emptyList(), base.workflows, statusRepository = statuses)).status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertNull(result.snapshot.problem)
    assertEquals("monitor-execution", result.snapshot.execution?.executionId)
    val summary = requireNotNull(result.snapshot.summary)
    assertEquals(true, "Pull request https://github.com/acme/repo/pull/7;" in summary, summary)
    assertEquals(true, "worktree /wt/7" in summary, summary)
  }

  private fun registeredGoalDatabase(
    identity: String,
    registeredBranch: String,
    goalState: String = "running",
    eligibleStandalones: List<StandalonePhaseStatusRecord> = emptyList(),
    goalUpdatedAt: String = "2026-08-06T10:00:00Z",
  ): TrackingDatabase {
    val database = goalOnlyDatabase(goalState)
    val statuses =
      object : StandalonePhaseStatusRepository by database.unitOfWork().standalonePhaseStatuses {
        override fun latestWorkflowExecution(workflowId: String): IdeStatusWorkflowExecution? =
          IdeStatusWorkflowExecution(
            repositoryIdentity = identity,
            branchCorrelation = registeredBranch,
            issueKey = "SKILL-148",
            workflowId = "goal-1",
            invocationId = "parent-invocation",
            executionId = "parent-execution",
            statusStoreId = "status-store",
            runSequence = "2",
            statusRevision = "1",
            lifecycleState = "active",
            startedAt = ideStatusObservedAt.minusSeconds(600),
            updatedAt = ideStatusObservedAt,
          ).takeIf { workflowId == "goal-1" }

        override fun readEligible(
          repositoryIdentity: String,
          branchCorrelation: String?,
          now: Instant,
          page: StandaloneEligiblePage,
          monitorRepositoryIdentity: String,
        ): List<StandalonePhaseStatusRecord> = eligibleStandalones.eligiblePage(page)
      }
    return TrackingDatabase(
      listOf(workItem("goal-1", WorkItemKind.FEATURE_GOAL, goalState, goalUpdatedAt)),
      database.workflows,
      statusRepository = statuses,
    )
  }

  @Test
  fun `goal preplanning and planning stay visible on a custom base branch before any child launches`() {
    val baseBranch = "base/SKILL-380-phase-slot-strategies"
    val fixture = gitRepoFixture("ide-status-planning-custom-base", branch = baseBranch)
    val identity = testGoalRepositoryIdentity(fixture)
    val initial = goalManifestState(fixture, identity, childWorkflowId = "")
    val state =
      initial.copy(
        manifest =
          initial.manifest.copy(
            baseBranch = baseBranch,
            currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
            subtasks =
              initial.manifest.subtasks.map {
                it.copy(
                  status = "pending",
                  workflowId = null,
                  lastResumableStep = null,
                )
              },
          ),
      )
    listOf(
      GoalPlanningStatusState.NOT_STARTED,
      GoalPlanningStatusState.PREPLANNED,
      GoalPlanningStatusState.PARTIALLY_PLANNED,
    ).forEach { planningState ->
      val planning =
        planningSnapshot(planningState)
          .copy(
            sharedPreplanPrepared = planningState != GoalPlanningStatusState.NOT_STARTED,
            plannedSubtaskCount = if (planningState == GoalPlanningStatusState.PARTIALLY_PLANNED) 1 else 0,
            currentPlanningSubtaskId = 1,
          )
      val result =
        ideStatusService(
          goalOnlyDatabase(),
          manifestStore = StubGoalManifestStore(state, planning = planning, lease = liveLease()),
        ).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))

      assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState, planningState.wireValue)
      assertEquals("goal-1", result.snapshot.workflowId)
      assertEquals("planning", result.snapshot.currentStep.id)
      assertEquals(planningState, result.snapshot.planning?.state)
      assertEquals(planning.sharedPreplanPrepared, result.snapshot.planning?.sharedPreplanPrepared)
      assertNull(result.snapshot.currentPhaseExecution)
    }
  }

  @Test
  fun `goals stay visible on prepared base branches and branches that do not name their issue key`() {
    val baseBranch = "base/SKILL-380-phase-slot-strategies"
    listOf(
      baseBranch to GoalPlanningStatusState.PREPARED,
      "feat/OTHER-9-unrelated" to GoalPlanningStatusState.PARTIALLY_PLANNED,
    ).forEachIndexed { index, (branch, planningState) ->
      val fixture = gitRepoFixture("ide-status-planning-scope-$index", branch = branch)
      val identity = testGoalRepositoryIdentity(fixture)
      val initial = goalManifestState(fixture, identity, childWorkflowId = "")
      val state = initial.copy(manifest = initial.manifest.copy(baseBranch = baseBranch))
      val result =
        ideStatusService(
          goalOnlyDatabase(),
          manifestStore =
            StubGoalManifestStore(state, planning = planningSnapshot(planningState), lease = liveLease()),
        ).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))

      assertNull(result.snapshot.problem, branch)
      assertEquals("goal-1", result.snapshot.workflowId, branch)
    }
  }

  @Test
  fun `goal mid-planning keeps planning and omits current_phase_execution`() {
    val fixture = gitRepoFixture("ide-status-planning-no-execution")
    val identity = testGoalRepositoryIdentity(fixture)
    val result =
      ideStatusService(
        goalOnlyDatabase(),
        manifestStore =
          StubGoalManifestStore(
            goalManifestState(fixture, identity, childWorkflowId = "w-child", lastResumableStep = "plan"),
            planning = planningSnapshot(GoalPlanningStatusState.PARTIALLY_PLANNED),
          ),
      ).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))

    assertEquals("planning", result.snapshot.currentStep.id)
    assertEquals(GoalPlanningStatusState.PARTIALLY_PLANNED, result.snapshot.planning?.state)
    assertNull(result.snapshot.currentPhaseExecution)
    assertFalse(result.snapshot.toStatusWireMap().containsKey("current_phase_execution"))
  }

  @Test
  fun `goal with launched child shows review despite missing goal planning checkpoints`() {
    val fixture = gitRepoFixture("ide-status-goal-child-execution")
    val identity = testGoalRepositoryIdentity(fixture)
    val database =
      goalWithLaunchedChildDatabase(
        identity,
        Instant.parse("2026-08-06T09:15:00Z"),
        childArtifactsJson =
          phaseRecordsArtifactsJson(
            "preplan" to phaseRecordWire("preplan", "completed", null),
            "plan" to phaseRecordWire("plan", "completed", null),
            "plan_subtasks" to phaseRecordWire("plan_subtasks", "completed", null),
            "implement" to phaseRecordWire("implement", "completed", null),
            "simplify" to phaseRecordWire("simplify", "completed", null),
            "audit" to phaseRecordWire("audit", "completed", null),
            "review" to
              phaseRecordWire(
                "review",
                "running",
                null,
                options = PhaseRecordOptions(reviewPassNumber = 2),
              ),
          ),
      )
    val result =
      ideStatusService(
        database,
        manifestStore =
          StubGoalManifestStore(
            goalManifestState(fixture, identity, childWorkflowId = "w-child"),
            planning =
              planningSnapshot(GoalPlanningStatusState.NOT_STARTED)
                .copy(sharedPreplanPrepared = false, plannedSubtaskCount = 0),
            lease = liveLease(),
          ),
      ).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))

    assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
    assertEquals(GoalPlanningStatusState.NOT_STARTED, result.snapshot.planning?.state)
    assertEquals("Goal SKILL-148 is active on review.", result.snapshot.summary)
    val execution = requireNotNull(result.snapshot.currentPhaseExecution)
    assertEquals("review", result.snapshot.currentStep.id)
    assertEquals("review", execution.phaseId)
    assertEquals(IdeStatusCurrentPhaseExecutionKind.PASS, execution.kind)
    assertEquals(2, execution.count)
  }

  @Test
  fun `goal prefers child derived phase over stale child current_step_id`() {
    val fixture = gitRepoFixture("ide-status-goal-stale-step")
    val identity = testGoalRepositoryIdentity(fixture)
    val database =
      goalWithLaunchedChildDatabase(
        identity,
        Instant.parse("2026-08-06T09:15:00Z"),
        childCurrentStep = "verify_findings",
        childArtifactsJson =
          phaseRecordsArtifactsJson(
            "preplan" to phaseRecordWire("preplan", "completed", null),
            "plan" to phaseRecordWire("plan", "completed", null),
            "plan_subtasks" to phaseRecordWire("plan_subtasks", "completed", null),
            "implement" to phaseRecordWire("implement", "completed", null),
            "simplify" to phaseRecordWire("simplify", "completed", null),
            "audit" to phaseRecordWire("audit", "completed", null),
            "review" to phaseRecordWire("review", "completed", null),
            "verify_findings" to phaseRecordWire("verify_findings", "completed", null),
          ),
      )
    val staleProgress =
      GoalRunnerWorkflowProgress(
        workflowId = "w-child",
        workflowStatus = WorkflowStatus.RUNNING,
        currentStepId = "verify_findings",
        progressToken = "stale-verify-findings",
        latestLivenessSignal = "workflow_status=running; step=verify_findings",
      )
    val result =
      ideStatusService(
        database,
        manifestStore =
          StubGoalManifestStore(
            goalManifestState(fixture, identity, childWorkflowId = "w-child").let { state ->
              state.copy(
                manifest =
                  state.manifest.copy(
                    subtasks =
                      state.manifest.subtasks.map { subtask ->
                        if (subtask.id == 2) subtask.copy(lastResumableStep = "verify_findings") else subtask
                      },
                  ),
              )
            },
          ),
        outcomeStore =
          object : GoalRunnerWorkflowOutcomeStore by EmptyOutcomeStore {
            override fun progress(workflowId: String): GoalRunnerWorkflowProgress? =
              staleProgress.takeIf { workflowId == "w-child" }
          },
      ).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))

    assertEquals("validate", result.snapshot.currentStep.id)
  }

  @Test
  fun `started_at stays stable across two polls for the same durable runtime work`() {
    val fixture = gitRepoFixture("ide-status-stable-start")
    val identity = testGoalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureTaskWorkflow(runtimeRecord("w-stable", "2026-08-06T10:00:00Z"), PROSE)
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-stable", identity))
    val database =
      TrackingDatabase(
        work = listOf(workItem("w-stable", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
        workflows = workflows,
      )
    val service = ideStatusService(database)

    val first =
      service.status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )
    val second =
      service.status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt.plusSeconds(60)),
      )

    assertEquals(first.snapshot.startedAt, second.snapshot.startedAt)
    assertEquals(Instant.parse("2026-08-06T08:00:00Z"), first.snapshot.startedAt)
  }

  @Test
  fun `unbound verify work is excluded without same-repo issue correlation`() {
    val fixture = gitRepoFixture("ide-status-verify-unbound")
    val workflows = IdeStatusWorkflowStates()
    workflows.saveRecord(WorkflowFamily.VERIFY, verifyRecord("w-verify", "2026-08-06T11:00:00Z"))
    val database =
      TrackingDatabase(
        work = listOf(workItem("w-verify", WorkItemKind.FEATURE_VERIFY, "running", "2026-08-06T11:00:00Z")),
        workflows = workflows,
      )
    val service = ideStatusService(database)

    val result =
      service.status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
    assertNull(result.snapshot.workflowId)
  }

  @Test
  fun `verify work for another repo issue correlation is not selected`() {
    val fixture = gitRepoFixture("ide-status-verify-other")
    val workflows = IdeStatusWorkflowStates()
    workflows.saveRecord(WorkflowFamily.VERIFY, verifyRecord("w-verify", "2026-08-06T11:00:00Z"))
    workflows.saveFeatureTaskWorkflow(runtimeRecord("w-foreign", "2026-08-06T10:00:00Z"), PROSE)
    workflows.saveFeatureTaskExecutionIdentity(
      identityFor("w-foreign", "repo-root-realpath-v1:/other-repo"),
    )
    val database =
      TrackingDatabase(
        work =
          listOf(
            workItem("w-verify", WorkItemKind.FEATURE_VERIFY, "running", "2026-08-06T11:00:00Z"),
            workItem("w-foreign", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z"),
          ),
        workflows = workflows,
      )
    val service = ideStatusService(database)

    val result =
      service.status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
    assertNull(result.snapshot.workflowId)
  }

  @Test
  fun `verify work is included when issue correlates to same-repo feature-task identity`() {
    val fixture = gitRepoFixture("ide-status-verify-correlated")
    val identity = testGoalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveRecord(WorkflowFamily.VERIFY, verifyRecord("w-verify", "2026-08-06T11:00:00Z"))
    workflows.saveFeatureTaskWorkflow(runtimeRecord("w-runtime", "2026-08-06T09:00:00Z", currentStep = "pr"), PROSE)
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-runtime", identity))
    val database =
      TrackingDatabase(
        work =
          listOf(
            workItem("w-verify", WorkItemKind.FEATURE_VERIFY, "running", "2026-08-06T11:00:00Z"),
            workItem("w-runtime", WorkItemKind.FEATURE_TASK_RUNTIME, "completed", "2026-08-06T09:00:00Z"),
          ),
        workflows = workflows,
      )
    val service = ideStatusService(database)

    val result =
      service.status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals(0, result.exitCode)
    assertNull(result.snapshot.problem)
    assertEquals("w-verify", result.snapshot.workflowId)
    assertEquals(IdeStatusWorkflowFamily.FEATURE_VERIFY, result.snapshot.workflowFamily)
  }

  @Test
  fun `verify registered on the checkout branch is selected without an issue key in the branch`() {
    val fixture = gitRepoFixture("ide-status-verify-registered", branch = "chore/cleanup")
    val database = registeredVerifyDatabase(testGoalRepositoryIdentity(fixture), registeredBranch = "chore/cleanup")

    val result =
      ideStatusService(database).status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertNull(result.snapshot.problem)
    assertEquals("w-verify", result.snapshot.workflowId)
    assertEquals(IdeStatusWorkflowFamily.FEATURE_VERIFY, result.snapshot.workflowFamily)
  }

  @Test
  fun `verify parked after presenting its criteria is reported as finished`() {
    val fixture = gitRepoFixture("ide-status-verify-parked", branch = "chore/cleanup")
    val database =
      registeredVerifyDatabase(
        testGoalRepositoryIdentity(fixture),
        registeredBranch = "chore/cleanup",
        state = "pending",
      )

    val result =
      ideStatusService(database).status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals("w-verify", result.snapshot.workflowId)
    assertEquals(IdeStatusLifecycleState.TERMINAL, result.snapshot.lifecycleState)
  }

  @Test
  fun `verify registered on another branch is not selected`() {
    val fixture = gitRepoFixture("ide-status-verify-other-branch", branch = "chore/cleanup")
    val database = registeredVerifyDatabase(testGoalRepositoryIdentity(fixture), registeredBranch = "chore/elsewhere")

    val result =
      ideStatusService(database).status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
  }

  @Test
  fun `verify registered in another repository is not selected`() {
    val fixture = gitRepoFixture("ide-status-verify-other-repo-registration", branch = "chore/cleanup")
    val database = registeredVerifyDatabase("repo-root-realpath-v1:/other-repo", registeredBranch = "chore/cleanup")

    val result =
      ideStatusService(database).status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
  }

  private fun registeredVerifyDatabase(
    identity: String,
    registeredBranch: String,
    state: String = "running",
  ): TrackingDatabase {
    val workflows = IdeStatusWorkflowStates()
    workflows.saveRecord(WorkflowFamily.VERIFY, verifyRecord("w-verify", "2026-08-06T11:00:00Z"))
    val work = listOf(workItem("w-verify", WorkItemKind.FEATURE_VERIFY, state, "2026-08-06T11:00:00Z"))
    val statuses =
      object : StandalonePhaseStatusRepository by TrackingDatabase(
        work,
        workflows,
      ).unitOfWork().standalonePhaseStatuses {
        override fun latestWorkflowExecution(workflowId: String): IdeStatusWorkflowExecution? =
          IdeStatusWorkflowExecution(
            repositoryIdentity = identity,
            branchCorrelation = registeredBranch,
            issueKey = null,
            workflowId = "w-verify",
            invocationId = "verify-invocation",
            executionId = "verify-execution",
            statusStoreId = "status-store",
            runSequence = "1",
            statusRevision = "1",
            lifecycleState = "active",
            startedAt = ideStatusObservedAt.minusSeconds(600),
            updatedAt = ideStatusObservedAt,
          ).takeIf { workflowId == "w-verify" }
      }
    return TrackingDatabase(work, workflows, statusRepository = statuses)
  }

  @Test
  fun `branch scoping hides work whose issue key is not in the checked-out branch`() {
    val fixture = gitRepoFixture("ide-status-branch-scope", branch = "feat/OTHER-9-unrelated")
    val identity = testGoalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureTaskWorkflow(runtimeRecord("w-active", "2026-08-06T10:00:00Z"), PROSE)
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-active", identity))
    val database =
      TrackingDatabase(
        work = listOf(workItem("w-active", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
        workflows = workflows,
      )

    val result =
      ideStatusService(database).status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
    assertEquals("No recent Skill Bill work for branch 'feat/OTHER-9-unrelated'.", result.snapshot.summary)
  }

  @Test
  fun `feature task registered before its branch was created stays selected on its issue branch`() {
    val fixture = gitRepoFixture("ide-status-runtime-registered-on-main", branch = "feat/SKILL-148-fixture")
    val identity = testGoalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureTaskWorkflow(runtimeRecord("w-active", "2026-08-06T10:00:00Z"), PROSE)
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-active", identity))
    val work = listOf(workItem("w-active", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z"))
    val statuses =
      object : StandalonePhaseStatusRepository by TrackingDatabase(
        work,
        workflows,
      ).unitOfWork().standalonePhaseStatuses {
        override fun latestWorkflowExecution(workflowId: String): IdeStatusWorkflowExecution? =
          IdeStatusWorkflowExecution(
            repositoryIdentity = identity,
            branchCorrelation = "main",
            issueKey = "SKILL-148",
            workflowId = "w-active",
            invocationId = "runtime-invocation",
            executionId = "runtime-execution",
            statusStoreId = "status-store",
            runSequence = "1",
            statusRevision = "1",
            lifecycleState = "active",
            startedAt = ideStatusObservedAt.minusSeconds(600),
            updatedAt = ideStatusObservedAt,
          ).takeIf { workflowId == "w-active" }
      }

    val result =
      ideStatusService(TrackingDatabase(work, workflows, statusRepository = statuses)).status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertNull(result.snapshot.problem)
    assertEquals("w-active", result.snapshot.workflowId)
    assertEquals("runtime-execution", result.snapshot.execution?.executionId)
  }

  @Test
  fun `branch scoping requires a whole issue-key token, not a prefix hit`() {
    val fixture = gitRepoFixture("ide-status-branch-token", branch = "feat/SKILL-14-prefix")
    val identity = testGoalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureTaskWorkflow(runtimeRecord("w-active", "2026-08-06T10:00:00Z"), PROSE)
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-active", identity))
    val database =
      TrackingDatabase(
        work = listOf(workItem("w-active", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
        workflows = workflows,
      )

    val result =
      ideStatusService(database).status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
  }

  @Test
  fun `unresolvable checkout disables branch scoping instead of hiding work`() {
    val fixture = gitRepoFixture("ide-status-branch-detached", branch = null)
    val identity = testGoalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureTaskWorkflow(runtimeRecord("w-active", "2026-08-06T10:00:00Z"), PROSE)
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-active", identity))
    val database =
      TrackingDatabase(
        work = listOf(workItem("w-active", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
        workflows = workflows,
      )

    val result =
      ideStatusService(database).status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals(IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME, result.snapshot.workflowFamily)
    assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
  }

  @Test
  fun `protected base branch disables scoping so pre-branch work stays visible`() {
    val fixture = gitRepoFixture("ide-status-branch-protected", branch = "main")
    val identity = testGoalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureTaskWorkflow(runtimeRecord("w-active", "2026-08-06T10:00:00Z"), PROSE)
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-active", identity))
    val database =
      TrackingDatabase(
        work = listOf(workItem("w-active", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
        workflows = workflows,
      )

    val result =
      ideStatusService(database).status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals(IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME, result.snapshot.workflowFamily)
    assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
  }

  @Test
  fun `recorded goal completion wins over a leftover live lease`() {
    val fixture = gitRepoFixture("ide-status-goal-settled")
    val identity = testGoalRepositoryIdentity(fixture)
    val service =
      ideStatusService(
        goalOnlyDatabase(completionRecorded = true),
        manifestStore =
          StubGoalManifestStore(
            completedGoalManifestState(fixture, identity, goalCompletedAt = ideStatusObservedAt.toString()),
            lease = liveLease(),
          ),
      )

    val result =
      service.status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals(IdeStatusLifecycleState.TERMINAL, result.snapshot.lifecycleState)
    assertEquals("done", result.snapshot.currentStep.id)
    assertEquals("Complete", result.snapshot.currentStep.label)
    assertEquals("Goal SKILL-148 is complete.", result.snapshot.summary)
  }

  @Test
  fun `unfinished finalization stays blocked or failed even when every subtask is complete`() {
    listOf("blocked", "failed").forEach { stuckState ->
      val fixture = gitRepoFixture("ide-status-goal-settled-$stuckState")
      val identity = testGoalRepositoryIdentity(fixture)
      val service =
        ideStatusService(
          goalOnlyDatabase(goalState = stuckState),
          manifestStore =
            StubGoalManifestStore(
              completedGoalManifestState(fixture, identity),
            ),
        )

      val result =
        service.status(
          IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
        )

      val expected = if (stuckState == "blocked") IdeStatusLifecycleState.BLOCKED else IdeStatusLifecycleState.FAILED
      assertEquals(expected, result.snapshot.lifecycleState, stuckState)
      assertFalse(result.snapshot.currentStep.id == "done", stuckState)
    }
  }

  @Test
  fun `completed subtasks without a terminal goal record stay idle without fabricating a fresh monitor`() {
    val fixture = gitRepoFixture("ide-status-finalization-unfinished")
    val identity = testGoalRepositoryIdentity(fixture)
    val result =
      ideStatusService(
        goalOnlyDatabase("running"),
        manifestStore = StubGoalManifestStore(completedGoalManifestState(fixture, identity)),
      ).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))

    assertEquals(IdeStatusLifecycleState.IDLE, result.snapshot.lifecycleState)
    assertEquals(IdeStatusFreshness.STALE, result.snapshot.freshness)
    assertEquals(Instant.parse("2026-08-06T10:00:00Z"), result.snapshot.updatedAt)
    assertNull(result.snapshot.currentActivity)
    assertFalse(result.snapshot.currentStep.id == "monitor")
  }

  @Test
  fun `legacy completed goal remains terminal without a newer completion control marker`() {
    val fixture = gitRepoFixture("ide-status-legacy-completed-goal")
    val identity = testGoalRepositoryIdentity(fixture)
    val result =
      ideStatusService(
        goalOnlyDatabase("completed"),
        manifestStore = StubGoalManifestStore(completedGoalManifestState(fixture, identity)),
      ).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))

    assertEquals(IdeStatusLifecycleState.TERMINAL, result.snapshot.lifecycleState)
    assertEquals("done", result.snapshot.currentStep.id)
    assertEquals("Goal SKILL-148 is complete.", result.snapshot.summary)
  }

  private fun completedGoalManifestState(
    fixture: Path,
    identity: String,
    goalCompletedAt: String? = null,
  ): GoalRunnerManifestState {
    val base = goalManifestState(fixture, identity, childWorkflowId = "w-child")
    return base.copy(
      manifest =
        base.manifest.copy(
          currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "complete"),
          subtasks = base.manifest.subtasks.map { it.copy(status = "complete", lastResumableStep = null) },
        ),
      controlState = base.controlState.copy(goalCompletedAt = goalCompletedAt),
    )
  }

  @Test
  fun `goal current_subtask started_at comes from durable launched child WorkItem`() {
    val fixture = gitRepoFixture("ide-status-goal-subtask-start")
    val identity = testGoalRepositoryIdentity(fixture)
    val childStarted = Instant.parse("2026-08-06T09:15:00Z")
    val database = goalWithLaunchedChildDatabase(identity, childStarted)
    val service =
      ideStatusService(
        database,
        manifestStore =
          StubGoalManifestStore(
            goalManifestState(fixture, identity, childWorkflowId = "w-child"),
          ),
      )

    val result =
      service.status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals("goal-1", result.snapshot.workflowId)
    assertEquals(IdeStatusWorkflowFamily.FEATURE_GOAL, result.snapshot.workflowFamily)
    assertEquals("2", result.snapshot.currentSubtask?.id)
    assertEquals(childStarted, result.snapshot.currentSubtask?.startedAt)
    assertEquals(Instant.parse("2026-08-06T08:00:00Z"), result.snapshot.startedAt)
  }

  @Test
  fun `feature-goal freshness follows the newest same-repo child workflow write`() {
    val fixture = gitRepoFixture("ide-status-goal-freshness")
    val identity = testGoalRepositoryIdentity(fixture)
    val database = goalWithChildWrittenAt(identity, childUpdatedAt = "2026-08-06T11:45:00Z")
    val service = ideStatusService(database)

    val result =
      service.status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals(IdeStatusWorkflowFamily.FEATURE_GOAL, result.snapshot.workflowFamily)
    assertEquals(IdeStatusLifecycleState.IDLE, result.snapshot.lifecycleState)
    assertEquals(Instant.parse("2026-08-06T11:45:00Z"), result.snapshot.updatedAt)
    assertEquals(IdeStatusFreshness.FRESH, result.snapshot.freshness)
  }

  @Test
  fun `child workflow timestamps written by SQLite CURRENT_TIMESTAMP are parsed as UTC`() {
    val fixture = gitRepoFixture("ide-status-sqlite-timestamp")
    val identity = testGoalRepositoryIdentity(fixture)
    val database = goalWithChildWrittenAt(identity, childUpdatedAt = "2026-08-06 11:45:00")
    val service = ideStatusService(database)

    val result =
      service.status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals(Instant.parse("2026-08-06T11:45:00Z"), result.snapshot.updatedAt)
    assertEquals(IdeStatusFreshness.FRESH, result.snapshot.freshness)
  }

  @Test
  fun `feature-goal with no child writes stays anchored to its own durable state`() {
    val fixture = gitRepoFixture("ide-status-goal-no-children")
    val database =
      TrackingDatabase(
        work = listOf(workItem("goal-1", WorkItemKind.FEATURE_GOAL, "running", "2026-08-06T10:00:00Z")),
        workflows = IdeStatusWorkflowStates(),
      )
    val service = ideStatusService(database)

    val result =
      service.status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals(Instant.parse("2026-08-06T10:00:00Z"), result.snapshot.updatedAt)
    assertEquals(IdeStatusFreshness.STALE, result.snapshot.freshness)
  }

  private fun goalWithChildWrittenAt(
    identity: String,
    childUpdatedAt: String,
  ): TrackingDatabase {
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureTaskWorkflow(runtimeRecord("w-child", childUpdatedAt), PROSE)
    workflows.saveFeatureTaskExecutionIdentity(
      identityFor("w-child", identity).copy(routeScope = FeatureTaskRouteScope.GOAL_CHILD),
    )
    val controls =
      object : GoalRunnerControlRepository by EmptyGoalRunnerControlRepository {
        override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
          GoalRunnerControlState(repositoryIdentity = identity)
      }
    return TrackingDatabase(
      work =
        listOf(
          workItem("goal-1", WorkItemKind.FEATURE_GOAL, "running", "2026-08-06T10:00:00Z"),
          workItem("w-child", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z"),
        ),
      workflows = workflows,
      controls = controls,
    )
  }

  private fun goalWithLaunchedChildDatabase(
    identity: String,
    childStarted: Instant,
    childArtifactsJson: String = "{}",
    childCurrentStep: String = "implement",
  ): TrackingDatabase {
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      runtimeRecord("w-child", "2026-08-06T11:00:00Z", currentStep = childCurrentStep)
        .copy(startedAt = childStarted.toString(), artifactsJson = childArtifactsJson),
      PROSE,
    )
    workflows.saveFeatureTaskExecutionIdentity(
      identityFor("w-child", identity).copy(routeScope = FeatureTaskRouteScope.GOAL_CHILD),
    )
    val controls =
      object : GoalRunnerControlRepository by EmptyGoalRunnerControlRepository {
        override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
          GoalRunnerControlState(repositoryIdentity = identity)
      }
    return TrackingDatabase(
      work =
        listOf(
          workItem("goal-1", WorkItemKind.FEATURE_GOAL, "running", "2026-08-06T10:00:00Z"),
          workItem("w-child", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T11:00:00Z")
            .copy(startedAt = childStarted),
        ),
      workflows = workflows,
      controls = controls,
    )
  }

  @Test
  fun `goal mid-planning projects the planning step label and a planning-progress summary`() {
    val fixture = gitRepoFixture("ide-status-goal-planning")
    val identity = testGoalRepositoryIdentity(fixture)
    val service =
      ideStatusService(
        goalOnlyDatabase(),
        manifestStore =
          StubGoalManifestStore(
            goalManifestState(fixture, identity, childWorkflowId = "w-child", lastResumableStep = "plan"),
            planning = planningSnapshot(GoalPlanningStatusState.PARTIALLY_PLANNED),
          ),
      )

    val result =
      service.status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals("planning", result.snapshot.currentStep.id)
    assertEquals("Planning", result.snapshot.currentStep.label)
    assertEquals("Goal SKILL-148 is planning subtasks (1/2 planned).", result.snapshot.summary)
    assertEquals(GoalPlanningStatusState.PARTIALLY_PLANNED, result.snapshot.planning?.state)
    assertEquals("2", result.snapshot.planning?.currentPlanningSubtaskId)
  }
}
