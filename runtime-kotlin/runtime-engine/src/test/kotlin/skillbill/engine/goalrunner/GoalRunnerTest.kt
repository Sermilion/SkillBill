package skillbill.engine.goalrunner

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.application.RecordingSpecScratchStore
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.executionModel
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.specSource
import skillbill.application.testHarnessClock
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.lifecycle.core.ownership
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.runner.InMemoryRuntimeWorkflowRepository
import skillbill.engine.featuretask.runner.RuntimeFakeDatabaseSessionFactory
import skillbill.engine.goalrunner.execution.core.GoalRunnerExecutionCoordinator
import skillbill.engine.goalrunner.execution.core.GoalRunnerOwnedRun
import skillbill.engine.goalrunner.execution.core.GoalRunnerProgressReader
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.SubtaskLaunchRequestArgs
import skillbill.engine.goalrunner.execution.core.goalRunnerDeps
import skillbill.engine.goalrunner.execution.core.testActivityStampWriter
import skillbill.engine.goalrunner.execution.core.testGoalRunner
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.execution.core.testPhaseRecorder
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerManifestStore
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerOutcomeStore
import skillbill.engine.goalrunner.execution.core.testWorktreeEditJournalWriter
import skillbill.engine.goalrunner.execution.support.progressProbe
import skillbill.engine.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.engine.goalrunner.launch.GoalRunnerLaunchReconciler
import skillbill.engine.goalrunner.launch.TestNoopGoalRunnerSubtaskLauncher
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStoreDefaults
import skillbill.engine.goalrunner.model.GoalRunnerAcceptRequest
import skillbill.engine.goalrunner.model.GoalRunnerAcceptResult
import skillbill.engine.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerChildExecutionPlanAdmission
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.model.GoalRunnerCompletionPersistenceResult
import skillbill.engine.goalrunner.model.GoalRunnerEventSink
import skillbill.engine.goalrunner.model.GoalRunnerLaunchAuthorization
import skillbill.engine.goalrunner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerObservabilityLivenessClass
import skillbill.engine.goalrunner.model.GoalRunnerPauseStatus
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerReconcileGate
import skillbill.engine.goalrunner.model.GoalRunnerResetRequest
import skillbill.engine.goalrunner.model.GoalRunnerResumeStatus
import skillbill.engine.goalrunner.model.GoalRunnerRunEvent
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanOptions
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanWriteResult
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.persist.DeadProcessSupervisor
import skillbill.engine.goalrunner.persist.GoalRunnerLedgerContext
import skillbill.engine.goalrunner.persist.GoalRunnerLedgerRecorder
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.LiveProcessSupervisor
import skillbill.engine.goalrunner.planning.outcome.canonicalRepository
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.engine.goalrunner.status.completed
import skillbill.engine.goalrunner.status.supervisionEvent
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilityEmitter
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilitySignal
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilitySubject
import skillbill.engine.goalrunner.telemetry.GoalRunnerProgressEventEmitter
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.goalrunner.GoalObservabilityArtifacts
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalAttemptLedgerEntry
import skillbill.goalrunner.model.GoalObservabilityProgressEvent
import skillbill.goalrunner.model.GoalObservabilityRuntimeEventInput
import skillbill.goalrunner.model.GoalPlanningStatusReasons
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState.BLOCKED
import skillbill.goalrunner.model.GoalPlanningStatusState.NOT_STARTED
import skillbill.goalrunner.model.GoalPlanningStatusState.PARTIALLY_PLANNED
import skillbill.goalrunner.model.GoalPlanningStatusState.PREPARED
import skillbill.goalrunner.model.GoalPlanningStatusState.PREPLANNED
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.GoalRunnerWirePayload
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.planning.cascadeEligiblePlanSubtaskIds
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchDenied
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunProgressEmission
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorizationResult
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState.ACTIVE
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.runner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.WorkflowStateRepositoryDefaults
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperationsTestBase
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshot
import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshotResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.gitops.model.WorkflowPathContentIdentitiesResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.ports.workflow.model.FeatureTaskWorkflowCandidate
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionDependency
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.decomposition.withWorkflowId
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalobservability.GoalObservabilityDiffStat
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.model.goalreview.GoalSubtaskReviewPassResult
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import skillbill.goalrunner.model.GoalPlanningStatusReasons.NOT_STARTED as NOT_STARTED_REASON

class GoalRunnerTest {
  @Test
  fun `sqlite goal runner resume preserves completed subtask state`() {
    val root = Files.createTempDirectory("goal-runner-sqlite-resume")
    val database =
      sqliteDatabaseSessionFactory(
        userHome = root,
        dbPathOverride = root.resolve("runtime.db").toString(),
        environment = emptyMap(),
      )
    val workflowId = "goal-parent-sqlite-resume"
    seedGoalRunnerResumeWorkflow(database, workflowId)
    val manifestStore =
      testWorkflowGoalRunnerManifestStore(
        database = database,
        decompositionManifestStore = TestDecompositionManifestStore,
        clock = testHarnessClock,
      )
    val manifest =
      manifest(1)
        .copy(issueKey = "SKILL-352")
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
    manifestStore.save(
      GoalRunnerManifestState(
        parentWorkflowId = workflowId,
        dbPath = root.resolve("runtime.db").toString(),
        manifest = manifest,
        repoRoot = root,
      ),
    )
    val outcomeStore = testWorkflowGoalRunnerOutcomeStore(database)
    val report =
      testGoalRunner(
        manifestStore = manifestStore,
        subtaskLauncher = TestNoopGoalRunnerSubtaskLauncher,
        outcomeStore = outcomeStore,
        pullRequestPort = RecordingPullRequestPort(),
      ).run(
        GoalRunnerRunRequest(
          issueKey = "SKILL-352",
          repoRoot = root,
          invokedAgentId = "claude",
        ),
      )

    val completed = assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(emptyList(), completed.attemptedSubtasks)
    assertEquals("sha-1", manifestStore.loadByIssueKey("SKILL-352", root)?.manifest?.subtasks?.single()?.commitSha)
  }

  private fun seedGoalRunnerResumeWorkflow(
    database: SQLiteDatabaseSessionFactory,
    workflowId: String,
  ) {
    database.transaction { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskWorkflow(
        WorkflowStateRecord(
          workflowId = workflowId,
          sessionId = "goal-parent-session",
          workflowName = "bill-feature-task",
          contractVersion = "0.1",
          workflowStatus = WorkflowStatus.RUNNING.wireValue,
          currentStepId = "commit_push",
          stepsJson = "[]",
          artifactsJson = "{}",
          startedAt = null,
          updatedAt = null,
          finishedAt = null,
          mode = FeatureTaskWorkflowMode.RUNTIME,
          issueKey = "SKILL-352",
        ),
        FeatureTaskWorkflowMode.RUNTIME,
      )
    }
  }

  @Test
  fun `happy path launches each subtask once and opens one final pr`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    val pr = RecordingPullRequestPort()
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, pr))

    val report = runner.run(runRequest())

    val completed = assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(1, 2), completed.attemptedSubtasks)
    assertEquals(2, completed.subtasksCompleted)
    assertEquals(0, completed.subtasksPending)
    assertEquals(0, completed.subtasksBlocked)
    assertEquals("opened", completed.pullRequestStatus.wireValue)
    assertEquals("https://github.com/canonical/skill-bill/pull/56", completed.pullRequestUrl)
    assertEquals(listOf(1, 2), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals(1, pr.requests.size)
    assertEquals("feat/SKILL-56-goal", pr.requests.single().headBranch)
    assertEquals("complete", store.manifest.status)
    assertEquals(listOf("sha-1", "sha-2"), store.manifest.subtasks.map { it.commitSha })
    assertEquals(listOf(1, 2), store.newChildWorkflowSetups.map { it.subtaskId })
    assertTrue(store.newChildWorkflowSetups.all { it.reviewBaseline.reviewBaseSha == "0".repeat(40) })
  }

  @Test
  fun `done path saves final manifest projection before opening pr`() {
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
      )
    val outcomes = RecordingOutcomeStore()
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = TestNoopGoalRunnerSubtaskLauncher,
          outcomeStore = outcomes,
          pullRequestPort = RecordingPullRequestPort(),
        ),
      )

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(1, store.saveCount)
    assertEquals("complete", store.manifest.status)
    assertEquals("SKILL-56", outcomes.lastReconcileRequest?.issueKey)
    assertEquals(emptySet(), outcomes.lastReconcileRequest?.activeWorkflowIds)

    assertEquals(true, outcomes.lastReconcileRequest?.gate?.requireStalenessEvidence)
  }

  @Test
  fun `goal review summaries are acknowledged only after their event is emitted`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    outcomes.unemittedReviewPasses["wfl-1"] =
      listOf(
        GoalSubtaskReviewPassResult(
          passNumber = 1,
          verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
          reviewResultArtifact = "goal_subtask_review_results.1",
          unresolvedFindingCount = 1,
          findings = listOf(GoalSubtaskReviewCompactFinding("major", "Service", "Missing behavior")),
        ),
      )
    val launcher =
      RecordingSubtaskLauncher { request ->
        store.mutate { current -> current.withWorkflowId(requireNotNull(request.skillRunRequest.subtaskId), "wfl-1") }
        outcomes["wfl-1"] = completeOutcome(1)
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))
    var emittedBeforeAcknowledgement = false

    val report =
      runner.run(
        runRequest().copy(
          eventSink =
            GoalRunnerEventSink { event ->
              if (event is GoalRunnerRunEvent.SubtaskReviewSummary) {
                emittedBeforeAcknowledgement = outcomes.acknowledgedReviewPasses.isEmpty()
              }
            },
        ),
      )

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertTrue(emittedBeforeAcknowledgement)
    assertEquals(listOf("wfl-1" to 1), outcomes.acknowledgedReviewPasses)
  }

  @Test
  fun `forced failure stops on current subtask and does not run later subtasks`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 3))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] =
          if (subtaskId == 2) {
            GoalRunnerStoredOutcome(
              status = GoalRunnerTerminalStatus.FAILED,
              workflowId = "wfl-2",
              blockedReason = "review failed",
              lastResumableStep = "review",
              suppressPr = true,
            )
          } else {
            completeOutcome(subtaskId)
          }
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(listOf(1, 2), stopped.attemptedSubtasks)
    assertEquals(2, stopped.stop.subtaskId)
    assertEquals(GoalRunnerStopReason.FAILED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "review failed")
    assertEquals(CurrentSubtaskIntent(subtaskId = 2, action = "blocked"), store.manifest.currentSubtaskIntent)
    assertEquals("blocked", store.manifest.subtasks.single { it.id == 2 }.status)
    assertEquals("pending", store.manifest.subtasks.single { it.id == 3 }.status)
  }

  @Test
  fun `resume after stop reconciles a terminal child before continuing`() {
    val initial =
      manifest(subtaskCount = 3)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
        .withBlockedSubtask(2, workflowId = "wfl-2", reason = "validation failed")
    val store = InMemoryGoalManifestStore(manifest = initial)
    val outcomes = RecordingOutcomeStore()
    outcomes["wfl-2"] = completeOutcome(2)
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        if (subtaskId == 3) {
          store.mutate { current -> current.withWorkflowId(3, "wfl-3") }
          outcomes["wfl-3"] = completeOutcome(3)
        }
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(3), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals("complete", store.manifest.status)
    assertEquals(listOf("sha-1", "sha-2", "sha-3"), store.manifest.subtasks.map { it.commitSha })
  }

  @Test
  fun `missing terminal workflow-store outcome stops on attempted subtask`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        launchFacts()
      }
    val outcomes = RecordingOutcomeStore()
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(listOf(1), stopped.attemptedSubtasks)
    assertEquals(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, stopped.stop.reason)
    assertEquals(1, stopped.stop.subtaskId)
    assertEquals("wfl-1", stopped.stop.workflowId)
    assertContains(stopped.stop.blockedReason, "without a terminal workflow-store outcome")
    assertContains(stopped.stop.blockedReason, "exited cleanly (status 0)")
    assertContains(stopped.stop.blockedReason, "last_resumable_step")
    assertEquals("blocked", store.manifest.subtasks.single { it.id == 1 }.status)
    assertEquals(listOf("wfl-1"), outcomes.blockedWorkflows.map { it.workflowId })
    assertEquals(1, launcher.requests.size)
    assertEquals(null, launcher.requests.first().skillRunRequest.timeout)
    assertEquals(null, launcher.requests.first().skillRunRequest.progressIdleTimeout)

    outcomes.progresses["wfl-1"] =
      GoalRunnerWorkflowProgress(
        workflowId = "wfl-1",
        workflowStatus = WorkflowStatus.RUNNING,
        currentStepId = "implement",
        progressToken = "child-progress-token",
      )
    val freshProbeToken = requireNotNull(launcher.requests.last().skillRunRequest.progressProbe.progressToken())
    assertContains(freshProbeToken, "wfl-1")
    assertContains(freshProbeToken, "child-progress-token")
    assertEquals("pending", store.manifest.subtasks.single { it.id == 2 }.status)
  }

  @Test
  fun `missing terminal outcome does not retry after the process result`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    var launches = 0
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        launches += 1
        if (launches == 2) {
          outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        }
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, stopped.stop.reason)
    assertEquals(1, launcher.requests.size)
    assertEquals(1, launches)
    assertEquals("blocked", store.manifest.status)
  }

  @Test
  fun `late terminal outcome is not polled after the child process exits`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        launchFacts()
      }
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = launcher,
          outcomeStore = outcomes,
          pullRequestPort = RecordingPullRequestPort(),
        ),
      )

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, stopped.stop.reason)
    assertEquals(1, launcher.requests.size)
  }

  @Test
  fun `an interrupted child result does not trigger a late retry launch`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        launchFacts(AgentRunTermination.Interrupted)
      }
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = launcher,
          outcomeStore = outcomes,
          pullRequestPort = RecordingPullRequestPort(),
        ),
      )

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.INTERRUPTED, stopped.stop.reason)
    assertEquals(1, launcher.requests.size)
  }

  @Test
  fun `missing terminal result does not retry a worker request launch`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    var launches = 0
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        if (subtaskId == 1) {
          launches += 1
          if (launches == 2) {
            outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
          }
          launchFacts(
            stdout =
              workerSubtaskRequestJson(
                name = if (launches == 1) "Stale first follow up" else "Retry follow up",
                specPath =
                  if (launches == 1) {
                    ".feature-specs/SKILL-56-goal/spec_subtask_2_stale_first.md"
                  } else {
                    ".feature-specs/SKILL-56-goal/spec_subtask_2_retry_follow_up.md"
                  },
              ),
          )
        } else {
          outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
          launchFacts()
        }
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, stopped.stop.reason)
    assertEquals(1, launches)
    assertEquals(1, launcher.requests.size)
  }

  @Test
  fun `timed out child workflow is marked blocked`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        launchFacts(AgentRunTermination.TimedOut)
      }
    val outcomes = RecordingOutcomeStore()
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.TIMEOUT, stopped.stop.reason)
    assertEquals("wfl-1", stopped.stop.workflowId)
    assertEquals(listOf("wfl-1"), outcomes.blockedWorkflows.map { it.workflowId })
    assertEquals("blocked", store.manifest.subtasks.single().status)
    assertEquals("implement", store.manifest.subtasks.single().lastResumableStep)
    assertEquals("implement", stopped.stop.lastResumableStep)
  }

  @Test
  fun `same-branch run blocks before launch when feature branch resolves to protected main`() {
    val store =
      InMemoryGoalManifestStore(
        manifest = manifest(subtaskCount = 1).copy(featureBranch = "main"),
      )
    val launcher = RecordingSubtaskLauncher { launchFacts() }
    val outcomes = RecordingOutcomeStore()
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = launcher,
          outcomeStore = outcomes,
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          gitOperations = FixedBranchGitOperations("main"),
        ),
      )

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.POLICY_BLOCKED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "protected branch 'main'")
    assertEquals(emptyList(), launcher.requests)
    assertEquals("blocked", store.manifest.status)
    assertEquals("blocked", store.manifest.currentSubtaskIntent.action)
    assertEquals("blocked", store.manifest.subtasks.single().status)
    assertContains(store.manifest.subtasks.single().blockedReason.orEmpty(), "protected branch")
  }

  @Test
  fun `same-branch goal checks out feature branch from protected current branch before launch`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    val git = RecordingGitOperations(currentBranch = "main")
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = launcher,
          outcomeStore = outcomes,
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          gitOperations = git,
        ),
      )

    val report = runner.run(runRequest())

    assertTrue(report is GoalRunnerRunReport.Completed, report.toString())
    assertEquals(listOf("feat/SKILL-56-goal@main"), git.checkouts)
    assertEquals(listOf(1), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals("complete", store.manifest.status)
  }

  @Test
  fun `same-branch goal retries failed checkout without forwarding create branch to the new child`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val continuation = requireNotNull(request.skillRunRequest.goalContinuation)
        assertNull(continuation.lastResumableStep)
        val workflowId = requireNotNull(continuation.assignedWorkflowId)
        outcomes[workflowId] = completeOutcome(1).copy(workflowId = workflowId)
        launchFacts()
      }
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = launcher,
          outcomeStore = outcomes,
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          gitOperations =
            RecordingGitOperations(
              currentBranch = "main",
              checkoutError = "cannot create feature branch",
            ),
        ),
      )

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.BLOCKED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "cannot create feature branch")
    assertEquals("create_branch", stopped.stop.lastResumableStep)
    assertEquals(emptyList(), launcher.requests)
    assertEquals("blocked", store.manifest.subtasks.single().status)
    assertEquals("create_branch", store.manifest.subtasks.single().lastResumableStep)

    val retryGit = RecordingGitOperations(currentBranch = "main")
    val retryRunner =
      testGoalRunner(
        goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()).copy(gitOperations = retryGit),
      )

    assertIs<GoalRunnerRunReport.Completed>(retryRunner.run(runRequest()))
    assertEquals(listOf("feat/SKILL-56-goal@main"), retryGit.checkouts)
    assertEquals(1, launcher.requests.size)
  }

  @Test
  fun `goal baseline capture failure blocks before opening or launching a child`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val launcher = RecordingSubtaskLauncher { launchFacts() }
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = launcher,
          outcomeStore = RecordingOutcomeStore(),
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          gitOperations =
            RecordingGitOperations(
              currentBranch = "feat/SKILL-56-goal",
              baselineError = "staged tracked changes are present",
            ),
        ),
      )

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertContains(stopped.stop.blockedReason, "staged tracked changes are present")
    assertEquals(emptyList(), launcher.requests)
    assertEquals(emptyList(), store.newChildWorkflowSetups)
    assertNull(store.manifest.subtasks.single().workflowId)
  }

  @Test
  fun `same-branch policy guard does not demote already completed goals`() {
    val completeManifest =
      manifest(subtaskCount = 1)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
        .copy(status = "complete", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "complete"))
    val store = InMemoryGoalManifestStore(manifest = completeManifest.copy(featureBranch = "main"))
    val launcher = RecordingSubtaskLauncher { launchFacts() }
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = launcher,
          outcomeStore = RecordingOutcomeStore(),
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          gitOperations = FixedBranchGitOperations("main"),
        ),
      )

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(emptyList(), launcher.requests)
    assertEquals("complete", store.manifest.status)
  }

  private fun runRequest(): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
    )
}

class GoalRunnerValidationDepthTest {
  @Test
  fun `every non-skipped child stamps full validation depth`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 3))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    assertIs<GoalRunnerRunReport.Completed>(runner.run(runRequest()))

    assertEquals(
      listOf(ValidationDepth.FULL, ValidationDepth.FULL, ValidationDepth.FULL),
      launcher.requests.map { requireNotNull(it.skillRunRequest.goalContinuation).validationDepth },
    )
  }

  @Test
  fun `single-subtask goal stamps full validation depth`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    assertIs<GoalRunnerRunReport.Completed>(runner.run(runRequest()))

    assertEquals(
      ValidationDepth.FULL,
      requireNotNull(launcher.requests.single().skillRunRequest.goalContinuation).validationDepth,
    )
  }

  @Test
  fun `ordinal-last skipped promotes previous last non-skipped to full`() {
    val initial =
      manifest(subtaskCount = 3).copy(
        subtasks =
          manifest(subtaskCount = 3).subtasks.map { subtask ->
            if (subtask.id == 3) subtask.copy(status = "skipped") else subtask
          },
      )
    val store = InMemoryGoalManifestStore(manifest = initial)
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    assertIs<GoalRunnerRunReport.Completed>(runner.run(runRequest()))

    assertEquals(listOf(1, 2), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals(
      listOf(ValidationDepth.FULL, ValidationDepth.FULL),
      launcher.requests.map { requireNotNull(it.skillRunRequest.goalContinuation).validationDepth },
    )
  }

  private fun runRequest(): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
    )
}

class GoalRunnerQualityGateSelectionTest {
  @Test
  fun `three-child goal stamps build build validate`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 3))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    assertIs<GoalRunnerRunReport.Completed>(runner.run(runRequest()))

    assertEquals(
      listOf(
        FeatureTaskRuntimeQualityGateSelection.BUILD,
        FeatureTaskRuntimeQualityGateSelection.BUILD,
        FeatureTaskRuntimeQualityGateSelection.VALIDATE,
      ),
      launcher.requests.map { requireNotNull(it.skillRunRequest.goalContinuation).qualityGateSelection },
    )
  }

  @Test
  fun `single-subtask goal stamps validate`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    assertIs<GoalRunnerRunReport.Completed>(runner.run(runRequest()))

    assertEquals(
      FeatureTaskRuntimeQualityGateSelection.VALIDATE,
      requireNotNull(launcher.requests.single().skillRunRequest.goalContinuation).qualityGateSelection,
    )
  }

  @Test
  fun `ordinal-last skipped promotes validate to previous last non-skipped`() {
    val initial =
      manifest(subtaskCount = 3).copy(
        subtasks =
          manifest(subtaskCount = 3).subtasks.map { subtask ->
            if (subtask.id == 3) subtask.copy(status = "skipped") else subtask
          },
      )
    val store = InMemoryGoalManifestStore(manifest = initial)
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    assertIs<GoalRunnerRunReport.Completed>(runner.run(runRequest()))

    assertEquals(listOf(1, 2), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals(
      listOf(FeatureTaskRuntimeQualityGateSelection.BUILD, FeatureTaskRuntimeQualityGateSelection.VALIDATE),
      launcher.requests.map { requireNotNull(it.skillRunRequest.goalContinuation).qualityGateSelection },
    )
  }

  private fun runRequest(): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
    )
}

class GoalRunnerLinearScratchFinalizeTest {
  @Test
  fun `decomposed linear run deletes each subtask spec after its commit and the dir after the final pr`() {
    val repoRoot = Files.createTempDirectory("goal-linear-cleanup")
    val specDir = repoRoot.resolve(".feature-specs/SKILL-56-goal")
    Files.createDirectories(specDir)
    Files.writeString(specDir.resolve("spec.md"), "# Parent\n")
    val sub1 = specDir.resolve("spec_subtask_1.md").also { Files.writeString(it, "# 1\n") }
    val sub2 = specDir.resolve("spec_subtask_2.md").also { Files.writeString(it, "# 2\n") }
    Files.writeString(specDir.resolve("decomposition-manifest.yaml"), "x")
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2).copy(specSource = SpecSource.LINEAR))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    val scratch = RecordingSpecScratchStore()
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = launcher,
          outcomeStore = outcomes,
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          specScratchStore = scratch,
        ),
      )

    val report = runner.run(linearRunRequest(repoRoot))

    assertIs<GoalRunnerRunReport.Completed>(report)

    assertEquals(listOf(sub1, sub2), scratch.deletedFiles)
    assertEquals(listOf(specDir, specDir), scratch.deletedDirectories)
    assertEquals(listOf(sub1, sub2, specDir, specDir), scratch.deletions)
    assertFalse(Files.exists(specDir), "linear goal scratch dir must be gone on success")
  }

  @Test
  fun `decomposed linear run that stops mid-goal leaves remaining scratch and manifest intact`() {
    val repoRoot = Files.createTempDirectory("goal-linear-abort")
    val specDir = repoRoot.resolve(".feature-specs/SKILL-56-goal")
    Files.createDirectories(specDir)
    val parentSpec = specDir.resolve("spec.md").also { Files.writeString(it, "# Parent\n") }
    val sub1 = specDir.resolve("spec_subtask_1.md").also { Files.writeString(it, "# 1\n") }
    val sub2 = specDir.resolve("spec_subtask_2.md").also { Files.writeString(it, "# 2\n") }
    val manifestFile = specDir.resolve("decomposition-manifest.yaml").also { Files.writeString(it, "x") }
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2).copy(specSource = SpecSource.LINEAR))
    val outcomes = RecordingOutcomeStore()

    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        if (subtaskId == 1) {
          outcomes["wfl-1"] = completeOutcome(1)
        }
        launchFacts()
      }
    val scratch = RecordingSpecScratchStore()
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = launcher,
          outcomeStore = outcomes,
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          specScratchStore = scratch,
        ),
      )

    assertIs<GoalRunnerRunReport.Stopped>(runner.run(linearRunRequest(repoRoot)))

    assertEquals(listOf(sub1), scratch.deletedFiles)
    assertTrue(scratch.deletedDirectories.isEmpty(), "a stopped goal must not delete the scratch dir")
    assertTrue(Files.exists(sub2), "the incomplete subtask spec must survive")
    assertTrue(Files.exists(parentSpec), "the parent spec must survive a stopped goal")
    assertTrue(Files.exists(manifestFile), "the manifest must survive a stopped goal")
  }

  @Test
  fun `local decomposed run deletes nothing`() {
    val repoRoot = Files.createTempDirectory("goal-local-no-delete")
    val specDir = repoRoot.resolve(".feature-specs/SKILL-56-goal")
    Files.createDirectories(specDir)
    Files.writeString(specDir.resolve("spec.md"), "# Parent\n")
    Files.writeString(specDir.resolve("spec_subtask_1.md"), "# 1\n")
    Files.writeString(specDir.resolve("spec_subtask_2.md"), "# 2\n")
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    val scratch = RecordingSpecScratchStore()
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = launcher,
          outcomeStore = outcomes,
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          specScratchStore = scratch,
        ),
      )

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))

    assertTrue(scratch.deletions.isEmpty(), "local mode must not delete any spec scratch")
    assertTrue(Files.exists(specDir.resolve("spec.md")), "local mode keeps the parent spec on disk")
  }

  @Test
  fun `linear finalize deletes scratch before commit-all and completes when remaining dirt is swept`() {
    val repoRoot = Files.createTempDirectory("goal-linear-finalize")
    val scratch = RecordingSpecScratchStore()
    val git =
      CommitAllRecordingGitOperations(
        dirtyPorcelain = " M src/Extra.kt",
        currentBranch = "feat/SKILL-56-goal",
      )
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
            .copy(specSource = SpecSource.LINEAR, executionModel = DecompositionExecutionModel.STACKED_BRANCHES),
      )
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
          outcomeStore = RecordingOutcomeStore(),
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          specScratchStore = scratch,
          gitOperations = git,
        ),
      )

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))

    assertEquals(2, scratch.deletedDirectories.size, "linear scratch must be deleted before commit-all")
    assertEquals(listOf(listOf("src/Extra.kt")), git.stagePathsCalls)
    assertEquals(
      listOf("chore(SKILL-56): goal finalization commit-all on 'feat/SKILL-56-goal'"),
      git.commitMessages,
    )
    assertEquals(0, git.stageAllCalls)
    assertEquals(listOf("feat/SKILL-56-goal"), git.pushedBranches)
  }

  @Test
  fun `local finalize skips commit when only the collapsed feature-specs directory is dirty`() {
    val repoRoot = Files.createTempDirectory("goal-local-finalize-specs-only")
    val git =
      CommitAllRecordingGitOperations(
        dirtyPorcelain = "?? .feature-specs/",
        currentBranch = "feat/SKILL-56-goal",
        unpushedCommits = true,
      )
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
      )
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
          outcomeStore = RecordingOutcomeStore(),
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          specScratchStore = RecordingSpecScratchStore(),
          gitOperations = git,
        ),
      )

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(0, git.stageAllCalls)
    assertTrue(git.stagePathsCalls.isEmpty())
    assertTrue(git.commitMessages.isEmpty())
    assertEquals(listOf("feat/SKILL-56-goal"), git.pushedBranches)
  }

  @Test
  fun `local finalize commit-all stages implementation paths but excludes the manifest`() {
    val repoRoot = Files.createTempDirectory("goal-local-finalize")
    val git =
      CommitAllRecordingGitOperations(
        dirtyPorcelain = " M .feature-specs/SKILL-56-goal/decomposition-manifest.yaml\n M src/Extra.kt",
        currentBranch = "feat/SKILL-56-goal",
      )
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
            .copy(executionModel = DecompositionExecutionModel.STACKED_BRANCHES),
      )
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
          outcomeStore = RecordingOutcomeStore(),
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          specScratchStore = RecordingSpecScratchStore(),
          gitOperations = git,
        ),
      )

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(0, git.stageAllCalls)
    assertEquals(listOf(listOf("src/Extra.kt")), git.stagePathsCalls)
    assertEquals(
      listOf("chore(SKILL-56): goal finalization commit-all on 'feat/SKILL-56-goal'"),
      git.commitMessages,
    )
    assertEquals(listOf("feat/SKILL-56-goal"), git.pushedBranches)
  }

  @Test
  fun `local finalize commit-all ignores leftover collapsed feature-specs dirt`() {
    val repoRoot = Files.createTempDirectory("goal-local-finalize-collapsed-specs")
    val git =
      CommitAllRecordingGitOperations(
        dirtyPorcelain = "?? .feature-specs/\n M src/Extra.kt",
        currentBranch = "feat/SKILL-56-goal",
      )
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
            .copy(executionModel = DecompositionExecutionModel.STACKED_BRANCHES),
      )
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
          outcomeStore = RecordingOutcomeStore(),
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          specScratchStore = RecordingSpecScratchStore(),
          gitOperations = git,
        ),
      )

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(0, git.stageAllCalls)
    assertEquals(listOf(listOf("src/Extra.kt")), git.stagePathsCalls)
    assertEquals(
      listOf("chore(SKILL-56): goal finalization commit-all on 'feat/SKILL-56-goal'"),
      git.commitMessages,
    )
    assertEquals(listOf("feat/SKILL-56-goal"), git.pushedBranches)
  }

  @Test
  fun `finalize ignores spec dirt when porcelain omits the leading dot`() {
    val repoRoot = Files.createTempDirectory("goal-spec-dot-finalize")
    val git =
      CommitAllRecordingGitOperations(
        dirtyPorcelain = " M feature-specs/SKILL-56-goal/decomposition-manifest.yaml",
        currentBranch = "feat/SKILL-56-goal",
      )
    val pullRequests = RecordingPullRequestPort()
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
      )
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
          outcomeStore = RecordingOutcomeStore(),
          pullRequestPort = pullRequests,
        ).copy(
          specScratchStore = RecordingSpecScratchStore(),
          gitOperations = git,
        ),
      )

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))
    assertTrue(git.stagePathsCalls.isEmpty())
    assertTrue(git.commitMessages.isEmpty())
    assertEquals(1, pullRequests.openCount)
  }

  @Test
  fun `finalize continues when commit-all has nothing to commit`() {
    val repoRoot = Files.createTempDirectory("goal-empty-commit-finalize")
    val git =
      CommitAllRecordingGitOperations(
        dirtyPorcelain = " M .feature-specs/SKILL-56-goal/decomposition-manifest.yaml\n M src/Extra.kt",
        currentBranch = "feat/SKILL-56-goal",
        commitResult = WorkflowGitCommitResult.NothingToCommit,
      )
    val pullRequests = RecordingPullRequestPort()
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
            .copy(executionModel = DecompositionExecutionModel.STACKED_BRANCHES),
      )
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
          outcomeStore = RecordingOutcomeStore(),
          pullRequestPort = pullRequests,
        ).copy(
          specScratchStore = RecordingSpecScratchStore(),
          gitOperations = git,
        ),
      )

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(
      listOf("chore(SKILL-56): goal finalization commit-all on 'feat/SKILL-56-goal'"),
      git.commitMessages,
    )
    assertTrue(git.pushedBranches.isEmpty())
    assertEquals(1, pullRequests.openCount)
  }

  @Test
  fun `same-branch finalize blocks leftover implementation paths instead of goal-level commit`() {
    val repoRoot = Files.createTempDirectory("goal-same-branch-finalize-block")
    val git =
      CommitAllRecordingGitOperations(
        dirtyPorcelain = " M src/Extra.kt",
        currentBranch = "feat/SKILL-56-goal",
      )
    val pullRequests = RecordingPullRequestPort()
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
      )
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
          outcomeStore = RecordingOutcomeStore(),
          pullRequestPort = pullRequests,
        ).copy(
          specScratchStore = RecordingSpecScratchStore(),
          gitOperations = git,
        ),
      )

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(GoalRunnerStopReason.PULL_REQUEST_FAILED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "same-branch mode refuses to commit leftover implementation paths")
    assertTrue(git.commitMessages.isEmpty())
    assertEquals(0, pullRequests.openCount)
  }

  @Test
  fun `finalize with a clean worktree skips commit-all and still opens the PR`() {
    val repoRoot = Files.createTempDirectory("goal-clean-finalize")
    val git = CommitAllRecordingGitOperations(dirtyPorcelain = "", currentBranch = "feat/SKILL-56-goal")
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
      )
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
          outcomeStore = RecordingOutcomeStore(),
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          gitOperations = git,
        ),
      )

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(0, git.stageAllCalls)
    assertTrue(git.commitMessages.isEmpty())
    assertTrue(git.pushedBranches.isEmpty())
  }

  @Test
  fun `finalize re-pushes when worktree is clean but local tip is ahead of origin`() {
    val repoRoot = Files.createTempDirectory("goal-unpushed-finalize")
    val git =
      CommitAllRecordingGitOperations(
        dirtyPorcelain = "",
        currentBranch = "feat/SKILL-56-goal",
        unpushedCommits = true,
      )
    val pullRequests = RecordingPullRequestPort()
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
      )
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
          outcomeStore = RecordingOutcomeStore(),
          pullRequestPort = pullRequests,
        ).copy(
          gitOperations = git,
        ),
      )

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(0, git.stageAllCalls, "clean worktree must not stage or commit again")
    assertTrue(git.commitMessages.isEmpty())
    assertEquals(listOf("feat/SKILL-56-goal"), git.pushedBranches)
    assertEquals(1, pullRequests.openCount)
  }

  @Test
  fun `finalize blocks when clean but unpushed tip cannot be pushed`() {
    val repoRoot = Files.createTempDirectory("goal-unpushed-push-fail")
    val git =
      CommitAllRecordingGitOperations(
        dirtyPorcelain = "",
        currentBranch = "feat/SKILL-56-goal",
        unpushedCommits = true,
        pushError = "remote rejected",
      )
    val pullRequests = RecordingPullRequestPort()
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
      )
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
          outcomeStore = RecordingOutcomeStore(),
          pullRequestPort = pullRequests,
        ).copy(
          gitOperations = git,
        ),
      )

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(GoalRunnerStopReason.PULL_REQUEST_FAILED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "unpushed commits")
    assertEquals(listOf("feat/SKILL-56-goal"), git.pushedBranches)
    assertEquals(0, pullRequests.openCount, "must not open a PR from a stale remote tip")
  }

  @Test
  fun `finalize commit-all blocks when the worktree is not on the feature branch`() {
    val repoRoot = Files.createTempDirectory("goal-wrong-branch-finalize")
    val git =
      CommitAllRecordingGitOperations(
        dirtyPorcelain = " M leftover.kt",
        currentBranch = "feat/other-branch",
      )
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
            .copy(executionModel = DecompositionExecutionModel.STACKED_BRANCHES),
      )
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
          outcomeStore = RecordingOutcomeStore(),
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          gitOperations = git,
        ),
      )

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(GoalRunnerStopReason.PULL_REQUEST_FAILED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "requires checkout of feature branch")
    assertEquals(0, git.stageAllCalls)
  }

  @Test
  fun `finalize commit-all blocks when the feature branch is protected`() {
    val repoRoot = Files.createTempDirectory("goal-protected-finalize")
    val git = CommitAllRecordingGitOperations(dirtyPorcelain = " M leftover.kt", currentBranch = "main")
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
            .copy(featureBranch = "main", executionModel = DecompositionExecutionModel.STACKED_BRANCHES),
      )
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
          outcomeStore = RecordingOutcomeStore(),
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          gitOperations = git,
        ),
      )

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(GoalRunnerStopReason.PULL_REQUEST_FAILED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "refuses protected branch")
    assertEquals(0, git.stageAllCalls)
  }

  private fun linearRunRequest(repoRoot: Path): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = repoRoot,
      invokedAgentId = "claude",
    )
}

class GoalRunnerReviewPolicyPersistenceTest {
  @Test
  fun `resume without add-ons carries the durable selection into child policy and continuation`() {
    val addOn =
      PersistedAgentAddonSelectionEntry(
        slug = "goal-context",
        sourceIdentity = "/tmp/skillbill-goal-runner/goal-context/agent-addon.yaml",
        contentSha256 = "a".repeat(64),
      )
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    store.persistReviewPolicy(
      parentWorkflowId = "wfl-parent",
      policy =
        GoalRunnerReviewPolicy(
          codeReviewMode = CodeReviewExecutionMode.DEFAULT,
          agentAddonSelection = AgentAddonSelection(listOf(addOn)),
        ),
    )
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        store.mutate { current -> current.withWorkflowId(1, "wfl-1") }
        outcomes["wfl-1"] = completeOutcome(1)
        launchFacts()
      }

    testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort())).run(runRequest())

    assertEquals(
      AgentAddonSelection(listOf(addOn)),
      store.newChildWorkflowSetups.single().reviewPolicy.agentAddonSelection,
    )
    assertEquals(
      AgentAddonSelection(listOf(addOn)),
      launcher.requests.single().skillRunRequest.goalContinuation?.agentAddonSelection,
    )
  }

  private fun runRequest(): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
    )
}

class GoalRunnerPauseLaunchBoundaryTest {
  @Test
  fun `targeted completion records the pause boundary before a dependent launch`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest().copy(stopAfterSubtaskId = 1))

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.PAUSED, stopped.stop.reason)
    assertEquals(listOf(1), launcher.requests.mapNotNull { it.skillRunRequest.subtaskId })
    assertEquals(1, store.boundaryTransitionCount)
    assertTrue(store.controlState.paused)
    assertTrue(store.controlState.stopAfterConsumed)
    assertEquals("complete", store.manifest.subtasks.single { it.id == 1 }.status)
    assertEquals("pending", store.manifest.subtasks.single { it.id == 2 }.status)
  }

  @Test
  fun `a completed target is reconciled to pause before selecting its dependent`() {
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 2)
            .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
      )
    val launcher = RecordingSubtaskLauncher { launchFacts() }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, RecordingOutcomeStore(), RecordingPullRequestPort()))

    val report = runner.run(runRequest().copy(stopAfterSubtaskId = 1))

    assertEquals(GoalRunnerStopReason.PAUSED, assertIs<GoalRunnerRunReport.Stopped>(report).stop.reason)
    assertTrue(launcher.requests.isEmpty())
    assertTrue(store.controlState.paused)
    assertTrue(store.controlState.stopAfterConsumed)
    assertEquals("pending", store.manifest.subtasks.single { it.id == 2 }.status)
  }

  @Test
  fun `an operator pause racing child completion is consumed once before the next launch`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.requestPauseForTest()
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    assertEquals(GoalRunnerStopReason.PAUSED, assertIs<GoalRunnerRunReport.Stopped>(report).stop.reason)
    assertEquals(listOf(1), launcher.requests.mapNotNull { it.skillRunRequest.subtaskId })
    assertTrue(store.controlState.paused)
    assertTrue(store.controlState.pauseConsumed)
    assertEquals("operator_request", store.controlState.pauseReason)
    assertEquals("pending", store.manifest.subtasks.single { it.id == 2 }.status)
  }

  @Test
  fun `pause committed at launch authorization denies the dependent child launch`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    store.beforeLaunchAuthorization = { subtaskId ->
      if (subtaskId == 2) store.requestPauseForTest()
    }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    assertEquals(GoalRunnerStopReason.PAUSED, assertIs<GoalRunnerRunReport.Stopped>(report).stop.reason)
    assertEquals(listOf(1), launcher.requests.mapNotNull { it.skillRunRequest.subtaskId })
    assertTrue(store.controlState.paused)
    assertTrue(store.controlState.pauseConsumed)
    assertEquals("operator_request", store.controlState.pauseReason)
    assertEquals("pending", store.manifest.subtasks.single { it.id == 2 }.status)
  }

  @Test
  fun `an explicit launch clears a pause request that never reached a boundary`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    store.requestPauseForTest()
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(1), launcher.requests.mapNotNull { it.skillRunRequest.subtaskId })
    assertFalse(store.controlState.pauseRequested)
    assertFalse(store.controlState.paused)
    assertEquals(null, store.controlState.pauseReason)
  }

  @Test
  fun `an already-running execution lease reports a blocked stop without entering the goal body`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val launcher = RecordingSubtaskLauncher { launchFacts() }
    val reason = "Goal parent 'parent' cannot start: another goal runner process is live"
    val alreadyRunning =
      object : GoalRunnerExecutionCoordinator {
        override fun <T> runOwned(
          parentWorkflowId: String,
          block: () -> T,
        ): GoalRunnerOwnedRun<T> = GoalRunnerOwnedRun.AlreadyRunning(reason)

        override fun <T> runOwnedWithChildAdmission(
          parentWorkflowId: String,
          childAdmission: GoalRunnerChildExecutionPlanAdmission,
          block: () -> T,
        ): GoalRunnerOwnedRun<T> = GoalRunnerOwnedRun.AlreadyRunning(reason)
      }
    val runner =
      testGoalRunner(
        goalRunnerDeps(store, launcher, RecordingOutcomeStore(), RecordingPullRequestPort())
          .copy(executionCoordinator = alreadyRunning),
      )

    val report = runner.run(runRequest())

    val stop = assertIs<GoalRunnerRunReport.Stopped>(report).stop
    assertEquals(GoalRunnerStopReason.BLOCKED, stop.reason)
    assertEquals(reason, stop.blockedReason)
    assertEquals("plan", stop.lastResumableStep)
    assertTrue(launcher.requests.isEmpty())
  }

  private fun runRequest(): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
    )
}

class GoalRunnerHandoffTest {
  @Test
  fun `completed subtask does not dirty projection before next review baseline`() {
    var projectionDirty = false
    val store =
      InMemoryGoalManifestStore(
        manifest = manifest(subtaskCount = 2),
        projectionSaved = { projectionDirty = true },
      )
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        projectionDirty = false
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = launcher,
          outcomeStore = outcomes,
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          gitOperations = RecordingGitOperations(currentBranch = "feat/SKILL-56-goal"),
        ),
      )

    val report =
      runner.run(
        GoalRunnerRunRequest(
          issueKey = "SKILL-56",
          repoRoot = Path.of("/tmp/skillbill-goal-runner"),
          invokedAgentId = "claude",
        ),
      )

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(1, 2), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals(2, store.runtimeStateSaveCount)
  }
}

class GoalRunnerRepositoryPathTest {
  @Test
  fun `absolute spec path through a repository alias stays inside the canonical repository`() {
    val canonicalRepository = Files.createTempDirectory("skillbill-goal-canonical")
    val repositoryAlias = Files.createTempDirectory("skillbill-goal-alias-parent").resolve("repository")
    Files.createSymbolicLink(repositoryAlias, canonicalRepository)
    val aliasedSpec = repositoryAlias.resolve(".feature-specs/SKILL-56-goal/spec_subtask_1.md")
    Files.createDirectories(aliasedSpec.parent)
    Files.writeString(aliasedSpec, "# Subtask 1")
    val manifest = manifest(subtaskCount = 1)
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest.copy(
            subtasks = listOf(manifest.subtasks.single().copy(specPath = aliasedSpec.toString())),
          ),
      )
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher {
        store.mutate { current -> current.withWorkflowId(1, "wfl-1") }
        outcomes["wfl-1"] = completeOutcome(1)
        launchFacts()
      }

    val report =
      testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort())).run(
        GoalRunnerRunRequest(
          issueKey = "SKILL-56",
          repoRoot = repositoryAlias,
          invokedAgentId = "claude",
        ),
      )

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(
      ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
      store.newChildWorkflowSetups.single().governedSpecPath,
    )
  }
}

class GoalRunnerNoTerminalOutcomeDiagnosisTest {
  @Test
  fun `non-zero child exit reports exit status and stderr tail without retry`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        agentRunLaunchFacts(
          agent = SupportedAgent.CLAUDE,
          termination = AgentRunTermination.Exited(1),
          stdout = "diagnostic only",
          stderr = "Error: usage limit reached before persisting terminal outcome",
        )
      }
    val outcomes = RecordingOutcomeStore()
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "exited with status 1")
    assertContains(stopped.stop.blockedReason, "Child stderr (head+tail):")
    assertContains(stopped.stop.blockedReason, "usage limit reached before persisting terminal outcome")
    assertEquals(1, launcher.requests.size)
  }

  @Test
  fun `oversized child stderr keeps exception head and recent tail`() {
    val head = "java.lang.IllegalStateException: SQLITE_BUSY: database is locked"
    val tail = "at skillbill.cli.core.MainKt.main(Main.kt:12)"
    val stderr = head + "X".repeat(GoalRunnerLaunchFacts.STDERR_EXCERPT_MAX_CHARS * 2) + tail
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        agentRunLaunchFacts(
          agent = SupportedAgent.CLAUDE,
          termination = AgentRunTermination.Exited(1),
          stdout = "diagnostic only",
          stderr = stderr,
        )
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, RecordingOutcomeStore(), RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertContains(stopped.stop.blockedReason, head)
    assertContains(stopped.stop.blockedReason, tail)
    assertContains(stopped.stop.blockedReason, "chars omitted")
  }

  private fun runRequest(): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
    )
}

private class CommitAllRecordingGitOperations(
  private val dirtyPorcelain: String,
  private val currentBranch: String,
  private val unpushedCommits: Boolean = false,
  private val pushError: String? = null,
  private val commitResult: WorkflowGitCommitResult? = null,
) : GoalReviewReadyGitOperations() {
  var stageAllCalls: Int = 0
  val stagePathsCalls: MutableList<List<String>> = mutableListOf()
  val commitMessages: MutableList<String> = mutableListOf()
  val pushedBranches: MutableList<String> = mutableListOf()
  private var porcelain: String = dirtyPorcelain

  override fun checkoutBranch(
    repoRoot: Path,
    branch: String,
    baseBranch: String?,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = branch)

  override fun branchExists(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "true")

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = currentBranch)

  override fun stageAll(repoRoot: Path): WorkflowGitOperationResult {
    stageAllCalls += 1
    return WorkflowGitOperationResult.Ok(value = "")
  }

  override fun stagePaths(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitOperationResult {
    stagePathsCalls += paths
    return WorkflowGitOperationResult.Ok(value = "")
  }

  override fun captureIndexState(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitIndexSnapshotResult = WorkflowGitIndexSnapshotResult.Captured(WorkflowGitIndexSnapshot.EMPTY)

  override fun restoreIndexState(
    repoRoot: Path,
    paths: List<String>,
    snapshot: WorkflowGitIndexSnapshot,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "")

  override fun stagedPaths(repoRoot: Path): WorkflowGitNameListResult = WorkflowGitNameListResult.Listed(emptyList())

  override fun pathContentIdentities(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowPathContentIdentitiesResult = WorkflowPathContentIdentitiesResult.Resolved(emptyMap())

  override fun createCommit(
    repoRoot: Path,
    message: String,
  ): WorkflowGitCommitResult {
    commitMessages += message
    porcelain =
      porcelain.lineSequence()
        .filter { line ->
          line.length >= 4 &&
            line.substring(3).substringAfterLast(" -> ").trim().trimEnd('/').let { path ->
              path == ".feature-specs" || path.startsWith(".feature-specs/")
            }
        }
        .joinToString("\n")
    return commitResult ?: WorkflowGitCommitResult.Committed(commitSha = "sha-finalize")
  }

  override fun pushBranch(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult {
    pushedBranches += branch
    return pushError?.let { WorkflowGitOperationResult.Failed(error = it) }
      ?: WorkflowGitOperationResult.Ok(value = branch)
  }

  override fun localBranchHasUnpushedCommits(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = if (unpushedCommits) "true" else "false")

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = "sha-finalize")

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = porcelain)

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult =
    WorkflowWorktreeActivityResult(status = WorkflowGitOperationStatus.OK)

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = WorkflowSelectedDiffHunksResult(status = WorkflowGitOperationStatus.OK)
}

class GoalRunnerStatusProjectionTest {
  @Test
  fun `execution liveness uses process inspect when the runtime worker lease is expired`() {
    val harness = GoalStatusPhaseLedgerHarness()
    harness.openRuntimeWorkflow("wfl-live")
    harness.seedOwnership("wfl-live", expiresAt = "2026-07-27T12:00:01Z")
    val service = statusServiceForLiveness(harness, "wfl-live", LiveProcessSupervisor)

    val live = requireNotNull(service.status(goalStatusRequest()))
    assertEquals(ExecutionLiveness.LIVE, live.executionLiveness)

    harness.seedOwnership("wfl-live", expiresAt = "2026-07-27T12:00:00Z")
    val boundary = requireNotNull(service.status(goalStatusRequest()))
    assertEquals(ExecutionLiveness.LIVE, boundary.executionLiveness)
  }

  @Test
  fun `live parent stays live when a SQLITE_BUSY heartbeat leaves an expired lease`() {
    val staleLease =
      GoalRunnerExecutionLease(
        generation = 1,
        ownerToken = "parent-owner",
        hostIdentity = "host",
        bootIdentity = "boot",
        pid = 42,
        processBirthToken = "birth-42",
        heartbeatAt = "2026-07-27T11:59:00Z",
        expiresAt = "2026-07-27T11:59:30Z",
      )
    val store =
      InMemoryGoalManifestStore(manifest(subtaskCount = 1)).apply {
        executionLeaseForTest = staleLease
        failHeartbeatWithSqliteBusy = true
      }

    val heartbeatFailure =
      assertFailsWith<IllegalStateException> {
        store.heartbeatExecutionLease(
          "wfl-parent",
          staleLease.copy(heartbeatAt = Instant.parse("2026-07-27T12:00:00Z")),
        )
      }
    assertTrue(heartbeatFailure.message?.contains("SQLITE_BUSY") == true)
    val status =
      requireNotNull(
        testGoalRunnerStatusService(
          manifestStore = store,
          outcomeStore = RecordingOutcomeStore(),
          phaseRecorder = goalTestPhaseRecorder(),
          clock = Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC),
          ports = GoalRunnerStatusTestPorts(workerSupervisor = LiveProcessSupervisor),
        ).status(goalStatusRequest()),
      )

    assertEquals(ExecutionLiveness.LIVE, status.executionLiveness)
    assertFalse(status.paused)
  }

  @Test
  fun `execution liveness is idle when inspect reports no live owner after lease expiry`() {
    val harness = GoalStatusPhaseLedgerHarness()
    harness.openRuntimeWorkflow("wfl-idle-expired")
    harness.seedOwnership("wfl-idle-expired", expiresAt = "2026-07-27T11:59:59Z")
    val service = statusServiceForLiveness(harness, "wfl-idle-expired", DeadProcessSupervisor)

    val status = requireNotNull(service.status(goalStatusRequest()))

    assertEquals(ExecutionLiveness.IDLE, status.executionLiveness)
  }

  @Test
  fun `execution liveness is idle when a runtime workflow has no ownership row`() {
    val harness = GoalStatusPhaseLedgerHarness()
    harness.openRuntimeWorkflow("wfl-idle")

    val status = requireNotNull(statusServiceForLiveness(harness, "wfl-idle").status(goalStatusRequest()))

    assertEquals(ExecutionLiveness.IDLE, status.executionLiveness)
    assertEquals(0, harness.ownershipWriteCount)
  }

  @Test
  fun `execution liveness is idle when lease or identity is missing and unknown on lease read failure`() {
    val missingLeaseStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1))
    assertEquals(
      ExecutionLiveness.IDLE,
      requireNotNull(
        testGoalRunnerStatusService(
          manifestStore = missingLeaseStore,
          outcomeStore = RecordingOutcomeStore(),
          phaseRecorder = goalTestPhaseRecorder(),
        )
          .status(goalStatusRequest()),
      ).executionLiveness,
    )

    val missingCurrentSubtaskStore =
      InMemoryGoalManifestStore(
        manifest(subtaskCount = 1)
          .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 2, action = "resume")),
      )
    assertEquals(
      ExecutionLiveness.IDLE,
      requireNotNull(
        testGoalRunnerStatusService(
          manifestStore = missingCurrentSubtaskStore,
          outcomeStore = RecordingOutcomeStore(),
          phaseRecorder = goalTestPhaseRecorder(),
        )
          .status(goalStatusRequest()),
      ).executionLiveness,
    )

    val failingHarness = GoalStatusPhaseLedgerHarness()
    failingHarness.openRuntimeWorkflow("wfl-failing")
    failingHarness.failOwnershipReads = true
    assertEquals(
      ExecutionLiveness.UNKNOWN,
      requireNotNull(
        statusServiceForLiveness(failingHarness, "wfl-failing").status(goalStatusRequest()),
      ).executionLiveness,
    )
    assertTrue(
      requireNotNull(
        statusServiceForLiveness(failingHarness, "wfl-failing").status(goalStatusRequest()),
      ).degradedDurableRead,
    )
    assertEquals(0, failingHarness.ownershipWriteCount)
  }

  @Test
  fun `execution liveness uses the parent lease before a child workflow exists`() {
    val store =
      InMemoryGoalManifestStore(manifest(subtaskCount = 1)).apply {
        executionLeaseForTest =
          GoalRunnerExecutionLease(
            generation = 1,
            ownerToken = "parent-owner",
            hostIdentity = "host",
            bootIdentity = "boot",
            pid = 42,
            processBirthToken = "birth-42",
            heartbeatAt = "2026-07-27T11:59:50Z",
            expiresAt = "2026-07-27T12:00:01Z",
          )
      }
    val liveService =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
        clock = Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC),
        ports = GoalRunnerStatusTestPorts(workerSupervisor = LiveProcessSupervisor),
      )

    assertEquals(ExecutionLiveness.LIVE, requireNotNull(liveService.status(goalStatusRequest())).executionLiveness)

    store.executionLeaseForTest = store.executionLeaseForTest!!.copy(expiresAt = Instant.parse("2026-07-27T11:59:59Z"))
    assertEquals(ExecutionLiveness.LIVE, requireNotNull(liveService.status(goalStatusRequest())).executionLiveness)

    val idleService =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
        clock = Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC),
        ports = GoalRunnerStatusTestPorts(workerSupervisor = DeadProcessSupervisor),
      )
    assertEquals(ExecutionLiveness.IDLE, requireNotNull(idleService.status(goalStatusRequest())).executionLiveness)
  }

  @Test
  fun `execution liveness falls back to the parent lease when the child worker lease is idle`() {
    val harness = GoalStatusPhaseLedgerHarness()
    harness.openRuntimeWorkflow("wfl-child-idle")
    harness.seedOwnership("wfl-child-idle", expiresAt = "2026-07-27T11:59:59Z")
    val store =
      InMemoryGoalManifestStore(
        manifest(subtaskCount = 1)
          .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(1, "resume"))
          .withWorkflowId(1, "wfl-child-idle"),
      ).apply {
        executionLeaseForTest =
          GoalRunnerExecutionLease(
            generation = 1,
            ownerToken = "parent-owner",
            hostIdentity = "host",
            bootIdentity = "boot",
            pid = 42,
            processBirthToken = "birth-42",
            heartbeatAt = "2026-07-27T11:59:50Z",
            expiresAt = "2026-07-27T12:00:01Z",
          )
      }
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = harness.recorder,
        clock = Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC),
        ports = GoalRunnerStatusTestPorts(workerSupervisor = ParentLiveChildDeadSupervisor),
      )

    assertEquals(ExecutionLiveness.LIVE, requireNotNull(service.status(goalStatusRequest())).executionLiveness)

    store.executionLeaseForTest = null
    assertEquals(ExecutionLiveness.IDLE, requireNotNull(service.status(goalStatusRequest())).executionLiveness)
  }

  @Test
  fun `status projection includes latest observability and requested diff stat when present`() {
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"))
            .withWorkflowId(1, "wfl-1"),
      )
    val outcomes = RecordingOutcomeStore()
    outcomes.progresses["wfl-1"] =
      GoalRunnerWorkflowProgress(
        workflowId = "wfl-1",
        workflowStatus = WorkflowStatus.RUNNING,
        currentStepId = "implement",
        progressToken = "child-progress-token",
        latestGoalObservabilityEvent =
          GoalObservabilityProgressEvent(
            issueKey = "SKILL-56",
            subtaskId = 1,
            workflowPhase = "implement",
            workerRole = "phase_subagent",
            livenessClass = "durable_progress",
            activitySummary = "editing runtime files",
            sequenceNumber = 42,
            timestamp = "2026-06-01T00:00:00Z",
          ),
      )
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = outcomes,
        phaseRecorder = goalTestPhaseRecorder(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = StatusDiffGitOperations,
          ),
      )

    val status =
      service.status(
        GoalRunnerStatusRequest(
          issueKey = "SKILL-56",
          invokedAgentId = "codex",
          repoRoot = Path.of("."),
          includeDiffStat = true,
        ),
      )

    requireNotNull(status)
    assertEquals("implement", status.latestObservabilityEvent?.workflowPhase)
    assertEquals(42, status.latestObservabilityEvent?.sequenceNumber)
    assertEquals(2, status.requestedDiffStat?.filesChanged)
    assertEquals(5, status.requestedDiffStat?.insertions)
    assertEquals(1, status.requestedDiffStat?.deletions)
  }

  @Test
  fun `status projection reflects terminal child outcome before parent projection catches up`() {
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"))
            .withWorkflowId(1, "wfl-1"),
      )
    val outcomes = RecordingOutcomeStore()
    outcomes["wfl-1"] = completeOutcome(1)
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = outcomes,
        phaseRecorder = goalTestPhaseRecorder(),
      )

    val status =
      service.status(
        GoalRunnerStatusRequest(
          issueKey = "SKILL-56",
          invokedAgentId = "codex",
        ),
      )

    requireNotNull(status)
    assertEquals(1, status.completeCount)
    assertEquals(0, status.pendingCount)
    assertEquals(0, status.blockedCount)
    assertEquals(null, status.currentSubtaskId)
    assertEquals(null, status.currentStep)
    assertEquals("in_progress", store.manifest.status)
    assertEquals("resume", store.manifest.currentSubtaskIntent.action)
    assertEquals("pending", store.manifest.subtasks.single().status)
    assertEquals(0, store.saveCount)
    assertEquals("SKILL-56", outcomes.lastAuthoritativeOutcomeRequest)
    assertEquals(null, outcomes.lastReconcileRequest)
  }

  @Test
  fun `status projection does not persist terminal child reconciliation`() {
    val stored =
      manifest(subtaskCount = 2)
        .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"))
        .withWorkflowId(1, "wfl-1")
    val store = InMemoryGoalManifestStore(stored)
    val outcomes =
      RecordingOutcomeStore().apply {
        authoritativeOutcomesBySubtask[1] = completeOutcome(1).copy(workflowId = "wfl-1")
      }
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = outcomes,
        phaseRecorder = goalTestPhaseRecorder(),
      )

    val status =
      service.status(
        GoalRunnerStatusRequest(
          issueKey = "SKILL-56",
          invokedAgentId = "codex",
        ),
      )

    requireNotNull(status)
    assertEquals(1, status.completeCount)
    assertEquals(1, status.pendingCount)
    assertEquals(stored, store.manifest)
    assertEquals(0, store.saveCount)
  }

  @Test
  fun `status projection prefers authoritative complete child outcome over stale blocked projection`() {
    val staleManifest =
      manifest(subtaskCount = 1)
        .copy(status = "blocked", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "blocked"))
        .withBlockedSubtask(1, workflowId = "wfl-stale", reason = "stale blocked projection")
    val store = InMemoryGoalManifestStore(manifest = staleManifest)
    val outcomes =
      RecordingOutcomeStore().apply {
        authoritativeOutcomesBySubtask[1] = completeOutcome(1).copy(workflowId = "wfl-authoritative")
      }
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = outcomes,
        phaseRecorder = goalTestPhaseRecorder(),
      )

    val status =
      service.status(
        GoalRunnerStatusRequest(
          issueKey = "SKILL-56",
          invokedAgentId = "codex",
        ),
      )

    requireNotNull(status)
    assertEquals(1, status.completeCount)
    assertEquals(0, status.pendingCount)
    assertEquals(0, status.blockedCount)
    assertEquals(null, status.currentSubtaskId)
    assertEquals("blocked", store.manifest.status)
    assertEquals("blocked", store.manifest.currentSubtaskIntent.action)
    val subtask = store.manifest.subtasks.single()
    assertEquals("blocked", subtask.status)
    assertEquals("wfl-stale", subtask.workflowId)
    assertEquals(0, store.saveCount)
  }

  @Test
  fun `status reconciliation preserves completed manifest subtask when child workflow has stale blocked outcome`() {
    val completedManifest =
      manifest(subtaskCount = 1)
        .copy(
          status = "complete",
          currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "complete"),
          subtasks =
            listOf(
              DecompositionSubtask(
                id = 1,
                name = "Subtask 1",
                specPath = ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
                status = "complete",
                workflowId = "wfl-1",
                commitSha = "sha-1",
                lastResumableStep = "commit_push",
              ),
            ),
        )
    val store = InMemoryGoalManifestStore(manifest = completedManifest)
    val outcomes = RecordingOutcomeStore()
    outcomes["wfl-1"] =
      GoalRunnerStoredOutcome(
        status = GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME,
        workflowId = "wfl-1",
        blockedReason = "stale no-terminal outcome",
        lastResumableStep = "review",
        suppressPr = true,
      )
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = outcomes,
        phaseRecorder = goalTestPhaseRecorder(),
      )

    val status =
      service.status(
        GoalRunnerStatusRequest(
          issueKey = "SKILL-56",
          invokedAgentId = "codex",
        ),
      )

    requireNotNull(status)
    assertEquals(1, status.completeCount)
    assertEquals(0, status.pendingCount)
    assertEquals(0, status.blockedCount)
    assertEquals(null, status.currentSubtaskId)
    assertEquals(null, status.currentStep)
    assertEquals("complete", store.manifest.status)
    assertEquals("complete", store.manifest.currentSubtaskIntent.action)
    val subtask = store.manifest.subtasks.single()
    assertEquals("complete", subtask.status)
    assertEquals(null, subtask.blockedReason)
    assertEquals("sha-1", subtask.commitSha)
    assertEquals("commit_push", subtask.lastResumableStep)
  }

  @Test
  fun `status reconciliation preserves active retry when sibling blocked outcome exists`() {
    val activeManifest =
      manifest(subtaskCount = 1)
        .copy(
          status = "in_progress",
          currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
          subtasks =
            listOf(
              DecompositionSubtask(
                id = 1,
                name = "Subtask 1",
                specPath = ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
                status = "in_progress",
                workflowId = "wfl-active",
              ),
            ),
        )
    val store = InMemoryGoalManifestStore(manifest = activeManifest)
    val outcomes =
      RecordingOutcomeStore().apply {
        authoritativeOutcomesBySubtask[1] =
          GoalRunnerStoredOutcome(
            status = GoalRunnerTerminalStatus.BLOCKED,
            workflowId = "wfl-blocked",
            blockedReason = "old sibling blocked",
            lastResumableStep = "review",
            suppressPr = true,
          )
        progresses["wfl-active"] =
          GoalRunnerWorkflowProgress(
            workflowId = "wfl-active",
            workflowStatus = WorkflowStatus.RUNNING,
            currentStepId = "implement",
            progressToken = "tok",
            latestDurableProgressEvent = null,
            latestLivenessSignal = "running",
            lastSnapshotUpdatedAt = "2026-05-30 00:00:00",
          )
      }
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = outcomes,
        phaseRecorder = goalTestPhaseRecorder(),
      )

    val status =
      service.status(
        GoalRunnerStatusRequest(
          issueKey = "SKILL-56",
          invokedAgentId = "codex",
        ),
      )

    requireNotNull(status)
    assertEquals(0, status.completeCount)
    assertEquals(0, status.blockedCount)
    assertEquals(1, status.currentSubtaskId)
    assertEquals("implement", status.currentStep)
    val subtask = store.manifest.subtasks.single()
    assertEquals("in_progress", subtask.status)
    assertEquals("wfl-active", subtask.workflowId)
  }

  @Test
  fun `status projection ignores retained blocked outcome when the same child is running after retry`() {
    val staleManifest =
      manifest(subtaskCount = 1)
        .withBlockedSubtask(1, workflowId = "wfl-active", reason = "pre-retry block")
    val outcomes =
      RecordingOutcomeStore().apply {
        this["wfl-active"] =
          GoalRunnerStoredOutcome(
            status = GoalRunnerTerminalStatus.BLOCKED,
            workflowId = "wfl-active",
            blockedReason = "pre-retry block",
            lastResumableStep = "implement",
            suppressPr = true,
          )
        progresses["wfl-active"] =
          GoalRunnerWorkflowProgress(
            workflowId = "wfl-active",
            workflowStatus = WorkflowStatus.RUNNING,
            currentStepId = "implement",
            progressToken = "retry-token",
            latestDurableProgressEvent = null,
            latestLivenessSignal = "running",
            lastSnapshotUpdatedAt = "2026-07-21 18:02:05",
          )
      }
    val store = InMemoryGoalManifestStore(staleManifest)
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = outcomes,
        phaseRecorder = goalTestPhaseRecorder(),
      )

    val status = service.status(GoalRunnerStatusRequest(issueKey = "SKILL-56", invokedAgentId = "codex"))

    requireNotNull(status)
    assertEquals(0, status.blockedCount)
    assertEquals(1, status.currentSubtaskId)
    assertEquals("implement", status.currentStep)
    assertEquals(staleManifest, store.manifest)
    assertEquals(0, store.saveCount)
  }

  @Test
  fun `status reconciliation preserves reset pending subtask when blocked sibling outcome exists`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes =
      RecordingOutcomeStore().apply {
        authoritativeOutcomesBySubtask[1] =
          GoalRunnerStoredOutcome(
            status = GoalRunnerTerminalStatus.BLOCKED,
            workflowId = "wfl-blocked-before-reset",
            blockedReason = "old sibling blocked",
            lastResumableStep = "review",
            suppressPr = true,
          )
      }
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = outcomes,
        phaseRecorder = goalTestPhaseRecorder(),
      )

    val status =
      service.status(
        GoalRunnerStatusRequest(
          issueKey = "SKILL-56",
          invokedAgentId = "codex",
        ),
      )

    requireNotNull(status)
    assertEquals(1, status.pendingCount)
    assertEquals(0, status.blockedCount)
    val subtask = store.manifest.subtasks.single()
    assertEquals("pending", subtask.status)
    assertEquals(null, subtask.workflowId)
  }

  @Test
  fun `status projects blocked terminal outcome without persisting it`() {
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"))
            .withWorkflowId(1, "wfl-1"),
      )
    val outcomes = RecordingOutcomeStore()
    outcomes["wfl-1"] =
      GoalRunnerStoredOutcome(
        status = GoalRunnerTerminalStatus.FAILED,
        workflowId = "wfl-1",
        blockedReason = "review failed",
        lastResumableStep = "review",
        suppressPr = true,
      )
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = outcomes,
        phaseRecorder = goalTestPhaseRecorder(),
      )

    val status =
      service.status(
        GoalRunnerStatusRequest(
          issueKey = "SKILL-56",
          invokedAgentId = "codex",
        ),
      )

    requireNotNull(status)
    assertEquals(0, status.completeCount)
    assertEquals(0, status.pendingCount)
    assertEquals(1, status.blockedCount)
    assertEquals(1, status.currentSubtaskId)
    assertEquals("review", status.currentStep)
    assertEquals("in_progress", store.manifest.status)
    assertEquals("resume", store.manifest.currentSubtaskIntent.action)
    val subtask = store.manifest.subtasks.single()
    assertEquals("pending", subtask.status)
    assertEquals(null, subtask.blockedReason)
    assertEquals(null, subtask.lastResumableStep)
    assertEquals(0, store.saveCount)
  }

  @Test
  fun `status projection marks blocked terminal child outcome from workflow store when parent is stale`() {
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"))
            .withWorkflowId(1, "wfl-1"),
      )
    val outcomes = RecordingOutcomeStore()
    outcomes["wfl-1"] =
      GoalRunnerStoredOutcome(
        status = GoalRunnerTerminalStatus.BLOCKED,
        workflowId = "wfl-1",
        blockedReason = "preplan blocked",
        lastResumableStep = "preplan",
        suppressPr = true,
      )
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = outcomes,
        phaseRecorder = goalTestPhaseRecorder(),
      )

    val status =
      service.status(
        GoalRunnerStatusRequest(
          issueKey = "SKILL-56",
          invokedAgentId = "codex",
        ),
      )

    requireNotNull(status)
    assertEquals(0, status.completeCount)
    assertEquals(0, status.pendingCount)
    assertEquals(1, status.blockedCount)
    assertEquals(1, status.currentSubtaskId)
    assertEquals("preplan", status.currentStep)
  }

  @Test
  fun `status projection shows pending_launch when current subtask is selected but not yet launched`() {
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 2)
            .copy(
              status = "in_progress",
              currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 2, action = "start"),
              subtasks =
                listOf(
                  DecompositionSubtask(
                    id = 1,
                    name = "Subtask 1",
                    specPath = ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
                    status = "complete",
                    workflowId = "wfl-1",
                    commitSha = "sha-1",
                    lastResumableStep = "commit_push",
                  ),
                  DecompositionSubtask(
                    id = 2,
                    name = "Subtask 2",
                    specPath = ".feature-specs/SKILL-56-goal/spec_subtask_2.md",
                    status = "pending",
                    workflowId = null,
                    lastResumableStep = null,
                  ),
                ),
            ),
      )
    val outcomes = RecordingOutcomeStore()
    outcomes["wfl-1"] = completeOutcome(1)
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = outcomes,
        phaseRecorder = goalTestPhaseRecorder(),
      )

    val status =
      service.status(
        GoalRunnerStatusRequest(
          issueKey = "SKILL-56",
          invokedAgentId = "codex",
        ),
      )

    requireNotNull(status)
    assertEquals(1, status.completeCount)
    assertEquals(2, status.currentSubtaskId)
    assertEquals("pending_launch", status.currentStep)
  }
}

class GoalRunnerPauseStatusTest {
  @Test
  fun `pause is consumed when the goal is stranded before launching a subtask`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
      )

    val result =
      service.pause(
        issueKey = "SKILL-56",
        repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      )

    assertEquals(GoalRunnerPauseStatus.PAUSED, result.status)
    assertTrue(result.paused)
    assertTrue(result.pauseRequested)
    assertTrue(store.controlState.paused)
    assertEquals(1, store.boundaryTransitionCount)
  }

  @Test
  fun `resume clears a pause request that never reached a boundary`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    store.requestPauseForTest()
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
      )

    val result =
      service.resume(
        issueKey = "SKILL-56",
        repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      )

    assertEquals(GoalRunnerResumeStatus.RESUMED, result.status)
    assertEquals("operator_request", result.clearedPauseReason)
    assertFalse(store.controlState.pauseRequested)
    assertFalse(store.controlState.paused)
  }

  @Test
  fun `resume reports not_paused when no pause boundary is durable`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
      )

    val result =
      service.resume(
        issueKey = "SKILL-56",
        repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      )

    assertEquals(GoalRunnerResumeStatus.NOT_PAUSED, result.status)
  }
}

private object ParentLiveChildDeadSupervisor : FeatureTaskRuntimeWorkerSupervisor {
  override fun currentProcess(): FeatureTaskRuntimeProcessIdentity =
    FeatureTaskRuntimeProcessIdentity("host", "boot", 9, "birth-9")

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection =
    if (ownership.workflowId.startsWith("wfl-child")) {
      FeatureTaskRuntimeProcessInspection.NotRunning
    } else {
      FeatureTaskRuntimeProcessInspection.ExactLive
    }

  override fun awaitExit(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    timeout: Duration,
  ) = Unit

  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership) = true

  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership) = true

  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ) = NoopFeatureTaskRuntimeHeartbeat

  override fun pause(durationMillis: Long) = Unit
}

private fun statusServiceForLiveness(
  harness: GoalStatusPhaseLedgerHarness,
  workflowId: String,
  workerSupervisor: FeatureTaskRuntimeWorkerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
): GoalRunnerStatusService =
  testGoalRunnerStatusService(
    manifestStore =
      InMemoryGoalManifestStore(
        manifest(subtaskCount = 1)
          .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(1, "resume"))
          .withWorkflowId(1, workflowId),
      ),
    outcomeStore = RecordingOutcomeStore(),
    phaseRecorder = harness.recorder,
    clock = Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC),
    ports = GoalRunnerStatusTestPorts(workerSupervisor = workerSupervisor),
  )

private fun goalStatusRequest() = GoalRunnerStatusRequest(issueKey = "SKILL-56", invokedAgentId = "codex")

class GoalRunnerObservabilityTest {
  @Test
  fun `runner records lifecycle observability from runtime supervision`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts(stdout = "worker summary")
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    val classes = outcomes.observabilityRecords.map { it.livenessClass }
    assertContains(classes, "subtask_start")
    assertContains(classes, "worker_output_summary")
    assertContains(classes, "completion")
    assertEquals(setOf("wfl-1"), outcomes.observabilityRecords.map { it.workflowId }.toSet())
  }

  @Test
  fun `observability store false does not block terminal completion`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    outcomes.observabilityRecordResult = false
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts(stdout = "worker summary")
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(1, launcher.requests.size)
    assertEquals("complete", store.manifest.status)
  }

  @Test
  fun `observability store exception does not block terminal completion`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    outcomes.throwOnObservabilityRecord = true
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts(stdout = "worker summary")
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(1, launcher.requests.size)
    assertEquals("complete", store.manifest.status)
  }

  @Test
  fun `observability callback propagates cancellation from the outcome store`() {
    val cancellation = CancellationException("observability cancelled")
    val outcomes = RecordingOutcomeStore().apply { observabilityRecordFailure = cancellation }
    val emitter =
      GoalRunnerObservabilityEmitter(
        outcomeStore = outcomes,
        clock = testHarnessClock,
        diagnostics = NoopRuntimeDiagnostics,
      )

    val thrown =
      assertFailsWith<CancellationException> {
        emitter.record(
          GoalRunnerObservabilitySubject("wfl-child", "SKILL-56", 1),
          GoalRunnerObservabilitySignal(
            workflowPhase = "implement",
            livenessClass = GoalRunnerObservabilityLivenessClass.HEARTBEAT,
            activitySummary = "still working",
          ),
        )
      }

    assertSame(cancellation, thrown)
  }

  @Test
  fun `observability callback propagates interruption and restores the interrupt flag`() {
    val outcomes =
      RecordingOutcomeStore().apply {
        observabilityRecordFailure = InterruptedException("observability interrupted")
      }
    val emitter =
      GoalRunnerObservabilityEmitter(
        outcomeStore = outcomes,
        clock = testHarnessClock,
        diagnostics = NoopRuntimeDiagnostics,
      )

    val thrown =
      assertFailsWith<InterruptedException> {
        emitter.record(
          GoalRunnerObservabilitySubject("wfl-child", "SKILL-56", 1),
          GoalRunnerObservabilitySignal(
            workflowPhase = "implement",
            livenessClass = GoalRunnerObservabilityLivenessClass.HEARTBEAT,
            activitySummary = "still working",
          ),
        )
      }

    assertEquals("observability interrupted", thrown.message)
    assertTrue(Thread.interrupted())
  }

  @Test
  fun `observability write reports a bounded diagnostic even when diagnostics fail`() {
    val outcomes =
      RecordingOutcomeStore().apply {
        observabilityRecordFailure = IllegalStateException("x".repeat(2_000))
      }
    val diagnostics = ThrowingDiagnostics()
    val emitter =
      GoalRunnerObservabilityEmitter(
        outcomeStore = outcomes,
        clock = testHarnessClock,
        diagnostics = diagnostics,
      )

    emitter.record(
      GoalRunnerObservabilitySubject("wfl-child", "SKILL-56", 1),
      GoalRunnerObservabilitySignal(
        workflowPhase = "implement",
        livenessClass = GoalRunnerObservabilityLivenessClass.HEARTBEAT,
        activitySummary = "still working",
      ),
    )

    assertTrue(diagnostics.warningMessages.single().length < 600)
  }

  @Test
  fun `observability progress exception does not block terminal completion`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    outcomes.throwOnProgress = true
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts(stdout = "worker summary")
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(1, launcher.requests.size)
    assertEquals("complete", store.manifest.status)
  }

  @Test
  fun `accepted worker subtask request becomes visible sibling work`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        val stdout =
          if (subtaskId == 1) {
            workerSubtaskRequestJson(
              name = "Worker follow up",
              specPath = ".feature-specs/SKILL-56-goal/spec_subtask_2_worker_follow_up.md",
            )
          } else {
            ""
          }
        launchFacts(stdout = stdout)
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    val completed = assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(1, 2), completed.attemptedSubtasks)
    assertEquals(listOf(1, 2), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals("Worker follow up", store.manifest.subtasks.single { it.id == 2 }.name)
    val outcome = outcomes.workerSubtaskRequestOutcomes.single().outcomes.single()
    assertIs<GoalRunnerWorkerSubtaskRequestOutcome.Accepted>(outcome)
  }

  @Test
  fun `accepted worker subtask request is not made visible when audit persistence fails`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    outcomes.workerSubtaskRequestOutcomeRecordResult = false
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts(
          stdout =
            workerSubtaskRequestJson(
              name = "Unaudited follow up",
              specPath = ".feature-specs/SKILL-56-goal/spec_subtask_2_unaudited_follow_up.md",
            ),
        )
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.BLOCKED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "audit could not be recorded")
    assertEquals(1, store.manifest.subtasks.size)
    assertEquals("blocked", store.manifest.subtasks.single().status)
    assertTrue(outcomes.workerSubtaskRequestOutcomes.isEmpty())
  }

  @Test
  fun `confirmation-required worker request blocks without creating hidden child state`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts(
          stdout =
            workerSubtaskRequestJson(
              name = "Needs approval",
              specPath = ".feature-specs/SKILL-56-goal/spec_subtask_2_needs_approval.md",
              requiresOperatorConfirmation = true,
            ),
        )
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.BLOCKED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "operator confirmation")
    assertEquals(1, store.manifest.subtasks.size)
    assertEquals("blocked", store.manifest.subtasks.single().status)
    val outcome = outcomes.workerSubtaskRequestOutcomes.single().outcomes.single()
    assertIs<GoalRunnerWorkerSubtaskRequestOutcome.RequiresOperatorConfirmation>(outcome)
  }

  private fun runRequest(): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
    )
}

class GoalRunnerAcceptResetTest {
  @Test
  fun `accept refuses ordinary out-of-band acceptance even when the subtask is not yet marked blocked`() {
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 2).copy(
            status = "running",
            currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
            subtasks =
              manifest(subtaskCount = 2).subtasks.map { subtask ->
                if (subtask.id == 1) {
                  subtask.copy(status = "pending")
                } else {
                  subtask
                }
              },
          ),
      )
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = AcceptGitOperations(),
          ),
      )

    val result =
      service.accept(
        GoalRunnerAcceptRequest(
          issueKey = "SKILL-56",
          subtaskId = 1,
          commitSha = "abc1234",
          reason = "...",
          repoRoot = Path.of("."),
        ),
      )

    val rejected = assertIs<GoalRunnerAcceptResult.Rejected>(result)
    assertContains(rejected.reason, "Out-of-band accept is disabled")
    assertEquals(emptyList(), store.acceptedParentWorkflowIds)
    assertEquals("pending", store.manifest.subtasks.first { it.id == 1 }.status)
  }

  @Test
  fun `accept refuses a blocked subtask instead of skipping the durable block`() {
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 2).copy(
            status = "blocked",
            currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "blocked"),
            subtasks =
              manifest(subtaskCount = 2).subtasks.map { subtask ->
                if (subtask.id == 1) {
                  subtask.copy(status = "blocked", blockedReason = "session limit", lastResumableStep = "review")
                } else {
                  subtask
                }
              },
          ),
      )
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = AcceptGitOperations(),
          ),
      )

    val result =
      service.accept(
        GoalRunnerAcceptRequest(
          issueKey = "SKILL-56",
          subtaskId = 1,
          commitSha = "abc1234",
          reason = "finished by hand after the runtime blocked",
          repoRoot = Path.of("."),
        ),
      )

    val rejected = assertIs<GoalRunnerAcceptResult.Rejected>(result)
    assertContains(rejected.reason, "Out-of-band accept is disabled")
    assertEquals(emptyList(), store.acceptedParentWorkflowIds)
    assertEquals("blocked", store.manifest.subtasks.first { it.id == 1 }.status)
  }

  @Test
  fun `accept rejects a commit that does not resolve in the repository`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = AcceptGitOperations(),
          ),
      )

    val result =
      service.accept(
        GoalRunnerAcceptRequest(
          issueKey = "SKILL-56",
          subtaskId = 1,
          commitSha = "deadbee",
          reason = "landed by hand",
          repoRoot = Path.of("."),
        ),
      )

    assertIs<GoalRunnerAcceptResult.Rejected>(result)
    assertEquals(emptyList(), store.acceptedParentWorkflowIds)
    assertEquals("pending", store.manifest.subtasks.single().status)
  }

  @Test
  fun `accept rejects a subtask whose dependency is not satisfied`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = AcceptGitOperations(),
          ),
      )

    val result =
      service.accept(
        GoalRunnerAcceptRequest(
          issueKey = "SKILL-56",
          subtaskId = 2,
          commitSha = "abc1234",
          reason = "landed by hand",
          repoRoot = Path.of("."),
        ),
      )

    assertIs<GoalRunnerAcceptResult.Rejected>(result)
    assertEquals(emptyList(), store.acceptedParentWorkflowIds)
  }

  @Test
  fun `status reconciliation keeps an accepted subtask complete across later reads`() {
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 2).copy(
            subtasks =
              manifest(subtaskCount = 2).subtasks.map { subtask ->
                if (subtask.id == 1) subtask.copy(status = "blocked", blockedReason = "session limit") else subtask
              },
          ),
      )

    store.persistOutOfBandAcceptance(
      parentWorkflowId = "wfl-parent",
      acceptance =
        GoalRunnerOutOfBandAcceptance(
          subtaskId = 1,
          commitSha = "abc1234abc1234abc1234abc1234abc1234abcd",
          reason = "landed by hand",
          acceptedAt = "2026-01-01T00:00:00Z",
        ),
    )
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = AcceptGitOperations(),
          ),
      )

    val projection =
      service.status(
        GoalRunnerStatusRequest(issueKey = "SKILL-56", invokedAgentId = "claude", repoRoot = Path.of(".")),
      )

    requireNotNull(projection)
    assertEquals(1, projection.completeCount)
    assertEquals(2, projection.currentSubtaskId)

    val accepted = projection.outOfBandAcceptances.single()
    assertEquals(1, accepted.subtaskId)
    assertEquals("abc1234abc1234abc1234abc1234abc1234abcd", accepted.commitSha)
    assertEquals("landed by hand", accepted.reason)
  }

  @Test
  fun `soft reset preserves child identity and resumable step`() {
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"))
            .withWorkflowId(1, "wfl-active")
            .copy(
              subtasks =
                listOf(
                  manifest(subtaskCount = 1).subtasks.single().copy(
                    status = "blocked",
                    workflowId = "wfl-active",
                    blockedReason = "repair required",
                    lastResumableStep = "implement",
                  ),
                ),
            ),
      )
    val outcomes = RecordingOutcomeStore()
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = outcomes,
        phaseRecorder = goalTestPhaseRecorder(),
      )

    val reset =
      service.reset(
        GoalRunnerResetRequest(
          issueKey = "SKILL-56",
          hard = false,
        ),
      )

    requireNotNull(reset)

    assertEquals(
      ReconcileRequest(
        "SKILL-56",
        emptySet(),
        GoalRunnerReconcileGate(allowInactiveReconciliation = true, requireStalenessEvidence = false),
        null,
      ),
      outcomes.lastReconcileRequest,
    )
    assertEquals("in_progress", store.manifest.subtasks.single().status)
    assertEquals("wfl-active", store.manifest.subtasks.single().workflowId)
    assertEquals("implement", store.manifest.subtasks.single().lastResumableStep)
    assertNull(store.manifest.subtasks.single().blockedReason)
    assertEquals(CurrentSubtaskIntent(subtaskId = 1, action = "resume"), store.manifest.currentSubtaskIntent)
  }

  @Test
  fun `soft reset restarts blocked subtask without child identity`() {
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1).copy(
            status = "blocked",
            currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "blocked"),
            subtasks =
              listOf(
                manifest(subtaskCount = 1).subtasks.single().copy(
                  status = "blocked",
                  blockedReason = "branch setup failed",
                  lastResumableStep = "create_branch",
                ),
              ),
          ),
      )
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
      )

    service.reset(GoalRunnerResetRequest(issueKey = "SKILL-56", hard = false))

    assertEquals("pending", store.manifest.subtasks.single().status)
    assertNull(store.manifest.subtasks.single().workflowId)
    assertNull(store.manifest.subtasks.single().lastResumableStep)
    assertEquals(CurrentSubtaskIntent(subtaskId = 1, action = "start"), store.manifest.currentSubtaskIntent)
  }

  @Test
  fun `scoped recovery resets only selected terminal child`() {
    val original =
      manifest(subtaskCount = 2).copy(
        status = "blocked",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 2, action = "blocked"),
        subtasks =
          manifest(subtaskCount = 2).subtasks.map { subtask ->
            when (subtask.id) {
              1 -> subtask.copy(status = "complete", commitSha = "full-commit-1")
              else ->
                subtask.copy(
                  status = "blocked",
                  workflowId = "wfl-stale",
                  blockedReason = "terminal child",
                  lastResumableStep = "implement",
                )
            }
          },
      )
    val store = InMemoryGoalManifestStore(original)
    val outcomes =
      RecordingOutcomeStore().apply {
        progresses["wfl-stale"] =
          GoalRunnerWorkflowProgress(
            workflowId = "wfl-stale",
            workflowStatus = WorkflowStatus.FAILED,
            currentStepId = "implement",
            progressToken = "terminal",
          )
      }

    val reset =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = outcomes,
        phaseRecorder = goalTestPhaseRecorder(),
      ).reset(
        GoalRunnerResetRequest(
          issueKey = "SKILL-56",
          hard = false,
          subtaskId = 2,
          deleteChildWorkflow = true,
        ),
      )

    requireNotNull(reset)
    assertEquals("scoped_child_recovery", reset.mode)
    assertEquals(original.subtasks.first(), store.manifest.subtasks.first())
    assertEquals("pending", store.manifest.subtasks.last().status)
    assertNull(store.manifest.subtasks.last().workflowId)
    assertEquals(null, outcomes.lastReconcileRequest, "selector validation must not mutate through reconciliation")
  }

  @Test
  fun `scoped recovery rejects resumable child without mutation`() {
    val original =
      manifest(subtaskCount = 1)
        .copy(status = "blocked", currentSubtaskIntent = CurrentSubtaskIntent(1, "blocked"))
        .withBlockedSubtask(1, workflowId = "wfl-resumable", reason = "paused")
    val store = InMemoryGoalManifestStore(original)
    val outcomes =
      RecordingOutcomeStore().apply {
        progresses["wfl-resumable"] =
          GoalRunnerWorkflowProgress(
            workflowId = "wfl-resumable",
            workflowStatus = WorkflowStatus.PAUSED,
            currentStepId = "implement",
            progressToken = "resumable",
          )
      }

    assertFailsWith<IllegalArgumentException> {
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = outcomes,
        phaseRecorder = goalTestPhaseRecorder(),
      ).reset(
        GoalRunnerResetRequest(
          issueKey = "SKILL-56",
          hard = false,
          subtaskId = 1,
          deleteChildWorkflow = true,
        ),
      )
    }

    assertEquals(original, store.manifest)
    assertEquals(0, store.saveCount)
    assertEquals(null, outcomes.lastReconcileRequest)
  }

  @Test
  fun `hard reset deletes goal planning preparation before saving pending projection`() {
    val database = GoalTestPlanningDatabase()
    val store =
      InMemoryGoalManifestStore(
        manifest = manifest(subtaskCount = 2),
        hardReset = { state ->
          database.transaction { unitOfWork ->
            unitOfWork.goalPlanningPreparations.deleteByGoal(state.parentWorkflowId)
            unitOfWork.workflowStates.deleteGoalChildWorkflowsByParent(state.parentWorkflowId)
          }
        },
      )
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
      )

    val reset =
      service.reset(
        GoalRunnerResetRequest(
          issueKey = "SKILL-56",
          hard = true,
          repoRoot = Path.of("/tmp/skillbill-goal-runner"),
        ),
      )

    requireNotNull(reset)
    assertEquals(listOf("wfl-parent"), database.deletedParentGoalIds)
    assertEquals(listOf("wfl-parent"), database.deletedChildWorkflowParentIds)
    assertEquals(listOf("pending", "pending"), store.manifest.subtasks.map(DecompositionSubtask::status))
  }

  @Test
  fun `hard reset lists discarded acceptance and only explicit restoration recreates it`() {
    val acceptedSha = "abc1234abc1234abc1234abc1234abc1234abcd"
    val acceptedAt = "2026-01-01T00:00:00Z"
    val store =
      InMemoryGoalManifestStore(
        manifest(subtaskCount = 2).withBlockedSubtask(
          subtaskId = 1,
          workflowId = "wfl-manual",
          reason = "finished outside runtime",
        ),
      )
    store.persistOutOfBandAcceptance(
      parentWorkflowId = "wfl-parent",
      acceptance = GoalRunnerOutOfBandAcceptance(1, acceptedSha, "reviewed; ship it", acceptedAt),
    )
    val service =
      acceptingStatusService(store, clock = Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC))
    assertEquals(
      listOf(GoalRunnerAcceptedSubtask(1, acceptedSha, "reviewed; ship it", acceptedAt)),
      service.hardResetPreflight("SKILL-56"),
    )
    service.reset(GoalRunnerResetRequest(issueKey = "SKILL-56", hard = true, repoRoot = Path.of(".")))
    assertEquals(emptyList(), service.hardResetPreflight("SKILL-56"))
    assertIs<GoalRunnerAcceptResult.Rejected>(service.accept(acceptRequest(acceptedSha)))
    val restored =
      assertIs<GoalRunnerAcceptResult.Accepted>(
        service.accept(acceptRequest(acceptedSha, restoreAfterHardReset = true)),
      )
    assertEquals("2026-07-27T12:00Z", restored.acceptedAt)
    assertEquals(
      listOf(GoalRunnerAcceptedSubtask(1, acceptedSha, "reviewed; ship it", "2026-07-27T12:00Z")),
      service.hardResetPreflight("SKILL-56"),
    )
  }

  private fun acceptingStatusService(
    store: InMemoryGoalManifestStore,
    clock: Clock = testHarnessClock,
  ) = testGoalRunnerStatusService(
    manifestStore = store,
    outcomeStore = RecordingOutcomeStore(),
    phaseRecorder = goalTestPhaseRecorder(),
    clock = clock,
    ports =
      GoalRunnerStatusTestPorts(
        gitOperations = AcceptGitOperations(),
      ),
  )

  private fun acceptRequest(
    commitSha: String,
    restoreAfterHardReset: Boolean = false,
  ) = GoalRunnerAcceptRequest(
    issueKey = "SKILL-56",
    subtaskId = 1,
    commitSha = commitSha,
    reason = "reviewed; ship it",
    repoRoot = Path.of("."),
    restoreAfterHardReset = restoreAfterHardReset,
  )

  @Test
  fun `hard reset requires repository root for checkpoint ref cleanup`() {
    val service =
      testGoalRunnerStatusService(
        manifestStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1)),
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
      )

    assertFailsWith<IllegalArgumentException> {
      service.reset(GoalRunnerResetRequest(issueKey = "SKILL-56", hard = true))
    }
  }

  private fun runRequest(): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
    )
}

class GoalRunnerManifestReconciliationTest {
  @Test
  fun `goal run reconciles terminal child before selecting the next subtask`() {
    val staleManifest =
      manifest(subtaskCount = 2).copy(
        status = "in_progress",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
        subtasks =
          manifest(subtaskCount = 2).subtasks.map { subtask ->
            if (subtask.id == 1) subtask.copy(status = "pending", workflowId = "wfl-1") else subtask
          },
      )
    val store = InMemoryGoalManifestStore(staleManifest)
    val outcomes =
      RecordingOutcomeStore().apply {
        authoritativeOutcomesBySubtask[1] = completeOutcome(1)
      }
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        assertEquals(2, subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-2") }
        outcomes["wfl-2"] = completeOutcome(2)
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report =
      runner.run(
        GoalRunnerRunRequest(
          issueKey = "SKILL-56",
          repoRoot = Path.of("/tmp/skillbill-goal-runner"),
          invokedAgentId = "claude",
        ),
      )

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(2), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals(listOf(2), store.newChildWorkflowSetups.map { it.subtaskId })
    assertEquals("complete", store.manifest.subtasks.first { it.id == 1 }.status)
    assertEquals("sha-1", store.manifest.subtasks.first { it.id == 1 }.commitSha)
  }
}

internal class InMemoryGoalManifestStore(
  manifest: DecompositionManifest,
  private val hardReset: ((GoalRunnerManifestState) -> Unit)? = null,
  private val projectionSaved: (() -> Unit)? = null,
  initialControlState: GoalRunnerControlState = GoalRunnerControlState(),
) : GoalRunnerManifestStoreDefaults() {
  var manifest: DecompositionManifest = manifest
    private set
  var saveCount: Int = 0
    private set
  var runtimeStateSaveCount: Int = 0
    private set
  var controlState: GoalRunnerControlState = initialControlState
    private set
  var executionLeaseForTest: GoalRunnerExecutionLease? = null
  var failHeartbeatWithSqliteBusy: Boolean = false
  var boundaryTransitionCount: Int = 0
    private set
  var beforeLaunchAuthorization: ((Int) -> Unit)? = null
  private var persistedReviewPolicy: GoalRunnerReviewPolicy? = null
  val newChildWorkflowSetups: MutableList<GoalRunnerChildWorkflowSetup> = mutableListOf()
  val acceptedParentWorkflowIds: MutableList<String> = mutableListOf()
  var acceptances: Map<Int, GoalRunnerOutOfBandAcceptance> = emptyMap()
    private set

  override fun loadByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerManifestState? =
    GoalRunnerManifestState(
      parentWorkflowId = "wfl-parent",
      dbPath = "/tmp/skillbill-goal-runner/metrics.db",
      manifest = manifest,
    ).takeIf { manifest.issueKey == issueKey }

  override fun save(state: GoalRunnerManifestState): GoalRunnerManifestState {
    saveCount += 1
    projectionSaved?.invoke()
    manifest = state.manifest
    return state.copy(manifest = manifest)
  }

  override fun saveRuntimeState(state: GoalRunnerManifestState): GoalRunnerManifestState {
    runtimeStateSaveCount += 1
    manifest = state.manifest
    return state.copy(manifest = manifest)
  }

  override fun controlState(parentWorkflowId: String): GoalRunnerControlState = controlState

  override fun persistControlState(
    parentWorkflowId: String,
    state: GoalRunnerControlState,
  ): GoalRunnerControlState {
    controlState = state
    return controlState
  }

  override fun bindRepositoryIdentity(
    parentWorkflowId: String,
    repositoryIdentity: String,
  ): GoalRunnerControlState {
    controlState = controlState.copy(repositoryIdentity = repositoryIdentity)
    return controlState
  }

  override fun executionLease(parentWorkflowId: String): GoalRunnerExecutionLease? = executionLeaseForTest

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
  ): Boolean = true

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
  ): Boolean {
    if (failHeartbeatWithSqliteBusy) {
      error("SQLITE_BUSY: database is locked")
    }
    return true
  }

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean = true

  override fun requestPause(parentWorkflowId: String): GoalRunnerControlState {
    controlState =
      controlState.copy(
        pauseRequested = true,
        pauseReason = controlState.pauseReason ?: "operator_request",
      )
    return controlState
  }

  override fun authorizeSubtaskLaunch(
    state: GoalRunnerManifestState,
    subtaskId: Int,
  ): GoalRunnerLaunchAuthorization {
    val spawnAuthorization =
      object : AgentRunSpawnAuthorization {
        override fun <T> withAuthorization(spawn: () -> T): AgentRunSpawnAuthorizationResult<T> {
          beforeLaunchAuthorization?.invoke(subtaskId)
          if (controlState.requiresPauseBoundary(state.manifest)) {
            return AgentRunSpawnAuthorizationResult.Denied(controlState.pauseReason)
          }
          return AgentRunSpawnAuthorizationResult.Authorized(spawn())
        }
      }
    return GoalRunnerLaunchAuthorization(
      authorized = !controlState.requiresPauseBoundary(state.manifest),
      controlState = controlState,
      spawnAuthorization = spawnAuthorization,
    )
  }

  override fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy? = persistedReviewPolicy

  override fun persistReviewPolicy(
    parentWorkflowId: String,
    policy: GoalRunnerReviewPolicy,
  ): GoalRunnerReviewPolicy {
    persistedReviewPolicy = policy
    return policy
  }

  override fun persistStopAfterSubtask(
    parentWorkflowId: String,
    subtaskId: Int,
  ): GoalRunnerControlState {
    controlState = controlState.copy(stopAfterSubtaskId = controlState.stopAfterSubtaskId ?: subtaskId)
    return controlState
  }

  fun requestPauseForTest() {
    controlState =
      controlState.copy(
        pauseRequested = true,
        pauseReason = controlState.pauseReason ?: "operator_request",
      )
  }

  override fun pauseAtBoundary(state: GoalRunnerManifestState): GoalRunnerManifestState {
    val targetReached =
      controlState.stopAfterSubtaskId?.let { targetId ->
        state.manifest.subtasks.any { it.id == targetId && it.status == "complete" }
      } == true && !controlState.stopAfterConsumed
    if (!controlState.requiresPauseBoundary(state.manifest)) return state
    controlState =
      when {
        controlState.paused -> controlState.copy(stopAfterConsumed = controlState.stopAfterConsumed || targetReached)
        controlState.pauseRequested ->
          controlState.copy(
            pauseConsumed = true,
            paused = true,
            pauseReason = controlState.pauseReason ?: "operator_request",
            pausedAt = FAKE_PAUSED_AT,
            stopAfterConsumed = controlState.stopAfterConsumed || targetReached,
          )
        else ->
          controlState.copy(
            paused = true,
            pauseReason = "stop_after_subtask",
            pausedAt = FAKE_PAUSED_AT,
            stopAfterConsumed = true,
          )
      }
    boundaryTransitionCount += 1
    return state.copy(controlState = controlState)
  }

  override fun resume(parentWorkflowId: String): GoalRunnerManifestState {
    controlState =
      controlState.copy(
        pauseRequested = false,
        pauseConsumed = false,
        paused = false,
        pauseReason = null,
        pausedAt = null,
      )
    return loadByIssueKey(manifest.issueKey, null)!!.copy(controlState = controlState)
  }

  override fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: Int,
  ): GoalRunnerCompletionPersistenceResult {
    runtimeStateSaveCount += 1
    manifest = state.manifest
    val targetReached = controlState.stopAfterSubtaskId == subtaskId && !controlState.stopAfterConsumed
    val operatorRequested = controlState.pauseRequested && !controlState.pauseConsumed
    val shouldPause = controlState.paused || targetReached || operatorRequested
    if (shouldPause) {
      controlState =
        controlState.copy(
          pauseConsumed = controlState.pauseConsumed || operatorRequested,
          paused = true,
          pauseReason =
            when {
              controlState.paused -> controlState.pauseReason
              operatorRequested -> "operator_request"
              else -> "stop_after_subtask"
            },
          pausedAt = controlState.pausedAt ?: FAKE_PAUSED_AT,
          stopAfterConsumed = controlState.stopAfterConsumed || targetReached,
        )
      boundaryTransitionCount += 1
    }
    return GoalRunnerCompletionPersistenceResult(
      state = state.copy(manifest = manifest, controlState = controlState),
      paused = shouldPause,
    )
  }

  override fun saveHardReset(
    state: GoalRunnerManifestState,
    preservePlanning: Boolean,
  ): GoalRunnerManifestState {
    hardReset?.invoke(state)
    acceptances = emptyMap()
    controlState = GoalRunnerControlState()
    return save(state)
  }

  override fun deleteIncompatibleChildWorkflow(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    workflowId: String,
  ): GoalRunnerManifestState {
    val recovered =
      state.copy(
        manifest =
          state.manifest.copy(
            currentSubtaskIntent = CurrentSubtaskIntent(subtaskId, "start"),
            subtasks =
              state.manifest.subtasks.map { subtask ->
                if (subtask.id == subtaskId && subtask.workflowId == workflowId) {
                  subtask.copy(
                    status = "pending",
                    branch = null,
                    commitSha = null,
                    workflowId = null,
                    blockedReason = null,
                    lastResumableStep = null,
                  )
                } else {
                  subtask
                }
              },
          ),
      )
    return save(recovered)
  }

  var plannedSubtaskIds: MutableSet<Int> = mutableSetOf()
  var sharedPreplanPrepared: Boolean = true
  var sharedPreplanPayloadSha256ForTest: String? = "c".repeat(64)
  var scopedReplanCount: Int = 0
    private set
  var clearedChildSubtaskIdsOnReplan: List<Int> = emptyList()
  var lastIncludeSharedPreplan: Boolean? = null
    private set
  var forceSharedDigestMismatchOnReplan: Boolean = false

  override fun planningStatus(
    parentWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
  ): GoalPlanningStatusSnapshot {
    val plannedIds = plannedSubtaskIds.sorted()
    val firstMissing = orderedSubtaskIds.firstOrNull { it !in plannedIds }
    val state =
      when {
        blockedReason != null -> BLOCKED
        !sharedPreplanPrepared -> NOT_STARTED
        firstMissing == null -> PREPARED
        plannedIds.isEmpty() -> PREPLANNED
        else -> PARTIALLY_PLANNED
      }
    val reason =
      when (state) {
        NOT_STARTED ->
          NOT_STARTED_REASON
        PREPLANNED ->
          GoalPlanningStatusReasons.preplannedResume(requireNotNull(firstMissing))
        PARTIALLY_PLANNED ->
          GoalPlanningStatusReasons.partiallyPlannedResume(requireNotNull(firstMissing))
        BLOCKED -> blockedReason
        PREPARED -> null
      }
    return GoalPlanningStatusSnapshot(
      state = state,
      sharedPreplanPrepared = sharedPreplanPrepared,
      plannedSubtaskCount = plannedIds.size,
      totalSubtaskCount = orderedSubtaskIds.size,
      currentPlanningSubtaskId = blockedSubtaskId ?: firstMissing,
      reason = reason,
    )
  }

  override fun sharedPreplanPayloadSha256(parentWorkflowId: String): String? =
    sharedPreplanPayloadSha256ForTest.takeIf { sharedPreplanPrepared }

  override fun saveScopedReplan(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    options: GoalRunnerScopedReplanOptions,
  ): GoalRunnerScopedReplanWriteResult {
    scopedReplanCount += 1
    lastIncludeSharedPreplan = options.includeSharedPreplan
    val before = plannedSubtaskIds.sorted()
    val sharedBefore = sharedPreplanPrepared
    val cascadedIds: List<Int>
    val deleted: Int
    if (options.includeSharedPreplan) {
      cascadedIds =
        cascadeEligiblePlanSubtaskIds(
          plannedIds = before.filter { it != subtaskId },
          subtasks = state.manifest.subtasks,
        )
      val retained = before.filter { it != subtaskId && it !in cascadedIds }
      if (options.expectedSharedPayloadSha256 != null) {
        if (forceSharedDigestMismatchOnReplan ||
          options.expectedSharedPayloadSha256 != sharedPreplanPayloadSha256ForTest
        ) {
          throw IncompatibleGoalPlanningPreparationRecoveryError(
            state.parentWorkflowId,
            0,
            "shared preplan changed after it was observed for discard",
          )
        }
        sharedPreplanPrepared = false
        if (retained.isEmpty()) {
          plannedSubtaskIds.clear()
          sharedPreplanPayloadSha256ForTest = null
          deleted = if (subtaskId in before) 1 else 0
        } else {
          cascadedIds.forEach { plannedSubtaskIds.remove(it) }
          deleted = if (plannedSubtaskIds.remove(subtaskId)) 1 else 0
        }
      } else {
        cascadedIds.forEach { plannedSubtaskIds.remove(it) }
        deleted = if (plannedSubtaskIds.remove(subtaskId)) 1 else 0
      }
    } else {
      cascadedIds = emptyList()
      deleted = if (plannedSubtaskIds.remove(subtaskId)) 1 else 0
    }
    val saved = save(state)
    return GoalRunnerScopedReplanWriteResult(
      state = saved,
      deletedPlanCount = deleted,
      plannedSubtaskIdsBefore = before,
      plannedSubtaskIdsAfter = plannedSubtaskIds.sorted(),
      sharedPreplanPrepared = sharedPreplanPrepared,
      sharedPreplanPreparedBefore = sharedBefore,
      discardedSharedPreplan = sharedBefore && !sharedPreplanPrepared,
      cascadedPlanSubtaskIds = cascadedIds,
      clearedChildSubtaskIds = clearedChildSubtaskIdsOnReplan,
    )
  }

  override fun saveNewChildWorkflow(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
  ): GoalRunnerManifestState {
    newChildWorkflowSetups += setup
    return save(state)
  }

  override fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance> = acceptances

  override fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance {
    acceptedParentWorkflowIds += parentWorkflowId
    acceptances = acceptances + (acceptance.subtaskId to acceptance)
    return acceptance
  }

  fun mutate(block: (DecompositionManifest) -> DecompositionManifest) {
    manifest = block(manifest)
  }
}

class GoalRunnerLedgerRecorderSeedingTest {
  @Test
  fun `ledger action variants do not inherit launch fields from another action`() {
    val outcomes = RecordingOutcomeStore()
    val recorder = GoalRunnerLedgerRecorder(outcomes, ledgerRunRequest(), testHarnessClock, NoopRuntimeDiagnostics)
    recorder.recordLedgerEntry(
      GoalRunnerLedgerContext.ChildActivation(
        workflowId = "wfl-child",
        issueKey = "SKILL-56",
        subtaskId = 1,
        progress = null,
        launchOutcome =
          agentRunLaunchFacts(
            agent = SupportedAgent.CLAUDE,
            stdout = "",
            stderr = "",
          ),
        diagnosticClass = null,
        recoverableJsonPresent = null,
        nextSafeAction = null,
        causingLoopEntry = null,
        reAttemptCause = null,
      ),
    )
    recorder.recordLedgerEntry(
      GoalRunnerLedgerContext.PolicyBlock(
        workflowId = "wfl-child",
        issueKey = "SKILL-56",
        subtaskId = 1,
        progress = null,
        blockedReason = "policy",
        stopReason = "policy_blocked",
      ),
    )

    val blocked = outcomes.attemptLedgerEntries.last()
    assertNull(blocked.launchOutcome)
    assertNull(blocked.finalReconciledResult)
    assertEquals("policy_block", blocked.action.wireValue)
  }

  @Test
  fun `recorder refuses construction when ledger watermark read fails`() {
    val outcomes = RecordingOutcomeStore().apply { throwOnLedgerWatermarks = true }
    assertFailsWith<IllegalStateException> {
      GoalRunnerLedgerRecorder(outcomes, ledgerRunRequest(), testHarnessClock, NoopRuntimeDiagnostics)
    }
  }

  @Test
  fun `ledger write diagnostic failure does not mask the store failure`() {
    val outcomes =
      RecordingOutcomeStore().apply {
        attemptLedgerRecordFailure = IllegalStateException("x".repeat(2_000))
      }
    val diagnostics = ThrowingDiagnostics()
    val recorder = GoalRunnerLedgerRecorder(outcomes, ledgerRunRequest(), testHarnessClock, diagnostics)
    recorder.recordLedgerEntry(
      GoalRunnerLedgerContext.ChildActivation(
        workflowId = "wfl-child",
        issueKey = "SKILL-56",
        subtaskId = 1,
      ),
    )
    assertTrue(outcomes.attemptLedgerRecords.isEmpty())
    assertTrue(diagnostics.warningMessages.single().length < 600)
  }

  @Test
  fun `ledger write propagates cancellation from the outcome store`() {
    val cancellation = CancellationException("ledger cancelled")
    val outcomes = RecordingOutcomeStore().apply { attemptLedgerRecordFailure = cancellation }
    val recorder = GoalRunnerLedgerRecorder(outcomes, ledgerRunRequest(), testHarnessClock, NoopRuntimeDiagnostics)

    val thrown =
      assertFailsWith<CancellationException> {
        recorder.recordLedgerEntry(
          GoalRunnerLedgerContext.ChildActivation(
            workflowId = "wfl-child",
            issueKey = "SKILL-56",
            subtaskId = 1,
          ),
        )
      }

    assertSame(cancellation, thrown)
  }

  @Test
  fun `ledger write propagates interruption and restores the interrupt flag`() {
    val outcomes =
      RecordingOutcomeStore().apply {
        attemptLedgerRecordFailure = InterruptedException("ledger interrupted")
      }
    val recorder = GoalRunnerLedgerRecorder(outcomes, ledgerRunRequest(), testHarnessClock, NoopRuntimeDiagnostics)

    val thrown =
      assertFailsWith<InterruptedException> {
        recorder.recordLedgerEntry(
          GoalRunnerLedgerContext.ChildActivation(
            workflowId = "wfl-child",
            issueKey = "SKILL-56",
            subtaskId = 1,
          ),
        )
      }

    assertEquals("ledger interrupted", thrown.message)
    assertTrue(Thread.interrupted())
  }

  private fun ledgerRunRequest(): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
    )
}

class GoalRunnerProgressEventEmitterTest {
  @Test
  fun `emitter persists declared operation events into goal_progress run history`() {
    val outcomes = RecordingOutcomeStore()
    val emitter =
      GoalRunnerProgressEventEmitter(
        outcomeStore = outcomes,
        resolveWorkflowId = { "wfl-child" },
        issueKey = "SKILL-56",
        clock = testHarnessClock,
        diagnostics = NoopRuntimeDiagnostics,
      )

    emitter.emit(emission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true))
    emitter.emit(emission(GoalProgressEventKind.OPERATION_HEARTBEAT, processAlive = true))
    emitter.emit(
      emission(
        GoalProgressEventKind.OPERATION_COMPLETED,
        processAlive = false,
        outcome = GoalProgressOutcome.SUCCEEDED,
      ),
    )

    val recorded = outcomes.progressEvents
    assertEquals(3, recorded.size)
    assertEquals("wfl-child", outcomes.progressEventRecords.first().workflowId)
    assertEquals(GoalProgressEventKind.OPERATION_STARTED, recorded[0].eventKind)
    assertEquals(GoalProgressEventKind.OPERATION_HEARTBEAT, recorded[1].eventKind)
    assertEquals(GoalProgressEventKind.OPERATION_COMPLETED, recorded[2].eventKind)
    assertEquals("child_agent_run", recorded[0].operationName)
    assertTrue(recorded[0].expectedLong)
    assertTrue(recorded[0].processAlive)
    assertTrue(!recorded[2].processAlive)
  }

  @Test
  fun `supported progress and observability emissions preserve their wire bytes`() {
    val emissionClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    assertProgressWireBytes(emissionClock)
    assertObservabilityWireBytes(emissionClock)
  }

  private fun assertProgressWireBytes(emissionClock: Clock) {
    val progressOutcomes = RecordingOutcomeStore()
    GoalRunnerProgressEventEmitter(
      outcomeStore = progressOutcomes,
      resolveWorkflowId = { "wfl-child" },
      issueKey = "SKILL-56",
      clock = emissionClock,
      diagnostics = NoopRuntimeDiagnostics,
    ).emit(emission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true))

    val progressBytes = jsonBytes(progressOutcomes.progressEvents.single().toPersistenceWire())
    assertContentEquals(
      (
        """{"contract_version":"0.1","event_kind":"operation_started","workflow_id":"wfl-child",""" +
          """"workflow_phase":"goal_runner_supervision","process_alive":true,"sequence_number":0,""" +
          """"timestamp":"2026-01-01T00:00:00Z","operation_name":"child_agent_run",""" +
          """"operation_kind":"long_child_run","expected_long":true}"""
      )
        .encodeToByteArray(),
      progressBytes,
    )
  }

  private fun assertObservabilityWireBytes(emissionClock: Clock) {
    val observabilityOutcomes = RecordingOutcomeStore()
    GoalRunnerObservabilityEmitter(
      outcomeStore = observabilityOutcomes,
      clock = emissionClock,
      diagnostics = NoopRuntimeDiagnostics,
    ).record(
      GoalRunnerObservabilitySubject("wfl-child", "SKILL-56", 1),
      GoalRunnerObservabilitySignal(
        workflowPhase = "goal_runner_supervision",
        livenessClass = GoalRunnerObservabilityLivenessClass.PHASE_CHANGE,
        activitySummary = "Child workflow is at step goal_runner_supervision.",
      ),
    )

    val observabilityEvent =
      GoalObservabilityArtifacts.patchForRuntimeEvent(
        input =
          GoalObservabilityRuntimeEventInput(
            artifacts = FeatureTaskRuntimeWorkflowArtifactMap.from(emptyMap<String, Any?>()),
            request = observabilityOutcomes.observabilityRecords.single(),
          ),
      ).let {
          patch ->
        (patch as Map<*, *>)[DurableWorkflowArtifactFamily.GOAL_OBSERVABILITY_LATEST_EVENT.label()]
      }
    val observabilityBytes = jsonBytes(observabilityEvent)
    val expected =
      (
        "{\"contract_version\":\"0.2\",\"record_kind\":\"progress\",\"issue_key\":\"SKILL-56\",\"subtask_id\":1," +
          "\"workflow_id\":\"wfl-child\",\"workflow_phase\":\"goal_runner_supervision\"," +
          "\"worker_role\":\"goal_runner_supervisor\",\"liveness_class\":\"phase_change\"," +
          "\"activity_summary\":\"Child workflow is at step goal_runner_supervision.\"," +
          "\"timestamp\":\"2026-01-01T00:00:00Z\",\"sequence_number\":0}"
      ).encodeToByteArray()
    assertContentEquals(expected, observabilityBytes)
  }

  private fun jsonBytes(value: Any?): ByteArray = JsonCodec.valueToJsonString(value).encodeToByteArray()

  @Test
  fun `emitter is a no-op until the child workflow id is known`() {
    val outcomes = RecordingOutcomeStore()
    var workflowId: String? = null
    val emitter =
      GoalRunnerProgressEventEmitter(
        outcomeStore = outcomes,
        resolveWorkflowId = { workflowId },
        issueKey = "SKILL-56",
        clock = testHarnessClock,
        diagnostics = NoopRuntimeDiagnostics,
      )

    emitter.emit(emission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true))
    assertTrue(outcomes.progressEventRecords.isEmpty())

    workflowId = "wfl-child"
    emitter.emit(emission(GoalProgressEventKind.OPERATION_HEARTBEAT, processAlive = true))
    assertEquals(1, outcomes.progressEventRecords.size)
  }

  @Test
  fun `emitter write failure never throws`() {
    val outcomes = RecordingOutcomeStore().apply { throwOnProgressEventRecord = true }
    val emitter =
      GoalRunnerProgressEventEmitter(
        outcomeStore = outcomes,
        resolveWorkflowId = { "wfl-child" },
        issueKey = "SKILL-56",
        clock = testHarnessClock,
        diagnostics = NoopRuntimeDiagnostics,
      )

    emitter.emit(emission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true))
  }

  @Test
  fun `emitter propagates cancellation from the outcome store`() {
    val cancellation = CancellationException("progress cancelled")
    val outcomes = RecordingOutcomeStore().apply { progressEventFailure = cancellation }
    val emitter =
      GoalRunnerProgressEventEmitter(
        outcomeStore = outcomes,
        resolveWorkflowId = { "wfl-child" },
        issueKey = "SKILL-56",
        clock = testHarnessClock,
        diagnostics = NoopRuntimeDiagnostics,
      )

    val thrown =
      assertFailsWith<CancellationException> {
        emitter.emit(emission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true))
      }

    assertSame(cancellation, thrown)
  }

  @Test
  fun `emitter propagates interruption from the outcome store`() {
    val outcomes =
      RecordingOutcomeStore().apply {
        progressEventFailure = InterruptedException("progress interrupted")
      }
    val emitter =
      GoalRunnerProgressEventEmitter(
        outcomeStore = outcomes,
        resolveWorkflowId = { "wfl-child" },
        issueKey = "SKILL-56",
        clock = testHarnessClock,
        diagnostics = NoopRuntimeDiagnostics,
      )

    val thrown =
      assertFailsWith<InterruptedException> {
        emitter.emit(emission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true))
      }

    assertEquals("progress interrupted", thrown.message)
    assertTrue(Thread.interrupted())
  }

  @Test
  fun `emitter write failure reports a bounded diagnostic even when diagnostics fail`() {
    val outcomes =
      RecordingOutcomeStore().apply {
        progressEventFailure = IllegalStateException("x".repeat(2_000))
      }
    val diagnostics = ThrowingDiagnostics()
    val emitter =
      GoalRunnerProgressEventEmitter(
        outcomeStore = outcomes,
        resolveWorkflowId = { "wfl-child" },
        issueKey = "SKILL-56",
        clock = testHarnessClock,
        diagnostics = diagnostics,
      )

    emitter.emit(emission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true))

    assertTrue(diagnostics.warningMessages.single().length < 600)
  }

  @Test
  fun `emitter propagates interruption from workflow identity resolution`() {
    val outcomes = RecordingOutcomeStore()
    val emitter =
      GoalRunnerProgressEventEmitter(
        outcomeStore = outcomes,
        resolveWorkflowId = { throw InterruptedException("interrupted") },
        issueKey = "SKILL-56",
        clock = testHarnessClock,
        diagnostics = NoopRuntimeDiagnostics,
      )

    val thrown =
      assertFailsWith<InterruptedException> {
        emitter.emit(emission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true))
      }
    assertEquals("interrupted", thrown.message)
    assertTrue(Thread.interrupted())
    Thread.interrupted()
    assertTrue(outcomes.progressEventRecords.isEmpty())
  }

  private fun emission(
    kind: GoalProgressEventKind,
    processAlive: Boolean,
    outcome: GoalProgressOutcome = GoalProgressOutcome.NONE,
  ): AgentRunProgressEmission =
    AgentRunProgressEmission(
      eventKind = kind,
      processAlive = processAlive,
      operationName = "child_agent_run",
      operationKind = "long_child_run",
      expectedLong = true,
      outcome = outcome,
    )
}

class GoalRunnerLaunchReconcilerWiringTest {
  @Test
  fun `reconciler threads an emitter that persists declared events for the resolved workflow`() {
    val store =
      InMemoryGoalManifestStore(
        manifest = manifest(subtaskCount = 1).withWorkflowId(subtaskId = 1, workflowId = "wfl-1"),
      )
    val outcomes = RecordingOutcomeStore()
    val reconciler =
      GoalRunnerLaunchReconciler(
        manifestStore = store,
        outcomeStore = outcomes,
        progressReader = GoalRunnerProgressReader(outcomes),
        activityStampWriter = testActivityStampWriter(),
        worktreeEditJournalWriter = testWorktreeEditJournalWriter(),
        clock = testHarnessClock,
        diagnostics = NoopRuntimeDiagnostics,
      )

    val launchRequest =
      reconciler.subtaskLaunchRequest(
        SubtaskLaunchRequestArgs(
          issueKey = "SKILL-56",
          subtaskId = 1,
          request = wiringRunRequest(),
          assignedWorkflowId = null,
          reviewBaseline = null,
          spawnAuthorization = null,
        ),
      )

    val emitter = launchRequest.skillRunRequest.progressEmitter
    emitter.emit(supervisorEmission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true))
    emitter.emit(supervisorEmission(GoalProgressEventKind.OPERATION_HEARTBEAT, processAlive = true))
    emitter.emit(
      supervisorEmission(
        GoalProgressEventKind.OPERATION_COMPLETED,
        processAlive = false,
        outcome = GoalProgressOutcome.SUCCEEDED,
      ),
    )

    val recorded = outcomes.progressEventRecords
    assertEquals(3, recorded.size, "wired emitter must persist every declared event via recordProgressEvent")

    assertTrue(recorded.all { it.workflowId == "wfl-1" })
    assertTrue(recorded.all { it.draft.workflowId == "wfl-1" })

    assertEquals(GoalProgressEventKind.OPERATION_STARTED, recorded[0].draft.eventKind)
    assertEquals(GoalProgressEventKind.OPERATION_COMPLETED, recorded[2].draft.eventKind)
    assertEquals(GoalProgressOutcome.SUCCEEDED, recorded[2].draft.outcome)
  }

  @Test
  fun `first-run subtask with pre-assigned id records started and heartbeat through a long quiet phase`() {
    val store =
      InMemoryGoalManifestStore(
        manifest = manifest(subtaskCount = 1).withWorkflowId(subtaskId = 1, workflowId = "wftr-pre-assigned"),
      )
    val outcomes = RecordingOutcomeStore()
    val reconciler =
      GoalRunnerLaunchReconciler(
        manifestStore = store,
        outcomeStore = outcomes,
        progressReader = GoalRunnerProgressReader(outcomes),
        activityStampWriter = testActivityStampWriter(),
        worktreeEditJournalWriter = testWorktreeEditJournalWriter(),
        clock = testHarnessClock,
        diagnostics = NoopRuntimeDiagnostics,
      )

    val launchRequest =
      reconciler.subtaskLaunchRequest(
        SubtaskLaunchRequestArgs(
          issueKey = "SKILL-56",
          subtaskId = 1,
          request = wiringRunRequest(),
          assignedWorkflowId = null,
          reviewBaseline = null,
          spawnAuthorization = null,
        ),
      )
    val emitter = launchRequest.skillRunRequest.progressEmitter
    emitter.emit(supervisorEmission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true))
    emitter.emit(supervisorEmission(GoalProgressEventKind.OPERATION_HEARTBEAT, processAlive = true))

    val recorded = outcomes.progressEventRecords
    assertEquals(2, recorded.size, "pre-assigned id must let the quiet first phase record liveness")
    assertTrue(recorded.all { it.draft.workflowId == "wftr-pre-assigned" })
    assertEquals(
      listOf(GoalProgressEventKind.OPERATION_STARTED, GoalProgressEventKind.OPERATION_HEARTBEAT),
      recorded.map { it.draft.eventKind },
    )
  }

  @Test
  fun `reconciler emitter is a no-op until the child workflow id is resolvable`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val reconciler =
      GoalRunnerLaunchReconciler(
        manifestStore = store,
        outcomeStore = outcomes,
        progressReader = GoalRunnerProgressReader(outcomes),
        activityStampWriter = testActivityStampWriter(),
        worktreeEditJournalWriter = testWorktreeEditJournalWriter(),
        clock = testHarnessClock,
        diagnostics = NoopRuntimeDiagnostics,
      )

    val launchRequest =
      reconciler.subtaskLaunchRequest(
        SubtaskLaunchRequestArgs(
          issueKey = "SKILL-56",
          subtaskId = 1,
          request = wiringRunRequest(),
          assignedWorkflowId = null,
          reviewBaseline = null,
          spawnAuthorization = null,
        ),
      )
    launchRequest.skillRunRequest.progressEmitter.emit(
      supervisorEmission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true),
    )

    assertTrue(outcomes.progressEventRecords.isEmpty())
  }

  @Test
  fun `reconciler progress identity resolution propagates interruption`() {
    val store =
      InMemoryGoalManifestStore(
        manifest = manifest(subtaskCount = 1).withWorkflowId(subtaskId = 1, workflowId = "wfl-1"),
      )
    val interruption = InterruptedException("progress interrupted")
    val outcomes = RecordingOutcomeStore().apply { progressFailure = interruption }
    val reconciler =
      GoalRunnerLaunchReconciler(
        manifestStore = store,
        outcomeStore = outcomes,
        progressReader = GoalRunnerProgressReader(outcomes),
        activityStampWriter = testActivityStampWriter(),
        worktreeEditJournalWriter = testWorktreeEditJournalWriter(),
        clock = testHarnessClock,
        diagnostics = NoopRuntimeDiagnostics,
      )
    val launchRequest =
      reconciler.subtaskLaunchRequest(
        SubtaskLaunchRequestArgs(
          issueKey = "SKILL-56",
          subtaskId = 1,
          request = wiringRunRequest(),
          assignedWorkflowId = null,
          reviewBaseline = null,
          spawnAuthorization = null,
        ),
      )

    val thrown =
      assertFailsWith<InterruptedException> {
        launchRequest.skillRunRequest.progressEmitter.emit(
          supervisorEmission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true),
        )
      }

    assertSame(interruption, thrown)
    assertTrue(Thread.interrupted())
  }

  private fun supervisorEmission(
    kind: GoalProgressEventKind,
    processAlive: Boolean,
    outcome: GoalProgressOutcome = GoalProgressOutcome.NONE,
  ): AgentRunProgressEmission =
    AgentRunProgressEmission(
      eventKind = kind,
      processAlive = processAlive,
      operationName = "child_agent_run",
      operationKind = "long_child_run",
      expectedLong = true,
      outcome = outcome,
    )

  private fun wiringRunRequest(): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
    )
}

internal class RecordingOutcomeStore : GoalRunnerWorkflowOutcomeStore {
  private val outcomes: MutableMap<String, GoalRunnerStoredOutcome> = mutableMapOf()
  private val reviewStates: MutableMap<String, GoalSubtaskReviewState> = mutableMapOf()
  val unemittedReviewPasses: MutableMap<String, List<GoalSubtaskReviewPassResult>> = mutableMapOf()
  val acknowledgedReviewPasses: MutableList<Pair<String, Int>> = mutableListOf()
  val progresses: MutableMap<String, GoalRunnerWorkflowProgress> = mutableMapOf()
  val blockedWorkflows: MutableList<BlockedWorkflow> = mutableListOf()
  val reopenBlockedPhaseCalls: MutableList<ReopenBlockedPhaseCall> = mutableListOf()
  val observabilityRecords: MutableList<GoalRunnerObservabilityRecordRequest> = mutableListOf()
  val workerSubtaskRequestOutcomes: MutableList<WorkerSubtaskRequestOutcomeRecord> = mutableListOf()
  val authoritativeOutcomesBySubtask: MutableMap<Int, GoalRunnerStoredOutcome> = mutableMapOf()
  val recoveredMissingResultPrefixOutputs: MutableList<RecoveredMissingResultPrefixOutput> = mutableListOf()
  var observabilityRecordResult: Boolean = true
  var throwOnObservabilityRecord: Boolean = false
  var observabilityRecordFailure: Throwable? = null
  var throwOnProgress: Boolean = false
  var progressFailure: Throwable? = null
  var workerSubtaskRequestOutcomeRecordResult: Boolean = true
  var throwOnWorkerSubtaskRequestOutcomeRecord: Boolean = false
  var lastReconcileRequest: ReconcileRequest? = null
  var lastAuthoritativeOutcomeRequest: String? = null

  operator fun set(
    workflowId: String,
    outcome: GoalRunnerStoredOutcome,
  ) {
    outcomes[workflowId] = outcome
    reviewStates.putIfAbsent(
      workflowId,
      GoalSubtaskReviewState.initial("0".repeat(40), emptyList(), CodeReviewExecutionMode.AUTO),
    )
  }

  fun seedReviewState(workflowId: String) {
    reviewStates[workflowId] =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "0".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.AUTO,
      )
  }

  override fun goalSubtaskReviewState(workflowId: String): GoalSubtaskReviewState? = reviewStates[workflowId]

  override fun unemittedGoalReviewPasses(workflowId: String): List<GoalSubtaskReviewPassResult> =
    unemittedReviewPasses[workflowId].orEmpty()

  override fun acknowledgeGoalReviewPass(
    workflowId: String,
    passNumber: Int,
  ): Boolean {
    val remaining = unemittedReviewPasses[workflowId].orEmpty()
    if (remaining.firstOrNull()?.passNumber != passNumber) return false
    acknowledgedReviewPasses += workflowId to passNumber
    unemittedReviewPasses[workflowId] = remaining.drop(1)
    return true
  }

  override fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String>,
    gate: GoalRunnerReconcileGate,
    repoRoot: Path?,
  ): Map<Int, GoalRunnerStoredOutcome> {
    lastReconcileRequest = ReconcileRequest(issueKey, activeWorkflowIds, gate, repoRoot)
    return authoritativeOutcomesBySubtask.toMap()
  }

  override fun authoritativeOutcomes(issueKey: String): Map<Int, GoalRunnerStoredOutcome> {
    lastAuthoritativeOutcomeRequest = issueKey
    return authoritativeOutcomesBySubtask.toMap()
  }

  override fun terminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ): GoalRunnerStoredOutcome? = outcomes[workflowId]

  override fun recoverAndPersistTerminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
  ): GoalRunnerStoredOutcome? = outcomes[workflowId]

  override fun recoverMissingResultPrefixOutput(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    output: GoalRunnerWirePayload,
  ): GoalRunnerStoredOutcome? {
    recoveredMissingResultPrefixOutputs +=
      RecoveredMissingResultPrefixOutput(
        workflowId = workflowId,
        issueKey = issueKey,
        subtaskId = subtaskId,
        output = output,
      )
    return outcomes[workflowId]
  }

  override fun markBlocked(
    workflowId: String,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent?,
  ): String {
    blockedWorkflows += BlockedWorkflow(workflowId, blockedReason, lastResumableStep, supervisionEvent)
    return "implement"
  }

  override fun reopenBlockedPhaseForOperatorResume(
    workflowId: String,
    preferredPhaseId: String,
    reason: String,
    expectedIdentity: FeatureTaskExecutionIdentity,
    expectedExecutionPlan: ValidatedFeatureTaskRuntimeExecutionPlan,
  ): Boolean {
    reopenBlockedPhaseCalls += ReopenBlockedPhaseCall(workflowId, preferredPhaseId, reason)
    return true
  }

  override fun progress(workflowId: String): GoalRunnerWorkflowProgress? {
    progressFailure?.let { throw it }
    if (throwOnProgress) {
      error("progress read failed")
    }
    return progresses[workflowId]
  }

  override fun progressEvents(workflowId: String) = emptyList<GoalProgressEvent>()

  override fun recordObservabilityEvent(request: GoalRunnerObservabilityRecordRequest): Boolean {
    observabilityRecordFailure?.let { throw it }
    if (throwOnObservabilityRecord) {
      error("observability persistence failed")
    }
    observabilityRecords += request
    return observabilityRecordResult
  }

  override fun recordWorkerSubtaskRequestOutcomes(
    workflowId: String,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
  ): Boolean {
    if (throwOnWorkerSubtaskRequestOutcomeRecord) {
      error("worker subtask request outcome persistence failed")
    }
    if (!workerSubtaskRequestOutcomeRecordResult) {
      return false
    }
    workerSubtaskRequestOutcomes += WorkerSubtaskRequestOutcomeRecord(workflowId, outcomes)
    return workerSubtaskRequestOutcomeRecordResult
  }

  val progressEventRecords: MutableList<GoalRunnerProgressEventRecordRequest> = mutableListOf()
  val attemptLedgerRecords: MutableList<GoalRunnerAttemptLedgerRecordRequest> = mutableListOf()

  val progressEvents: MutableList<GoalProgressEvent> = mutableListOf()
  val attemptLedgerEntries: MutableList<GoalAttemptLedgerEntry> = mutableListOf()
  private val nextProgressSequence = mutableMapOf<String, Int>()
  private val nextLedgerSequence = mutableMapOf<String, Int>()

  var throwOnProgressEventRecord: Boolean = false
  var throwOnAttemptLedgerRecord: Boolean = false
  var progressEventFailure: Throwable? = null
  var attemptLedgerRecordFailure: Throwable? = null
  var throwOnLedgerWatermarks: Boolean = false

  override fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest): Boolean {
    progressEventFailure?.let { throw it }
    if (throwOnProgressEventRecord) {
      error("progress event persistence failed")
    }
    progressEventRecords += request
    val sequence = nextProgressSequence.getOrDefault(request.issueKey, 0)
    nextProgressSequence[request.issueKey] = sequence + 1
    progressEvents += request.draft.toEvent(sequence)
    return true
  }

  override fun recordAttemptLedgerEntry(request: GoalRunnerAttemptLedgerRecordRequest): Boolean {
    attemptLedgerRecordFailure?.let { throw it }
    if (throwOnAttemptLedgerRecord) {
      error("attempt ledger persistence failed")
    }
    attemptLedgerRecords += request
    val sequence = nextLedgerSequence.getOrDefault(request.issueKey, 0)
    nextLedgerSequence[request.issueKey] = sequence + 1
    attemptLedgerEntries += request.draft.toEntry(sequence)
    return true
  }

  var ledgerSequenceWatermarks: GoalRunnerLedgerSequenceWatermarks = GoalRunnerLedgerSequenceWatermarks()

  override fun ledgerSequenceWatermarks(issueKey: String): GoalRunnerLedgerSequenceWatermarks {
    if (throwOnLedgerWatermarks) {
      error("ledger watermark read failed")
    }
    return ledgerSequenceWatermarks
  }

  override fun childWorkflowLoopIterations(workflowId: String): Map<String, Int> = emptyMap()
}

private class ThrowingDiagnostics : RuntimeDiagnostics {
  val warningMessages: MutableList<String> = mutableListOf()

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    warningMessages += message
    error("diagnostics failed")
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}

internal data class BlockedWorkflow(
  val workflowId: String,
  val blockedReason: String,
  val lastResumableStep: String,
  val supervisionEvent: GoalRunnerSupervisionEvent?,
)

internal data class ReopenBlockedPhaseCall(
  val workflowId: String,
  val preferredPhaseId: String,
  val reason: String,
)

internal data class RecoveredMissingResultPrefixOutput(
  val workflowId: String,
  val issueKey: String,
  val subtaskId: Int,
  val output: GoalRunnerWirePayload,
)

internal data class ReconcileRequest(
  val issueKey: String,
  val activeWorkflowIds: Set<String>,
  val gate: GoalRunnerReconcileGate,
  val repoRoot: Path?,
)

internal data class WorkerSubtaskRequestOutcomeRecord(
  val workflowId: String,
  val outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
)

internal class RecordingSubtaskLauncher(
  private val result: (GoalRunnerSubtaskLaunchRequest) -> AgentRunLaunchOutcome,
) : GoalRunnerSubtaskLauncher {
  val requests: MutableList<GoalRunnerSubtaskLaunchRequest> = mutableListOf()

  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome {
    val launch = {
      requests += request
      result(request)
    }
    return when (val authorized = request.skillRunRequest.spawnAuthorization?.withAuthorization(launch)) {
      null -> launch()
      is AgentRunSpawnAuthorizationResult.Authorized -> authorized.value
      is AgentRunSpawnAuthorizationResult.Denied ->
        AgentRunLaunchDenied(SupportedAgent.CLAUDE, authorized.pauseReason)
    }
  }
}

internal class RecordingPullRequestPort : GoalPullRequestPort {
  val requests: MutableList<GoalPullRequestRequest> = mutableListOf()
  val openCount: Int get() = requests.size

  override fun open(request: GoalPullRequestRequest): GoalPullRequestResult {
    requests += request
    return GoalPullRequestResult.Opened("https://github.com/canonical/skill-bill/pull/56")
  }
}

private class FixedBranchGitOperations(
  private val branch: String,
) : GoalReviewReadyGitOperations() {
  override fun checkoutBranch(
    repoRoot: Path,
    branch: String,
    baseBranch: String?,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = branch)

  override fun branchExists(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "true")

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = branch)

  override fun createCommit(
    repoRoot: Path,
    message: String,
  ): WorkflowGitCommitResult = WorkflowGitCommitResult.Committed(commitSha = "sha-test")

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = "sha-test")

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "")

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult =
    WorkflowWorktreeActivityResult(status = WorkflowGitOperationStatus.OK)

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = WorkflowSelectedDiffHunksResult(status = WorkflowGitOperationStatus.OK)
}

internal class AcceptGitOperations(
  private val resolvable: Map<String, String> =
    mapOf(
      "abc1234" to "abc1234abc1234abc1234abc1234abc1234abcd",
      "abc1234abc1234abc1234abc1234abc1234abcd" to "abc1234abc1234abc1234abc1234abc1234abcd",
    ),
) : WorkflowGitOperations by StatusDiffGitOperations {
  override fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ): WorkflowGitOperationResult =
    resolvable[revision.trim()]?.let { WorkflowGitOperationResult.Ok(value = it) }
      ?: WorkflowGitOperationResult.Failed(
        error = "Revision '$revision' does not name a commit in this repository.",
      )
}

private object StatusDiffGitOperations : WorkflowGitOperationsTestBase() {
  override fun checkoutBranch(
    repoRoot: Path,
    branch: String,
    baseBranch: String?,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = branch)

  override fun branchExists(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "true")

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "main")

  override fun createCommit(
    repoRoot: Path,
    message: String,
  ): WorkflowGitCommitResult = WorkflowGitCommitResult.Committed(commitSha = "sha-test")

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = "sha-test")

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "")

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult =
    WorkflowWorktreeActivityResult(
      status = WorkflowGitOperationStatus.OK,
      diffStat = GoalObservabilityDiffStat(filesChanged = 2, insertions = 5, deletions = 1),
    )

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = WorkflowSelectedDiffHunksResult(status = WorkflowGitOperationStatus.OK)
}

private class RecordingGitOperations(
  private val currentBranch: String = "",
  private val checkoutError: String? = null,
  private val validationError: String? = null,
  baselineError: String? = null,
) : GoalReviewReadyGitOperations(baselineError) {
  val checkouts: MutableList<String> = mutableListOf()
  val validations: MutableList<String> = mutableListOf()

  override fun checkoutBranch(
    repoRoot: Path,
    branch: String,
    baseBranch: String?,
  ): WorkflowGitOperationResult {
    checkouts += "$branch@${baseBranch.orEmpty()}"
    return checkoutError?.let { WorkflowGitOperationResult.Failed(error = it) }
      ?: WorkflowGitOperationResult.Ok(value = branch)
  }

  override fun branchExists(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "true")

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = currentBranch)

  override fun createCommit(
    repoRoot: Path,
    message: String,
  ): WorkflowGitCommitResult = WorkflowGitCommitResult.Committed(commitSha = "sha-test")

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = "sha-test")

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult {
    validations += "$branch@$expectedBaseBranch"
    return validationError?.let { WorkflowGitOperationResult.Failed(error = it) }
      ?: WorkflowGitOperationResult.Ok(value = expectedBaseBranch)
  }

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "")

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult =
    WorkflowWorktreeActivityResult(status = WorkflowGitOperationStatus.OK)

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = WorkflowSelectedDiffHunksResult(status = WorkflowGitOperationStatus.OK)
}

private abstract class GoalReviewReadyGitOperations(
  private val baselineError: String? = null,
) : WorkflowGitOperationsTestBase() {
  override fun captureGoalSubtaskReviewBaseline(
    repoRoot: Path,
    expectedBranch: String,
  ): GoalSubtaskReviewBaselineResult =
    baselineError?.let {
      GoalSubtaskReviewBaselineResult(status = WorkflowGitOperationStatus.ERROR, error = it)
    }
      ?: GoalSubtaskReviewBaselineResult(
        status = WorkflowGitOperationStatus.OK,
        baseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
      )

  override fun buildGoalSubtaskReviewInput(
    repoRoot: Path,
    baseline: GoalSubtaskReviewBaseline,
    expectedBranch: String,
  ): GoalSubtaskReviewInputResult =
    GoalSubtaskReviewInputResult(
      status = WorkflowGitOperationStatus.OK,
      input =
        GoalSubtaskReviewInput(
          reviewBaseSha = baseline.reviewBaseSha,
          currentHeadSha = "0".repeat(40),
          trackedDelta = "",
          ownedUntrackedPatches = "",
        ),
    )

  override fun recoverGoalSubtaskReviewBaseline(
    repoRoot: Path,
    request: GoalSubtaskReviewBaselineRecoveryRequest,
    expectedBranch: String,
  ): GoalSubtaskReviewBaselineResult =
    GoalSubtaskReviewBaselineResult(
      status = WorkflowGitOperationStatus.ERROR,
      error = "Goal review baseline recovery is not used by this goal runner fixture.",
    )
}

internal fun manifest(subtaskCount: Int): DecompositionManifest =
  DecompositionManifest(
    issueKey = "SKILL-56",
    featureName = "goal",
    parentSpecPath = ".feature-specs/SKILL-56-goal/spec.md",
    baseBranch = "main",
    featureBranch = "feat/SKILL-56-goal",
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
    subtasks =
      (1..subtaskCount).map { id ->
        DecompositionSubtask(
          id = id,
          name = "Subtask $id",
          specPath = ".feature-specs/SKILL-56-goal/spec_subtask_$id.md",
          dependencies = if (id == 1) emptyList() else listOf(DecompositionDependency(id - 1)),
        )
      },
  )

internal fun completeOutcome(subtaskId: Int): GoalRunnerStoredOutcome =
  GoalRunnerStoredOutcome(
    status = GoalRunnerTerminalStatus.COMPLETE,
    workflowId = "wfl-$subtaskId",
    commitSha = "sha-$subtaskId",
    lastResumableStep = "commit_push",
    suppressPr = true,
  )

private fun workerSubtaskRequestJson(
  name: String,
  specPath: String,
  requiresOperatorConfirmation: Boolean = false,
): String {
  val payload =
    listOf(
      """"kind":"skill_bill_subtask_request"""",
      """"name":"$name"""",
      """"spec_path":"$specPath"""",
      """"requires_operator_confirmation":$requiresOperatorConfirmation""",
    ).joinToString(prefix = "{", postfix = "}")
  return "SKILL_BILL_SUBTASK_REQUEST: $payload"
}

internal fun launchFacts(
  termination: AgentRunTermination = AgentRunTermination.Exited(0),
  stdout: String = "diagnostic only",
  stderr: String = "",
): AgentRunLaunchFacts =
  agentRunLaunchFacts(
    agent = SupportedAgent.CLAUDE,
    termination = termination,
    stdout = stdout,
    stderr = stderr,
  )

internal fun DecompositionManifest.withWorkflowId(
  subtaskId: Int,
  workflowId: String,
): DecompositionManifest =
  copy(
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) {
          subtask.copy(workflowId = workflowId)
        } else {
          subtask
        }
      },
  )

internal fun DecompositionManifest.withSubtaskAgent(
  subtaskId: Int,
  finalizingAgentId: String,
  participatingAgentIds: List<String> = listOf(finalizingAgentId),
): DecompositionManifest =
  copy(
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) {
          subtask.copy(finalizingAgentId = finalizingAgentId, participatingAgentIds = participatingAgentIds)
        } else {
          subtask
        }
      },
  )

private fun DecompositionManifest.withCompletedSubtask(
  subtaskId: Int,
  workflowId: String,
  commitSha: String,
): DecompositionManifest =
  copy(
    status = if (subtasks.all { it.id == subtaskId || it.status == "complete" }) "complete" else "in_progress",
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "complete"),
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) {
          subtask.copy(
            status = "complete",
            workflowId = workflowId,
            commitSha = commitSha,
            lastResumableStep = "commit_push",
          )
        } else {
          subtask
        }
      },
  )

private fun DecompositionManifest.withBlockedSubtask(
  subtaskId: Int,
  workflowId: String,
  reason: String,
): DecompositionManifest =
  copy(
    status = "blocked",
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "blocked"),
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) {
          subtask.copy(
            status = "blocked",
            workflowId = workflowId,
            blockedReason = reason,
            lastResumableStep = "validate",
          )
        } else {
          subtask
        }
      },
  )

class GoalRunnerStatusAttributionTest {
  @Test
  fun `status projection reports counts current step and active agent sourced from persisted run state`() {
    val blockedWithAgent =
      manifest(subtaskCount = 3)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
        .withBlockedSubtask(2, workflowId = "wfl-2", reason = "needs review")
        .withSubtaskAgent(2, finalizingAgentId = "cursor")
    val store = InMemoryGoalManifestStore(manifest = blockedWithAgent)
    val outcomes = RecordingOutcomeStore()
    outcomes.progresses["wfl-2"] =
      GoalRunnerWorkflowProgress(
        workflowId = "wfl-2",
        workflowStatus = WorkflowStatus.RUNNING,
        currentStepId = "implement",
        progressToken = "child-progress-token",
        latestLivenessSignal = "durable_progress step=implement attempt=1",
      )
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = outcomes,
        phaseRecorder = goalTestPhaseRecorder(),
      )

    val status =
      service.status(
        GoalRunnerStatusRequest(
          issueKey = "SKILL-56",
          invokedAgentId = "claude",
          configuredAgentOverrideId = "codex",
        ),
      )

    requireNotNull(status)
    assertEquals(1, status.completeCount)

    assertEquals(2, status.pendingCount)
    assertEquals(0, status.blockedCount)
    assertEquals(2, status.currentSubtaskId)
    assertEquals("implement", status.currentStep)
    assertEquals("cursor", status.activeAgent)
    assertEquals("durable_progress step=implement attempt=1", status.latestLivenessSignal)
  }

  @Test
  fun `status projection omits active agent when no agent is persisted for the current subtask`() {
    val store =
      InMemoryGoalManifestStore(
        manifest = manifest(subtaskCount = 1).withBlockedSubtask(1, workflowId = "wfl-1", reason = "needs review"),
      )
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
      )

    val status =
      service.status(
        GoalRunnerStatusRequest(
          issueKey = "SKILL-56",
          invokedAgentId = "codex",
          configuredAgentOverrideId = "claude",
        ),
      )

    requireNotNull(status)
    assertEquals(null, status.activeAgent)
  }

  @Test
  fun `status projection reports the persisted phase-ledger agent for a runtime child regardless of caller`() {
    val harness = GoalStatusPhaseLedgerHarness()
    val workflowId = "wfl-cursor-child"
    harness.openRuntimeWorkflow(workflowId)
    harness.recordCompletedPhase(workflowId, phaseId = "implement", resolvedAgentId = "cursor")
    val store =
      InMemoryGoalManifestStore(
        manifest = manifest(subtaskCount = 1).withBlockedSubtask(1, workflowId = workflowId, reason = "needs review"),
      )
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = harness.recorder,
      )

    val status =
      service.status(
        GoalRunnerStatusRequest(
          issueKey = "SKILL-56",
          invokedAgentId = "codex",
          configuredAgentOverrideId = "codex",
        ),
      )

    requireNotNull(status)
    assertEquals("cursor", status.activeAgent)
  }
}

internal const val FAKE_PAUSED_AT = "2026-08-02T10:00:00Z"

internal fun goalTestPhaseRecorder(): FeatureTaskRuntimePhaseRecorder =
  testPhaseRecorder(
    GoalTestEmptyDatabase,
    GoalTestNoopSnapshotValidator,
    AcceptingFeatureTaskRuntimeWireArtifactValidator,
    AcceptingFeatureTaskRuntimeWireArtifactValidator,
  )

private class GoalStatusPhaseLedgerHarness {
  private val repository = GoalStatusSeedableWorkflowStateRepository()
  private val database = GoalStatusSeedableDatabase(repository)
  val recorder: FeatureTaskRuntimePhaseRecorder =
    testPhaseRecorder(
      database,
      GoalTestNoopSnapshotValidator,
      AcceptingFeatureTaskRuntimeWireArtifactValidator,
      AcceptingFeatureTaskRuntimeWireArtifactValidator,
    )
  var failOwnershipReads: Boolean
    get() = repository.failOwnershipReads
    set(value) {
      repository.failOwnershipReads = value
    }
  val ownershipWriteCount: Int get() = repository.ownershipWriteCount

  fun openRuntimeWorkflow(workflowId: String) {
    recorder.openTestWorkflow(workflowId, sessionId = "goal-status-test")
  }

  fun seedOwnership(
    workflowId: String,
    expiresAt: String,
  ) {
    repository.seedOwnership(workflowId, expiresAt)
  }

  fun recordCompletedPhase(
    workflowId: String,
    phaseId: String,
    resolvedAgentId: String,
  ) {
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = workflowId,
        phaseId = phaseId,
        status = "completed",
        attemptCount = 1,
        resolvedAgentId = resolvedAgentId,
        finished = true,
        outputArtifact = """{"contract_version":"0.1"}""",
      ),
    )
  }
}

private class GoalStatusSeedableDatabase(
  private val repository: GoalStatusSeedableWorkflowStateRepository,
) : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/goal-status-phase-ledger.db")

  override fun resolveDbPath(): Path = dbPath

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = transaction(block)

  override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork =
    object : UnitOfWorkDefaults() {
      override val dbPath: Path = this@GoalStatusSeedableDatabase.dbPath
      override val reviews: ReviewRepository get() = error("unused by goal status tests")
      override val learnings: LearningRepository get() = error("unused by goal status tests")
      override val lifecycleTelemetry: LifecycleTelemetryRepository get() = error("unused by goal status tests")
      override val telemetryReconciliation: TelemetryReconciliationRepository get() =
        error(
          "unused by goal status tests",
        )
      override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused by goal status tests")
      override val workflowStates: WorkflowStateRepository = repository
      override val workList = EmptyWorkListRepository
      override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
      override val goalRunnerControls = EmptyGoalRunnerControlRepository
    }
}

private class GoalStatusSeedableWorkflowStateRepository : WorkflowStateRepositoryDefaults() {
  override fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity) = Unit

  override fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ) = emptyList<FeatureTaskWorkflowCandidate>()

  private val taskRuntimeRows = linkedMapOf<String, WorkflowStateRecord>()
  private val ownershipRows =
    linkedMapOf<String, FeatureTaskRuntimeWorkerOwnership>()
  var failOwnershipReads = false
  var ownershipWriteCount = 0

  fun seedOwnership(
    workflowId: String,
    expiresAt: String,
  ) {
    ownershipRows[workflowId] =
      FeatureTaskRuntimeWorkerOwnership(
        workflowId = workflowId,
        ownerToken = "owner-token-123456",
        generation = 1,
        hostIdentity = "host",
        bootIdentity = "boot",
        pid = 1234,
        processBirthToken = "birth-1234",
        leaseState = ACTIVE,
        phaseId = "implement",
        phaseAttempt = 1,
        heartbeatAt = "2026-07-27T11:59:30Z",
        expiresAt = expiresAt,
      )
  }

  override fun getFeatureTaskRuntimeWorkerOwnership(workflowId: String): FeatureTaskRuntimeWorkerOwnership? {
    if (failOwnershipReads) error("lease read failed")
    return ownershipRows[workflowId]
  }

  override fun saveFeatureTaskWorkflow(
    row: WorkflowStateRecord,
    mode: FeatureTaskWorkflowMode,
  ) {
    taskRuntimeRows[row.workflowId] = row
  }

  override fun getFeatureTaskWorkflow(workflowId: String): WorkflowStateRecord? = taskRuntimeRows[workflowId]

  override fun getFeatureTaskWorkflowAsMode(
    workflowId: String,
    mode: FeatureTaskWorkflowMode,
  ): WorkflowStateRecord? = taskRuntimeRows[workflowId]

  override fun listFeatureTaskWorkflows(
    mode: FeatureTaskWorkflowMode,
    limit: Int,
  ): List<WorkflowStateRecord> = taskRuntimeRows.values.toList().asReversed().take(limit)

  override fun save(
    family: WorkflowFamily,
    snapshot: WorkflowStateSnapshot,
  ) = saveRecord(family, snapshot.toRecord(taskRuntimeRows[snapshot.workflowId]))

  override fun saveRecord(
    family: WorkflowFamily,
    record: WorkflowStateRecord,
  ) {
    taskRuntimeRows[record.workflowId] = record
  }

  override fun get(
    family: WorkflowFamily,
    workflowId: String,
  ): WorkflowStateSnapshot? = taskRuntimeRows[workflowId]?.toSnapshot()

  override fun list(
    family: WorkflowFamily,
    limit: Int,
  ): List<WorkflowStateSnapshot> =
    taskRuntimeRows.values.toList().asReversed().take(limit).map(WorkflowStateRecord::toSnapshot)

  override fun latest(family: WorkflowFamily): WorkflowStateSnapshot? = list(family, 1).firstOrNull()
}

private object GoalTestNoopSnapshotValidator : WorkflowSnapshotValidator {
  override fun validate(
    snapshot: WorkflowStateSnapshot,
    slug: String,
  ) = Unit
}

private object GoalTestEmptyDatabase : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/goal-test-metrics.db")

  override fun resolveDbPath(): Path = dbPath

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = transaction(block)

  override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork =
    object : UnitOfWorkDefaults() {
      override val dbPath: Path = this@GoalTestEmptyDatabase.dbPath
      override val reviews: ReviewRepository get() = error("unused by goal status tests")
      override val learnings: LearningRepository get() = error("unused by goal status tests")
      override val lifecycleTelemetry: LifecycleTelemetryRepository get() = error("unused by goal status tests")
      override val telemetryReconciliation: TelemetryReconciliationRepository get() =
        error(
          "unused by goal status tests",
        )
      override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused by goal status tests")
      override val workflowStates: WorkflowStateRepository = GoalTestEmptyWorkflowStateRepository
      override val workList = EmptyWorkListRepository
      override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
      override val goalRunnerControls = EmptyGoalRunnerControlRepository
    }
}

private class GoalTestPlanningDatabase : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/goal-test-planning.db")
  val deletedParentGoalIds = mutableListOf<String>()
  val deletedChildWorkflowParentIds = mutableListOf<String>()
  private val planningRepository =
    object : GoalPlanningPreparationRepository by
    EmptyGoalPlanningPreparationRepository {
      override fun deleteByGoal(parentGoalWorkflowId: String): Int {
        deletedParentGoalIds += parentGoalWorkflowId
        return 1
      }
    }

  override fun resolveDbPath(): Path = dbPath

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = transaction(block)

  override fun <T> transaction(block: (UnitOfWork) -> T): T {
    return block(unitOfWork())
  }

  private fun unitOfWork(): UnitOfWork =
    object : UnitOfWorkDefaults() {
      override val dbPath: Path = this@GoalTestPlanningDatabase.dbPath
      override val reviews: ReviewRepository get() = error("unused by hard reset test")
      override val learnings: LearningRepository get() = error("unused by hard reset test")
      override val lifecycleTelemetry: LifecycleTelemetryRepository get() = error("unused by hard reset test")
      override val telemetryReconciliation: TelemetryReconciliationRepository get() = error("unused by hard reset test")
      override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused by hard reset test")
      override val workflowStates: WorkflowStateRepository =
        object : WorkflowStateRepository by
        GoalTestEmptyWorkflowStateRepository {
          override fun deleteGoalChildWorkflowsByParent(parentWorkflowId: String): Int {
            deletedChildWorkflowParentIds += parentWorkflowId
            return 1
          }
        }
      override val workList = EmptyWorkListRepository
      override val goalPlanningPreparations = planningRepository
      override val goalRunnerControls = EmptyGoalRunnerControlRepository
    }
}

private object GoalTestEmptyWorkflowStateRepository : WorkflowStateRepositoryDefaults() {
  override fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity) = Unit

  override fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ) = emptyList<FeatureTaskWorkflowCandidate>()
}

class GoalRunnerValidationQualityRetryTest {
  private fun runRequest(): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
    )

  @Test
  fun `goal stops when validate exhausts its own phase attempts`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] =
          GoalRunnerStoredOutcome(
            status = GoalRunnerTerminalStatus.BLOCKED,
            workflowId = "wfl-$subtaskId",
            blockedReason = "./gradlew check keeps failing during validate.",
            lastResumableStep = "validate",
            suppressPr = true,
          )
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.BLOCKED, stopped.stop.reason)
    assertEquals("validate", stopped.stop.lastResumableStep)
    assertEquals("blocked", store.manifest.subtasks.single().status)
    val validateResumes =
      launcher.requests.count {
        it.skillRunRequest.goalContinuation?.lastResumableStep == "validate"
      }
    assertEquals(1, launcher.requests.size, "the goal must not multiply the phase attempt budget")
    assertEquals(0, validateResumes, "a blocked validate phase requires an operator resume")
  }
}

class GoalRunnerUnaddressedFindingsSummaryTest {
  @Test
  fun `a goal whose ledger reports absent still completes with the compact severity breakdown`() {
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
      )
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = TestNoopGoalRunnerSubtaskLauncher,
          outcomeStore = RecordingOutcomeStore(),
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          unaddressedFindingsLedgerService =
            UnaddressedFindingsLedgerService(
              RuntimeFakeDatabaseSessionFactory(InMemoryRuntimeWorkflowRepository(), knownIssue = false),
              NoopRuntimeDiagnostics,
            ),
        ),
      )

    val request =
      GoalRunnerRunRequest(
        issueKey = "SKILL-56",
        repoRoot = Path.of("/tmp/skillbill-goal-runner"),
        invokedAgentId = "claude",
      )
    val completed = assertIs<GoalRunnerRunReport.Completed>(runner.run(request))

    assertEquals("opened", completed.pullRequestStatus.wireValue)
    assertEquals(0, completed.unaddressedFindingCount)
    assertEquals(
      mapOf("blocker" to 0, "major" to 0, "minor" to 0, "nit" to 0),
      completed.unaddressedSeverityBreakdown,
    )
  }

  @Test
  fun `a malformed ledger row does not abort finalization before the pull request is opened`() {
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
      )
    val sessionFactory = RuntimeFakeDatabaseSessionFactory(InMemoryRuntimeWorkflowRepository())
    sessionFactory.ledgerRows +=
      UnaddressedFinding(
        issueKey = "SKILL-56",
        subtaskId = 1,
        workflowId = "wfl-1",
        reviewPassNumber = 1,
        findingOrdinal = 1,
        severity = "major",
        issueCategory = "not_a_governed_category",
        location = "src/Feature.kt:42",
        summary = "Poison row persisted by an older writer",
      )
    val pullRequestPort = RecordingPullRequestPort()
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = TestNoopGoalRunnerSubtaskLauncher,
          outcomeStore = RecordingOutcomeStore(),
          pullRequestPort = pullRequestPort,
        ).copy(
          unaddressedFindingsLedgerService = UnaddressedFindingsLedgerService(sessionFactory, NoopRuntimeDiagnostics),
        ),
      )

    val request =
      GoalRunnerRunRequest(
        issueKey = "SKILL-56",
        repoRoot = Path.of("/tmp/skillbill-goal-runner"),
        invokedAgentId = "claude",
      )
    val completed = assertIs<GoalRunnerRunReport.Completed>(runner.run(request))

    assertEquals("opened", completed.pullRequestStatus.wireValue)
    assertEquals(
      null,
      completed.unaddressedFindingCount,
      "an unreadable ledger must not report an affirmative zero",
    )
    assertEquals(emptyMap(), completed.unaddressedSeverityBreakdown)
  }
}

class GoalRunnerOperatorBlockedResumeTest {
  @Test
  fun `operator resume of a blocked subtask reopens the child phase before launch`() {
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .withBlockedSubtask(1, workflowId = "wfl-1", reason = "implement needs user action"),
      )
    val outcomes = RecordingOutcomeStore()
    outcomes.seedReviewState("wfl-1")
    val launcher =
      RecordingSubtaskLauncher {
        outcomes["wfl-1"] = completeOutcome(1)
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(1), launcher.requests.map { it.skillRunRequest.subtaskId })
    val resumed = store.newChildWorkflowSetups.single()
    assertEquals("wfl-1", resumed.workflowId)
    assertEquals("validate", resumed.operatorResumePhaseId)
    assertEquals("validate", launcher.requests.single().skillRunRequest.goalContinuation?.lastResumableStep)
    assertTrue(
      resumed.operatorResumeReason.orEmpty().contains("Operator resumed the goal"),
      resumed.operatorResumeReason.orEmpty(),
    )
  }

  private fun runRequest(): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
    )
}
