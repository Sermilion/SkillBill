package skillbill.engine

import skillbill.application.RecordingLifecycleTelemetryRepository
import skillbill.application.RecordingSpecScratchStore
import skillbill.application.RecordingSpecStatusWriter
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.decomposition.baseBranch
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.application.review.spec.SpecIntentProjectionExtractor
import skillbill.application.review.spec.SpecIntentProjectionResolver
import skillbill.application.seedHarnessSpecIntentProjection
import skillbill.application.telemetry.lifecycle.LifecycleTelemetryService
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.testHarnessClock
import skillbill.config.model.RepoLocalConfig
import skillbill.contracts.JsonCodec
import skillbill.contracts.time.JvmSystemClock
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION
import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupRunner
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.lifecycle.core.AlwaysValidValidator
import skillbill.engine.featuretask.lifecycle.core.ApprovingReviewDriverStub
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeLifecycleTelemetry
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimePhaseOutputTestValidator
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeProbeWriters
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.phase.core.FeatureTaskPhaseSettlementService
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGateBranchBoundaries
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGateValidationBoundaries
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGates
import skillbill.engine.featuretask.phase.core.InMemoryFeatureTaskPhaseSettlementRepository
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimePlanningStopper
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationRuntime
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.engine.featuretask.prepare.FeatureTaskRuntimeSpecGate
import skillbill.engine.featuretask.prepare.SpecSourceResolver
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeReviewDriver
import skillbill.engine.featuretask.review.finding.FeatureTaskRuntimeFindingVerificationBoundaryMemory
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunInvariantsStore
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeRunner
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeBuildGateProgressStore
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeReadinessGateCoordinator
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeValidationGateCoordinator
import skillbill.engine.featuretask.validation.ReadinessCheckSelection
import skillbill.engine.featuretask.validation.ValidationGateResolver
import skillbill.engine.worktreeedit.WorktreeEditJournalWriter
import skillbill.error.core.RejectedOutputDiagnosticError
import skillbill.error.core.RejectedOutputDiagnosticError.Absent
import skillbill.error.core.RejectedOutputDiagnosticError.Conflict
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.infrastructure.workflow.github.GitHubPullRequestCheckDiscovery
import skillbill.infrastructure.workflow.goalplanning.FileSystemGoalPlanningBoundaryBodyResolver
import skillbill.infrastructure.workflow.goalplanning.FileSystemGoalPlanningContextDiscovery
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RejectedOutputDiagnosticPermissions
import skillbill.ports.diagnostics.RejectedOutputDiagnosticRepository
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRecord
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.diff.DiffResolverPortDefaults
import skillbill.ports.featuretask.model.FeatureTaskRuntimeCrashReconciliationCandidate
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState.TAKEOVER_RESERVED
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.UnaddressedFindingsRepository
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.repository.toFileLocation
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.taskruntime.DERIVING_SHARED_EVIDENCE_RESOLVER
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSpecStatusWriter
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider
import skillbill.ports.validation.PrCheckProcessRunner
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.PrCheckRunResult
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.WorkflowStateRepositoryDefaults
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.FeatureTaskWorkflowCandidate
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.specscratch.SpecScratchStore
import skillbill.ports.workflow.toRecord
import skillbill.review.context.model.accounting.ReviewBudgetKind
import skillbill.review.context.model.hunk.ReviewContextBudgetExceeded
import skillbill.review.context.model.hunk.ReviewContextBudgetExceededException
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity.BLOCKER
import skillbill.review.model.ReviewFindingVerdict
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat.GRADLE_KOTLIN_COMPILER_STDOUT
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsLocator
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.scaffold.model.ValidationGateExecutedWorkFormat.GRADLE_ACTIONABLE_SUMMARY
import skillbill.scaffold.model.ValidationGateExecutedWorkSignal
import skillbill.scaffold.model.ValidationGateFindingsFormat.JUNIT_XML
import skillbill.scaffold.model.ValidationGateFindingsLocator
import skillbill.telemetry.model.TelemetrySettings
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.FeatureTaskWorkflowMode.PROSE
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputSourceLocation
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputValidationResult
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode.CACHE_ELIGIBLE
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome.FAILED
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome.PASSED
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.lang.Boolean.TYPE
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource
import java.lang.Double.TYPE as DoubleTYPE
import java.lang.Long.TYPE as LongTYPE

internal const val WORKFLOW_ID = "wftr-20260602-test-0001"
internal const val SESSION_ID = "ftr-test-001"
internal const val RUNNER_TEST_ISSUE_KEY = "SKILL-65"
private const val ISSUE_KEY = RUNNER_TEST_ISSUE_KEY
internal const val RUNNER_TEST_SPEC_REFERENCE = ".feature-specs/SKILL-65/spec.md"
internal const val SPEC_REFERENCE = RUNNER_TEST_SPEC_REFERENCE
internal const val CONVENTION_SPEC_REFERENCE =
  ".feature-specs/SKILL-65-runtime-feature-task-parity/spec_subtask_1.md"
internal const val EXPECTED_FEATURE_BRANCH = "feat/SKILL-65-runtime-feature-task-parity"
internal const val INVOKED_AGENT = "claude-code"
internal const val VALID_OUTPUT = """{"contract_version":"0.2"}"""
internal val VALIDATE_REPAIR_WITHOUT_GATE_COUNTS =
  """
  {
    "contract_version": "0.3",
    "phase_id": "validate",
    "status": "completed",
    "summary": "Gate repair segment without measured counts.",
    "produced_outputs": {
      "validation_result": {
        "validation_status": "passed",
        "checks": [{"name": "check", "status": "passed"}],
        "repository_checkpoint": {"fingerprint": "fixture-checkpoint-1"},
        "validation_evidence": {
          "contract_version": "0.1",
          "results": [{"command": "./gradlew check", "exit_code": 0}]
        }
      }
    }
  }
  """.trimIndent()

internal const val VALID_REVIEW_OUTPUT = """{"contract_version":"0.3","produced_outputs":{"findings":[]}}"""

internal const val VALID_AUDIT_OUTPUT =
  """{"contract_version":"0.6","phase_id":"audit","status":"completed","summary":"Audit satisfied.",""" +
    """"verdict":"satisfied","produced_outputs":{"value":"{\"gaps\":[],\"non_blocking_findings\":[]}"}}"""

internal val VALID_VERIFY_FINDINGS_OUTPUT = verifyFindingsOutput()
internal val PREPLAN_OUTPUT = seededProjectionEnvelope("preplan", PlanningProjectionFixtures.PREPLAN_DIGEST)
internal val PLAN_OUTPUT = seededProjectionEnvelope("plan", PlanningProjectionFixtures.PLAN_PROSE)
internal val IMPLEMENT_OUTPUT =
  seededProjectionEnvelope("implement", PlanningProjectionFixtures.IMPLEMENT_PROSE)
internal val SIMPLIFY_OUTPUT = validJsonOutput("simplify")

private val GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_RESULTS.label()
private val GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.label()
private val FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES.label()
private val FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.label()

private fun seededProjectionEnvelope(
  phaseId: String,
  producedOutputs: String,
): String =
  """{"contract_version":"0.3","phase_id":"$phaseId","status":"completed",""" +
    """"summary":"Phase produced a validated output.","produced_outputs":$producedOutputs}"""

internal val ALL_PHASES =
  listOf(
    "preplan",
    "plan",
    "implement",
    "simplify",
    "audit",
    "review",
    "verify_findings",
    "implement_fix",
    "build",
    "validate",
    "write_history",
    "commit_push",
    "pr",
  )
internal val COMPLETED_PHASES_CLEAN_RUN = ALL_PHASES.filterNot { it == "implement_fix" || it == "build" }
internal val AGENT_LAUNCHED_PHASES =
  ALL_PHASES.filterNot {
    it == "review" || it == "implement_fix" || it == "build" || it == "commit_push"
  }

internal fun expiredCrashedOwnership(): FeatureTaskRuntimeWorkerOwnership =
  FeatureTaskRuntimeWorkerOwnership(
    workflowId = WORKFLOW_ID,
    generation = 1,
    ownerToken = "crashed-child-token",
    hostIdentity = "harness-host",
    bootIdentity = "harness-boot",
    pid = 7,
    processBirthToken = "harness-birth-7",
    leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
    heartbeatAt = "2000-01-01T00:00:00Z",
    expiresAt = "2000-01-01T00:00:30Z",
    phaseId = "implement",
    phaseAttempt = 1,
  )

internal fun phaseAgent(phaseId: String): String = "agent-$phaseId"

internal fun phasePerAgentAssignment(): FeatureTaskRuntimeAgentAssignment =
  FeatureTaskRuntimeAgentAssignment(perPhaseAgentIds = ALL_PHASES.associateWith(::phaseAgent))

internal class RunnerHarnessIo(
  val workflow: RunnerHarnessWorkflow,
  val repository: InMemoryRuntimeWorkflowRepository,
  val gitOperations: RecordingWorkflowGitOperations,
  val specStatusWriter: RecordingSpecStatusWriter,
  val database: RuntimeFakeDatabaseSessionFactory,
)

internal class RunnerHarnessWorkflow(
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
)

internal data class SeedReentryPhaseSeed(
  val phaseId: String,
  val status: String,
  val attemptCount: Int,
  val agentId: String,
  val outputArtifact: String?,
  val loopId: String,
  val edgeIteration: Int,
)

internal class RunnerHarness(
  val launcher: RuntimeRecordingLauncher,
  val io: RunnerHarnessIo,
  val runner: FeatureTaskRuntimeRunner,
  val events: MutableList<FeatureTaskRuntimeRunEvent>,
  private val runRequest: FeatureTaskRuntimeRunRequest,
  val specScratchStore: RecordingSpecScratchStore,
) {
  val specStatusWriter: RecordingSpecStatusWriter get() = io.specStatusWriter
  val recorder: FeatureTaskRuntimePhaseRecorder get() = io.workflow.recorder
  val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder
    get() = io.workflow.goalContinuationRecorder
  val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder
    get() = io.workflow.decomposeTerminalRecorder
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore get() = io.workflow.runInvariantsStore
  val repository: InMemoryRuntimeWorkflowRepository get() = io.repository
  val gitOperations: RecordingWorkflowGitOperations get() = io.gitOperations
  val ledgerRows: List<UnaddressedFinding> get() = io.database.ledgerRows

  fun seedRawReviewResults(state: GoalSubtaskReviewState) {
    val artifacts = repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap()
    artifacts[GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY] =
      state.passResults.associate { result ->
        result.passNumber.toString() to "raw review result for pass ${result.passNumber}"
      }
    repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)
  }

  fun reviewedDeltaDigest(): String? =
    requireNotNull(goalContinuationRecorder.reviewStateRecorder.reviewState(WORKFLOW_ID)).reviewedDeltaDigest

  fun currentReviewDeltaDigest(
    git: RecordingWorkflowGitOperations,
    repoRoot: Path,
  ): String {
    val state = requireNotNull(goalContinuationRecorder.reviewStateRecorder.reviewState(WORKFLOW_ID))
    return requireNotNull(
      git.buildGoalSubtaskReviewInput(
        repoRoot,
        GoalSubtaskReviewBaseline(state.reviewBaseSha, state.baselineUntrackedPaths),
        "feat/existing-runtime-branch",
      ).input,
    ).deltaDigest
  }

  fun stripReviewedDeltaDigest() {
    val artifacts = repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap()
    val state =
      JsonCodec
        .anyToStringAnyMap(artifacts[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY])
        .orEmpty()
        .toMutableMap()
    state.remove("reviewed_delta_digest")
    artifacts[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY] = state
    repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)
  }

  fun launchOrder(): List<String> =
    events.mapNotNull { event ->
      when (event) {
        is FeatureTaskRuntimeRunEvent.PhaseStarted -> event.phaseId
        is FeatureTaskRuntimeRunEvent.PhaseFixLoopIteration -> event.phaseId
        else -> null
      }
    }

  fun launchedPhaseOrder(): List<String> =
    launcher.requests.map { request ->
      ALL_PHASES.firstOrNull { phaseId -> phaseAgent(phaseId) == request.invokedAgentId }
        ?: error("Launch request agent '${request.invokedAgentId}' is not phase-attributable.")
    }

  fun launchedPromptPhaseOrder(): List<String> =
    launcher.requests.map { request ->
      phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
    }

  fun seedPhase(
    phaseId: String,
    status: String,
    attemptCount: Int,
    agentId: String,
    outputArtifact: String?,
  ) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseStateForTest(phaseId, status, attemptCount, agentId, outputArtifact)
  }

  fun seedReviewPhase(
    status: String,
    attemptCount: Int,
    outputArtifact: String?,
    reviewPassNumber: Int,
  ) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "review",
        status = status,
        attemptCount = attemptCount,
        resolvedAgentId = phaseAgent("review"),
        finished = status == "completed",
        outputArtifact = outputArtifact,
        reviewPassNumber = reviewPassNumber,
      ),
    )
  }

  fun seedLegacyCheckpointIdentityStore() {
    seedCheckpointIdentityStore(
      mapOf(
        "contract_version" to "0.1",
        "checkpoints" to
          listOf(
            mapOf(
              "sequence_number" to 0,
              "issue_key" to ISSUE_KEY,
              "branch" to "feat/existing-runtime-branch",
              "phase_id" to "implement",
              "generation" to 0,
              "owned_path_digest" to "a".repeat(64),
              "owned_path_count" to 1,
              "commit_sha" to "b".repeat(40),
              "recorded_at" to "2026-08-10T00:00:00Z",
            ),
          ),
      ),
    )
  }

  fun seedMalformedCurrentCheckpointIdentityStore() {
    seedCheckpointIdentityStore(
      mapOf(
        "contract_version" to
          FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION,
        "checkpoints" to listOf(mapOf("sequence_number" to 0)),
      ),
    )
  }

  private fun seedCheckpointIdentityStore(store: Map<String, Any?>) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    repository.replaceTaskRuntimeArtifacts(
      WORKFLOW_ID,
      LinkedHashMap(repository.taskRuntimeArtifacts(WORKFLOW_ID)).apply {
        put(FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY, store)
      },
    )
  }

  fun seedProseModeWorkflow() {
    repository.saveFeatureTaskWorkflow(
      WorkflowStateRecord(
        workflowId = WORKFLOW_ID,
        sessionId = SESSION_ID,
        workflowName = "bill-feature-task",
        contractVersion = "0.1",
        workflowStatus = WorkflowStatus.RUNNING.wireValue,
        currentStepId = "implement",
        stepsJson = "[]",
        artifactsJson = "{}",
        startedAt = null,
        updatedAt = null,
        finishedAt = null,
        mode = PROSE,
      ),
      PROSE,
    )
  }

  fun seedResolvedBranch(
    branch: String,
    baseBranch: String?,
    created: Boolean,
  ) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordResolvedBranch(
      WORKFLOW_ID,
      FeatureTaskRuntimeResolvedBranch(
        branch = branch,
        baseBranch = baseBranch,
        created = created,
        reviewBaseSha = "0".repeat(40),
      ),
    )
  }

  fun seedBlockedPhase(
    phaseId: String,
    attemptCount: Int,
    agentId: String,
    blockedReason: String,
    failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  ) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = phaseId,
        status = "blocked",
        attemptCount = attemptCount,
        resolvedAgentId = agentId,
        finished = false,
        outputArtifact = null,
        blockedReason = blockedReason,
        failureDisposition = failureDisposition,
      ),
    )
  }

  fun seedReentryPhase(seed: SeedReentryPhaseSeed) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = seed.phaseId,
        status = seed.status,
        attemptCount = seed.attemptCount,
        resolvedAgentId = seed.agentId,
        finished = seed.status == "completed",
        outputArtifact = seed.outputArtifact,
        loopId = seed.loopId,
        edgeIteration = seed.edgeIteration,
      ),
    )
  }

  fun seedLoopEdge(
    phaseId: String,
    loopId: String,
    edgeIteration: Int,
  ) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.appendLedgerEntry(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = WORKFLOW_ID,
        action = FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
        phaseId = phaseId,
        attemptCount = edgeIteration,
        resolvedAgentId = INVOKED_AGENT,
        loopId = loopId,
        edgeIteration = edgeIteration,
      ),
    )
  }

  fun seedBranchSetupBlockedPhase(
    phaseId: String,
    blockedReason: String,
  ) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = phaseId,
        status = "blocked",
        attemptCount = 1,
        resolvedAgentId = BRANCH_SETUP_AGENT_ID,
        finished = false,
        outputArtifact = null,
        blockedReason = blockedReason,
      ),
    )
  }

  fun request(): FeatureTaskRuntimeRunRequest = runRequest

  fun request(transitionsOverride: FeatureTaskRuntimeTransitionDeclaration): FeatureTaskRuntimeRunRequest =
    runRequest.copy(transitionsOverride = transitionsOverride)
}

internal const val BRANCH_SETUP_AGENT_ID = "branch-setup"

internal fun remediationReviewLauncher(git: RecordingWorkflowGitOperations): RuntimeRecordingLauncher =
  RuntimeRecordingLauncher { request ->
    val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
    if (phaseId == "implement_fix" && git.goalReviewTrackedDelta.isEmpty()) {
      git.goalReviewTrackedDelta = "remediation-progress\n"
    }
    facts(validJsonOutput(phaseId))
  }

internal const val COMMITTED_HEAD_SHA = "ffffffffffffffffffffffffffffffffffffffff"

internal fun committedRepoBranchSetup(): BranchSetupTestConfig =
  BranchSetupTestConfig(
    gitOperations = RecordingWorkflowGitOperations().also { it.headCommitShaValue = COMMITTED_HEAD_SHA },
  )

internal data class BranchSetupTestConfig(
  val gitOperations: RecordingWorkflowGitOperations = RecordingWorkflowGitOperations(),
  val specReference: String = SPEC_REFERENCE,
  val featureSize: FeatureTaskRuntimeFeatureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
)

internal data class RuntimeHarnessConfig(
  val branchSetup: BranchSetupTestConfig = BranchSetupTestConfig(),
  val repoRoot: Path = Path.of("/tmp/repo"),
  val environment: Map<String, String> = emptyMap(),
  val goalContinuation: FeatureTaskRuntimeGoalContinuationContext? = null,
  val eventSink: FeatureTaskRuntimeRunEventSink? = null,
  val acceptanceCriteria: List<String> = listOf("AC-1", "AC-2"),
  val planningProjectionValidator: FeatureTaskRuntimeWireArtifactValidator =
    AcceptingFeatureTaskRuntimeWireArtifactValidator,
  val buildReceiptValidator: FeatureTaskRuntimeWireArtifactValidator =
    AcceptingFeatureTaskRuntimeWireArtifactValidator,
  val codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DEFAULT,
  val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort =
    DERIVING_SHARED_EVIDENCE_RESOLVER,
  val diffResolver: DiffResolverPort =
    object : DiffResolverPortDefaults() {
      override fun runProcess(
        args: List<String>,
        workDir: Path,
      ): String? = null
    },
  val validationGateRunner: ValidationGateRunner? = null,
  val validationGatePlatformManifests: List<PlatformManifest> = listOf(kotlinPackWithValidationGate()),
  val reviewDriver: FeatureTaskRuntimeReviewDriver =
    ApprovingReviewDriverStub,
  val launcher: RuntimeRecordingLauncher? = null,
  val agentAssignment: FeatureTaskRuntimeAgentAssignment? = null,
  val validator: FeatureTaskRuntimePhaseOutputValidator? = null,
  val diagnostics: RuntimeDiagnostics? = null,
)

private fun runtimeSpecSourceResolver(): SpecSourceResolver =
  SpecSourceResolver(TestDecompositionManifestStore, testDecompositionManifestValidator)

private data class RuntimePhaseGatesDeps(
  val branchSetupRunner: FeatureTaskRuntimeBranchSetupRunner,
  val planningStopper: FeatureTaskRuntimePlanningStopper,
  val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry,
  val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  val specGate: FeatureTaskRuntimeSpecGate = testSpecGate(),
  val planningProjectionValidator: FeatureTaskRuntimeWireArtifactValidator =
    AcceptingFeatureTaskRuntimeWireArtifactValidator,
  val buildReceiptValidator: FeatureTaskRuntimeWireArtifactValidator =
    AcceptingFeatureTaskRuntimeWireArtifactValidator,
  val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort =
    DERIVING_SHARED_EVIDENCE_RESOLVER,
  val diffResolver: DiffResolverPort =
    object : DiffResolverPortDefaults() {
      override fun runProcess(
        args: List<String>,
        workDir: Path,
      ): String? = null
    },
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val validationGateRunnerOverride: ValidationGateRunner? = null,
  val validationGatePlatformManifests: List<PlatformManifest> = listOf(kotlinPackWithValidationGate()),
  val reviewDriver: FeatureTaskRuntimeReviewDriver = ApprovingReviewDriverStub,
)

private fun runtimePhaseGates(deps: RuntimePhaseGatesDeps): FeatureTaskRuntimePhaseGates {
  val validationGateResolver =
    ValidationGateResolver { deps.validationGatePlatformManifests }
  val validationGateRunner =
    deps.validationGateRunnerOverride
      ?: object : ValidationGateRunner {
        override fun run(request: ValidationGateRunRequest) =
          ValidationGateRunResult(
            exitCode = 0,
            durationMs = 1,
            outcome = PASSED,
            cacheMode = request.cacheMode,
            executedWorkUnits = 1,
            executedCheckIdentities = emptyList(),
            findings = emptyList(),
          )
      }
  return FeatureTaskRuntimePhaseGates(
    FeatureTaskRuntimePhaseGateBranchBoundaries(
      branchSetupRunner = deps.branchSetupRunner,
      planningStopper = deps.planningStopper,
      lifecycleTelemetry = deps.lifecycleTelemetry,
      gitOperations = deps.gitOperations,
      specGate = deps.specGate,
    ),
    validationGateBoundaries(deps, validationGateResolver, validationGateRunner),
  )
}

private fun validationGateBoundaries(
  deps: RuntimePhaseGatesDeps,
  validationGateResolver: ValidationGateResolver,
  validationGateRunner: ValidationGateRunner,
): FeatureTaskRuntimePhaseGateValidationBoundaries =
  FeatureTaskRuntimePhaseGateValidationBoundaries(
    planningProjectionValidator = deps.planningProjectionValidator,
    buildReceiptValidator = deps.buildReceiptValidator,
    validationGateResolver = validationGateResolver,
    validationGateRunner = validationGateRunner,
    validationGateCoordinator = FeatureTaskRuntimeValidationGateCoordinator(),
    readinessGateCoordinator =
      FeatureTaskRuntimeReadinessGateCoordinator(
        ReadinessCheckSelection(
          GitHubPullRequestCheckDiscovery(),
        ),
        object : PrCheckProcessRunner {
          override fun run(
            command: String,
            repoRoot: Path,
          ): PrCheckRunResult = PrCheckRunResult(exitCode = 0, durationMs = 1)
        },
        deps.recorder,
        NoopRuntimeDiagnostics,
      ),
    buildGateCoordinator =
      FeatureTaskRuntimeBuildGateCoordinator(
        validationGateResolver,
        validationGateRunner,
        FeatureTaskRuntimeBuildGateProgressStore(deps.recorder),
        defaultRepoLocalConfigPort(),
        NoopRuntimeDiagnostics,
      ),
    sharedEvidenceResolver = deps.sharedEvidenceResolver,
    diffResolver = deps.diffResolver,
    reviewDriver = deps.reviewDriver,
    specIntentProjectionResolver =
      SpecIntentProjectionResolver(
        TestDecompositionManifestStore,
        testDecompositionManifestValidator,
        SpecIntentProjectionExtractor(
          ReviewContextEnvelopeValidator { _, _ -> },
          TestDecompositionManifestStore,
        ),
      ),
    findingVerificationBoundaryMemory =
      FeatureTaskRuntimeFindingVerificationBoundaryMemory(
        FileSystemGoalPlanningContextDiscovery(JvmSystemClock),
        FileSystemGoalPlanningBoundaryBodyResolver(),
      ),
  )

private fun defaultRepoLocalConfigPort(): RepoLocalConfigPort =
  object : RepoLocalConfigPort {
    override fun readRepoLocalConfig(request: ReadRepoLocalConfigRequest) =
      ReadRepoLocalConfigResult(RepoLocalConfig.defaults())
  }

private fun testSpecGate(
  specScratchStore: SpecScratchStore = RecordingSpecScratchStore(),
  specStatusWriter: FeatureTaskRuntimeSpecStatusWriter = RecordingSpecStatusWriter(),
): FeatureTaskRuntimeSpecGate =
  FeatureTaskRuntimeSpecGate(runtimeSpecSourceResolver(), specScratchStore, specStatusWriter, NoopRuntimeDiagnostics)

private fun disabledRuntimeLifecycleTelemetry(database: DatabaseSessionFactory): FeatureTaskRuntimeLifecycleTelemetry =
  FeatureTaskRuntimeLifecycleTelemetry(
    LifecycleTelemetryService(
      database,
      DisabledRuntimeTelemetrySettingsProvider,
      Clock.systemUTC(),
      NoopRuntimeDiagnostics,
    ),
    NoopRuntimeDiagnostics,
  )

private object DisabledRuntimeTelemetrySettingsProvider : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings =
    TelemetrySettings(
      configPath = Path.of("/fake/config.json").toFileLocation(),
      level = "off",
      enabled = false,
      installId = "",
      proxyUrl = "",
      customProxyUrl = null,
      batchSize = 50,
    )
}

internal fun smallRuntimeConfig(): RuntimeHarnessConfig =
  RuntimeHarnessConfig(
    branchSetup = BranchSetupTestConfig(featureSize = FeatureTaskRuntimeFeatureSize.SMALL),
  )

internal fun conventionRuntimeConfig(git: RecordingWorkflowGitOperations): RuntimeHarnessConfig =
  RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(git, CONVENTION_SPEC_REFERENCE))

private fun runnerHarnessRequest(
  runtimeConfig: RuntimeHarnessConfig,
  agentAssignment: FeatureTaskRuntimeAgentAssignment,
  sink: FeatureTaskRuntimeRunEventSink,
): FeatureTaskRuntimeRunRequest =
  FeatureTaskRuntimeRunRequest(
    issueKey = ISSUE_KEY,
    workflowId = WORKFLOW_ID,
    sessionId = SESSION_ID,
    runInvariants =
      FeatureTaskRuntimeRunInvariants(
        specReference = runtimeConfig.branchSetup.specReference,
        featureSize = runtimeConfig.branchSetup.featureSize,
        acceptanceCriteria = runtimeConfig.acceptanceCriteria,
        mandatesAndOverrides = listOf("mandate-X"),
        codeReviewMode = runtimeConfig.codeReviewMode,
      ),
    invokedAgentId = INVOKED_AGENT,
    agentAssignment = agentAssignment,
    environment = runtimeConfig.environment,
    repoRoot = runtimeConfig.repoRoot,
    goalContinuation = runtimeConfig.goalContinuation,
    eventSink = sink,
  )

internal data class RunnerHarnessSupervision(
  val crashSupervisor: FeatureTaskRuntimeWorkerSupervisor = HarnessDeadProcessSupervisor,
  val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
)

internal data class RunnerHarnessCore(
  val launcher: RuntimeRecordingLauncher = defaultPhaseAwareLauncher(),
  val validator: FeatureTaskRuntimePhaseOutputValidator = AlwaysValidValidator,
  val agentAssignment: FeatureTaskRuntimeAgentAssignment = FeatureTaskRuntimeAgentAssignment(),
)

private fun resolvedHarnessSupervision(
  runtimeConfig: RuntimeHarnessConfig,
  supervision: RunnerHarnessSupervision,
): RunnerHarnessSupervision = runtimeConfig.diagnostics?.let { supervision.copy(diagnostics = it) } ?: supervision

private fun harnessPhaseRecorder(database: RuntimeFakeDatabaseSessionFactory): FeatureTaskRuntimePhaseRecorder =
  featureTaskRuntimePhaseRecorder(
    database,
    NoopWorkflowSnapshotValidator,
    AcceptingFeatureTaskRuntimeWireArtifactValidator,
    AcceptingFeatureTaskRuntimeWireArtifactValidator,
    testHarnessClock,
    NoopRuntimeDiagnostics,
  )

private fun harnessGoalContinuationRecorder(
  database: RuntimeFakeDatabaseSessionFactory,
): FeatureTaskRuntimeGoalContinuationRecorder =
  FeatureTaskRuntimeGoalContinuationRecorder(
    database,
    NoopRuntimeDiagnostics,
    Clock.systemUTC(),
  )

private fun harnessWorkflowParts(database: RuntimeFakeDatabaseSessionFactory): RunnerHarnessWorkflow =
  RunnerHarnessWorkflow(
    recorder = harnessPhaseRecorder(database),
    goalContinuationRecorder = harnessGoalContinuationRecorder(database),
    decomposeTerminalRecorder =
      FeatureTaskRuntimeDecomposeTerminalRecorder(
        database,
        testHarnessClock,
      ),
    runInvariantsStore =
      FeatureTaskRuntimeRunInvariantsStore(
        database,
        FeatureTaskRuntimeWorkflowPersistence(database, NoopWorkflowSnapshotValidator),
      ),
  )

private fun harnessCrashReconciler(
  database: DatabaseSessionFactory,
  supervisor: FeatureTaskRuntimeWorkerSupervisor,
): FeatureTaskRuntimeCrashReconciler =
  FeatureTaskRuntimeCrashReconciler(
    database,
    supervisor,
    NoopRuntimeDiagnostics,
    testHarnessClock,
  )

private fun harnessPhaseSettlement(): FeatureTaskPhaseSettlementService =
  FeatureTaskPhaseSettlementService(
    InMemoryFeatureTaskPhaseSettlementRepository(),
    testHarnessClock,
  )

internal fun runnerHarness(
  runtimeConfig: RuntimeHarnessConfig = RuntimeHarnessConfig(),
  core: RunnerHarnessCore = RunnerHarnessCore(),
  repository: InMemoryRuntimeWorkflowRepository = InMemoryRuntimeWorkflowRepository(),
  supervision: RunnerHarnessSupervision = RunnerHarnessSupervision(),
): RunnerHarness {
  val launcher = runtimeConfig.launcher ?: core.launcher
  val validator = runtimeConfig.validator ?: core.validator
  val agentAssignment = runtimeConfig.agentAssignment ?: core.agentAssignment
  val resolvedSupervision = resolvedHarnessSupervision(runtimeConfig, supervision)
  harnessPendingVerifyFindingIds = emptyList()
  seedHarnessSpecIntentProjection(runtimeConfig.repoRoot, runtimeConfig.branchSetup.specReference)
  val specScratchStore = RecordingSpecScratchStore()
  val specStatusWriter = RecordingSpecStatusWriter()
  val database = RuntimeFakeDatabaseSessionFactory(repository)
  val workflow = harnessWorkflowParts(database)
  val runner =
    harnessRunner(
      HarnessRunnerDeps(
        launcher = launcher,
        recorder = workflow.recorder,
        goalContinuationRecorder = workflow.goalContinuationRecorder,
        runInvariantsStore = workflow.runInvariantsStore,
        validator = validator,
        runtimeConfig = runtimeConfig,
        database = database,
        crashSupervisor = resolvedSupervision.crashSupervisor,
        diagnostics = resolvedSupervision.diagnostics,
        specScratchStore = specScratchStore,
        specStatusWriter = specStatusWriter,
        decomposeTerminalRecorder = workflow.decomposeTerminalRecorder,
      ),
    )
  val captured = mutableListOf<FeatureTaskRuntimeRunEvent>()
  val sink =
    FeatureTaskRuntimeRunEventSink { event ->
      captured += event
      runtimeConfig.eventSink?.emit(event)
    }
  val runRequest = runnerHarnessRequest(runtimeConfig, agentAssignment, sink)
  val io =
    RunnerHarnessIo(
      workflow = workflow,
      repository = repository,
      gitOperations = runtimeConfig.branchSetup.gitOperations,
      specStatusWriter = specStatusWriter,
      database = database,
    )
  return RunnerHarness(launcher, io, runner, captured, runRequest, specScratchStore)
}

private data class HarnessRunnerDeps(
  val launcher: RuntimeRecordingLauncher,
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
  val validator: FeatureTaskRuntimePhaseOutputValidator,
  val runtimeConfig: RuntimeHarnessConfig,
  val database: RuntimeFakeDatabaseSessionFactory,
  val crashSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  val diagnostics: RuntimeDiagnostics,
  val specScratchStore: RecordingSpecScratchStore,
  val specStatusWriter: RecordingSpecStatusWriter,
  val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
)

private fun harnessRunner(deps: HarnessRunnerDeps): FeatureTaskRuntimeRunner {
  val branchSetupRunner =
    FeatureTaskRuntimeBranchSetupRunner(
      deps.recorder,
      deps.runtimeConfig.branchSetup.gitOperations,
    )
  val decompositionPlanner = testDecompositionPlanner()
  val planningStopper =
    FeatureTaskRuntimePlanningStopper(
      deps.validator,
      decompositionPlanner,
      deps.decomposeTerminalRecorder,
      deps.diagnostics,
    )
  return FeatureTaskRuntimeRunner(
    subtaskLauncher = deps.launcher,
    recorder = deps.recorder,
    goalContinuationRecorder = deps.goalContinuationRecorder,
    runInvariantsStore = deps.runInvariantsStore,
    outputValidator = deps.validator,
    phaseGates =
      runtimePhaseGates(
        RuntimePhaseGatesDeps(
          branchSetupRunner = branchSetupRunner,
          planningStopper = planningStopper,
          lifecycleTelemetry = disabledRuntimeLifecycleTelemetry(deps.database),
          gitOperations = deps.runtimeConfig.branchSetup.gitOperations,
          specGate = testSpecGate(deps.specScratchStore, deps.specStatusWriter),
          planningProjectionValidator = deps.runtimeConfig.planningProjectionValidator,
          buildReceiptValidator = deps.runtimeConfig.buildReceiptValidator,
          sharedEvidenceResolver = deps.runtimeConfig.sharedEvidenceResolver,
          diffResolver = deps.runtimeConfig.diffResolver,
          recorder = deps.recorder,
          validationGateRunnerOverride = deps.runtimeConfig.validationGateRunner,
          validationGatePlatformManifests = deps.runtimeConfig.validationGatePlatformManifests,
          reviewDriver = harnessReviewDriverSyncingPendingVerifyFindings(deps.runtimeConfig.reviewDriver),
        ),
      ),
    crashReconciler = harnessCrashReconciler(deps.database, deps.crashSupervisor),
    phaseSettlementService = harnessPhaseSettlement(),
    diagnostics = deps.diagnostics,
    clock = testHarnessClock,
    probeWriters =
      FeatureTaskRuntimeProbeWriters(
        activityStampWriter =
          AgentActivityStampWriter(deps.database, Clock.systemUTC(), deps.diagnostics, TimeSource.Monotonic),
        worktreeEditJournalWriter =
          WorktreeEditJournalWriter(
            deps.database,
            Clock.systemUTC(),
            deps.diagnostics,
            NoopWorkflowGitOperations,
          ),
      ),
  )
}

internal class TelemetryRunnerHarness(
  val runner: FeatureTaskRuntimeRunner,
  val lifecycle: RecordingLifecycleTelemetryRepository,
  val request: FeatureTaskRuntimeRunRequest,
  val database: RuntimeFakeDatabaseSessionFactory,
  val recorder: FeatureTaskRuntimePhaseRecorder,
) {
  fun seedPhase(
    phaseId: String,
    status: String,
    attemptCount: Int,
    agentId: String,
    outputArtifact: String?,
  ) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseStateForTest(phaseId, status, attemptCount, agentId, outputArtifact)
  }
}

private fun telemetryHarnessRequest(runtimeConfig: RuntimeHarnessConfig): FeatureTaskRuntimeRunRequest =
  FeatureTaskRuntimeRunRequest(
    issueKey = ISSUE_KEY,
    workflowId = WORKFLOW_ID,
    sessionId = SESSION_ID,
    runInvariants =
      FeatureTaskRuntimeRunInvariants(
        specReference = runtimeConfig.branchSetup.specReference,
        featureSize = runtimeConfig.branchSetup.featureSize,
        acceptanceCriteria = listOf("AC-1", "AC-2"),
        mandatesAndOverrides = listOf("mandate-X"),
      ),
    invokedAgentId = INVOKED_AGENT,
    repoRoot = runtimeConfig.repoRoot,
  )

internal fun telemetryRunnerHarness(runtimeConfig: RuntimeHarnessConfig): TelemetryRunnerHarness =
  telemetryRunnerHarness(
    launcher =
      runtimeConfig.launcher
        ?: RuntimeRecordingLauncher { request -> facts(defaultPhaseOutput(request)) },
    validator = runtimeConfig.validator ?: AlwaysValidValidator,
    runtimeConfig = runtimeConfig,
  )

internal fun telemetryRunnerHarness(
  launcher: RuntimeRecordingLauncher = RuntimeRecordingLauncher { request -> facts(defaultPhaseOutput(request)) },
  validator: FeatureTaskRuntimePhaseOutputValidator = AlwaysValidValidator,
  runtimeConfig: RuntimeHarnessConfig = RuntimeHarnessConfig(),
): TelemetryRunnerHarness {
  val effectiveLauncher = runtimeConfig.launcher ?: launcher
  val effectiveValidator = runtimeConfig.validator ?: validator
  seedHarnessSpecIntentProjection(runtimeConfig.repoRoot, runtimeConfig.branchSetup.specReference)
  val repository = InMemoryRuntimeWorkflowRepository()
  val lifecycle = RecordingLifecycleTelemetryRepository()
  val database = RuntimeFakeDatabaseSessionFactory(repository, lifecycle)
  val workflow = harnessWorkflowParts(database)
  val runner =
    telemetryHarnessRunner(
      launcher = effectiveLauncher,
      validator = effectiveValidator,
      runtimeConfig = runtimeConfig,
      database = database,
      workflow = workflow,
    )
  return TelemetryRunnerHarness(
    runner,
    lifecycle,
    telemetryHarnessRequest(runtimeConfig),
    database,
    workflow.recorder,
  )
}

private fun telemetryHarnessRunner(
  launcher: RuntimeRecordingLauncher,
  validator: FeatureTaskRuntimePhaseOutputValidator,
  runtimeConfig: RuntimeHarnessConfig,
  database: RuntimeFakeDatabaseSessionFactory,
  workflow: RunnerHarnessWorkflow,
): FeatureTaskRuntimeRunner {
  val branchSetupRunner =
    FeatureTaskRuntimeBranchSetupRunner(
      workflow.recorder,
      runtimeConfig.branchSetup.gitOperations,
    )
  val decompositionPlanner = testDecompositionPlanner()
  val planningStopper =
    FeatureTaskRuntimePlanningStopper(
      validator,
      decompositionPlanner,
      workflow.decomposeTerminalRecorder,
      NoopRuntimeDiagnostics,
    )
  return FeatureTaskRuntimeRunner(
    subtaskLauncher = launcher,
    recorder = workflow.recorder,
    goalContinuationRecorder = workflow.goalContinuationRecorder,
    runInvariantsStore = workflow.runInvariantsStore,
    outputValidator = validator,
    phaseGates =
      telemetryRunnerPhaseGates(
        runtimeConfig,
        database,
        workflow,
        branchSetupRunner,
        planningStopper,
      ),
    crashReconciler = harnessCrashReconciler(database, NoopFeatureTaskRuntimeWorkerSupervisor),
    phaseSettlementService = harnessPhaseSettlement(),
    diagnostics = NoopRuntimeDiagnostics,
    clock = testHarnessClock,
    probeWriters = telemetryRunnerProbeWriters(database),
  )
}

private fun telemetryRunnerPhaseGates(
  runtimeConfig: RuntimeHarnessConfig,
  database: RuntimeFakeDatabaseSessionFactory,
  workflow: RunnerHarnessWorkflow,
  branchSetupRunner: FeatureTaskRuntimeBranchSetupRunner,
  planningStopper: FeatureTaskRuntimePlanningStopper,
): FeatureTaskRuntimePhaseGates =
  runtimePhaseGates(
    RuntimePhaseGatesDeps(
      branchSetupRunner = branchSetupRunner,
      planningStopper = planningStopper,
      lifecycleTelemetry =
        FeatureTaskRuntimeLifecycleTelemetry(
          LifecycleTelemetryService(
            database,
            EnabledRuntimeTelemetrySettingsProvider,
            Clock.systemUTC(),
            NoopRuntimeDiagnostics,
          ),
          NoopRuntimeDiagnostics,
        ),
      gitOperations = runtimeConfig.branchSetup.gitOperations,
      sharedEvidenceResolver = runtimeConfig.sharedEvidenceResolver,
      diffResolver = runtimeConfig.diffResolver,
      recorder = workflow.recorder,
      validationGateRunnerOverride = runtimeConfig.validationGateRunner,
      validationGatePlatformManifests = runtimeConfig.validationGatePlatformManifests,
      reviewDriver = harnessReviewDriverSyncingPendingVerifyFindings(runtimeConfig.reviewDriver),
    ),
  )

private fun telemetryRunnerProbeWriters(database: RuntimeFakeDatabaseSessionFactory): FeatureTaskRuntimeProbeWriters =
  FeatureTaskRuntimeProbeWriters(
    activityStampWriter =
      AgentActivityStampWriter(database, Clock.systemUTC(), NoopRuntimeDiagnostics, TimeSource.Monotonic),
    worktreeEditJournalWriter =
      WorktreeEditJournalWriter(
        database,
        Clock.systemUTC(),
        NoopRuntimeDiagnostics,
        NoopWorkflowGitOperations,
      ),
  )

private fun testDecompositionPlanner(): FeatureTaskRuntimeDecompositionPlanner =
  FeatureTaskRuntimeDecompositionPlanner(
    preparationRuntime = FeatureSpecPreparationRuntime(),
    preparationWriter =
      FeatureSpecPreparationWriter(
        decompositionManifestValidator = testDecompositionManifestValidator,
        fileStore = TestDecompositionManifestStore,
        decompositionManifestWriter = testDecompositionManifestWriter,
      ),
  )

internal fun facts(stdout: String): AgentRunLaunchOutcome =
  agentRunLaunchFacts(
    agent = SupportedAgent.CLAUDE,
    stdout = stdout,
    stderr = "",
  )

private val PHASE_LINE = Regex("^Phase: ([a-z_-]+) ", setOf(RegexOption.MULTILINE))

internal fun phaseIdFromPrompt(prompt: String): String =
  PHASE_LINE.find(prompt)?.groupValues?.get(1) ?: error("Prompt did not contain a phase header: $prompt")

internal fun defaultPhaseAwareLauncher(): RuntimeRecordingLauncher =
  RuntimeRecordingLauncher { request ->
    facts(defaultPhaseOutput(request))
  }

internal fun defaultPhaseOutput(request: GoalRunnerSubtaskLaunchRequest): String {
  val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
  return when {
    FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId) -> validJsonOutput(phaseId)
    phaseId == "preplan" || phaseId == "plan" -> validJsonOutput(phaseId)
    phaseId == "review" -> VALID_REVIEW_OUTPUT
    phaseId == "audit" -> VALID_AUDIT_OUTPUT
    phaseId == "verify_findings" -> verifyFindingsOutput()
    else -> validJsonOutput(phaseId)
  }
}

internal const val PLAN_FIX_CAP = 2

internal val PLAN_FIX_CYCLE =
  FeatureTaskRuntimeTransitionDeclaration(
    forwardPhaseIds = listOf("preplan", "plan"),
    backwardEdges =
      listOf(
        FeatureTaskRuntimeBackwardEdge(
          fromPhaseId = "plan",
          triggeringVerdict = FeatureTaskRuntimeVerdict("needs_fix"),
          destinationPhaseId = "preplan",
          loopId = "plan-fix",
          perEdgeCap = PLAN_FIX_CAP,
        ),
      ),
  )
internal const val IMPLEMENT_FIX_CAP = 2

internal val IMPLEMENT_FIX_CYCLE =
  FeatureTaskRuntimeTransitionDeclaration(
    forwardPhaseIds = listOf("preplan", "plan", "implement", "simplify", "audit", "review"),
    backwardEdges =
      listOf(
        FeatureTaskRuntimeBackwardEdge(
          fromPhaseId = "review",
          triggeringVerdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
          destinationPhaseId = "implement",
          loopId = "implement-fix",
          perEdgeCap = IMPLEMENT_FIX_CAP,
        ),
      ),
  )

internal fun verdictReviewOutput(verdict: String): String =
  """
  {
    "contract_version": "0.3",
    "phase_id": "review",
    "status": "completed",
    "summary": "Review produced a validated output.",
    "verdict": "$verdict",
    "produced_outputs": {}
  }
  """.trimIndent()

internal const val REVIEW_BLOCKER_MESSAGE = "Foo.kt leaks a connection in the error path"

internal fun reviewFindingsOutput(
  changesRequested: Boolean,
  dispositionedBlockerIds: List<String> = emptyList(),
): String {
  val findings =
    if (changesRequested) {
      """{"severity": "blocker", "finding_id": "$REVIEW_FIX_BLOCKER_FINDING_ID", """ +
        """"message": "$REVIEW_BLOCKER_MESSAGE"}"""
    } else {
      ""
    }
  val dispositions =
    dispositionedBlockerIds.joinToString(", ") { findingId ->
      """{"finding_id": "$findingId", "verdict": "${if (changesRequested) "unresolved" else "resolved"}", """ +
        """"evidence": ["Foo.kt:42 in the remediation delta"]}"""
    }
  return """
    {
      "contract_version": "0.3",
      "phase_id": "review",
      "status": "completed",
      "summary": "Review produced a validated output.",
      "produced_outputs": {"findings": [$findings], "blocker_dispositions": [$dispositions]}
    }
    """.trimIndent()
}

internal fun reviewFixDriver(convergeOnReview: Int): FeatureTaskRuntimeReviewDriver {
  var reviewPasses = 0
  return FeatureTaskRuntimeReviewDriver { request ->
    reviewPasses += 1
    val findings =
      if (reviewPasses < convergeOnReview) {
        listOf(
          ParallelReviewMergedFinding(
            fNumber = REVIEW_FIX_BLOCKER_FINDING_ID,
            agentIds = listOf(request.agent1Id),
            severity = BLOCKER,
            confidence = "High",
            location = "Foo.kt:1",
            description = REVIEW_BLOCKER_MESSAGE,
          ),
        )
      } else {
        emptyList()
      }
    harnessPendingVerifyFindingIds = findings.map { it.fNumber }
    ApprovingReviewDriverStub.run(request).copy(
      mergeResult =
        ParallelReviewMergeResult(
          findings = findings,
          formattedOutput = if (findings.isEmpty()) "NO_FINDINGS" else "findings",
        ),
    )
  }
}

internal fun reviewFixRuntimeConfig(
  convergeOnReview: Int,
  gitOperations: RecordingWorkflowGitOperations = RecordingWorkflowGitOperations(),
): RuntimeHarnessConfig =
  RuntimeHarnessConfig(
    branchSetup = BranchSetupTestConfig(gitOperations = gitOperations),
    reviewDriver = reviewFixDriver(convergeOnReview),
  )

internal fun crashingRemediationReviewDriver(): FeatureTaskRuntimeReviewDriver {
  var reviewPasses = 0
  return FeatureTaskRuntimeReviewDriver { request ->
    reviewPasses += 1
    when (reviewPasses) {
      2 ->
        ApprovingReviewDriverStub.run(request).copy(
          lane1 =
            ParallelReviewLaneStatus(
              agentId = request.agent1Id,
              success = false,
              failureReason = "spawn failed",
            ),
        )
      else -> {
        val findings =
          if (reviewPasses == 1) {
            listOf(
              ParallelReviewMergedFinding(
                fNumber = "F-001",
                agentIds = listOf(request.agent1Id),
                severity = BLOCKER,
                confidence = "High",
                location = "Foo.kt:1",
                description = REVIEW_BLOCKER_MESSAGE,
              ),
            )
          } else {
            emptyList()
          }
        ApprovingReviewDriverStub.run(request).copy(
          mergeResult =
            ParallelReviewMergeResult(
              findings = findings,
              formattedOutput = if (findings.isEmpty()) "NO_FINDINGS" else "findings",
            ),
        )
      }
    }
  }
}

internal fun throwingBudgetReviewDriver(): FeatureTaskRuntimeReviewDriver =
  FeatureTaskRuntimeReviewDriver {
    throw ReviewContextBudgetExceededException(
      ReviewContextBudgetExceeded(
        lane = "architecture",
        budgetKind = ReviewBudgetKind.PARENT_PACKET_BYTES,
        configuredLimit = 524_288,
        observedValue = 584_846,
        packetDigest = "a".repeat(64),
        assignmentDigest = "b".repeat(64),
        enforceable = true,
      ),
    )
  }

internal fun failingReviewDriver(
  failOnPass: Int,
  failureReason: String,
): FeatureTaskRuntimeReviewDriver {
  var reviewPasses = 0
  return FeatureTaskRuntimeReviewDriver { request ->
    reviewPasses += 1
    if (reviewPasses == failOnPass) {
      ApprovingReviewDriverStub.run(request).copy(
        lane1 =
          ParallelReviewLaneStatus(
            agentId = request.agent1Id,
            success = false,
            failureReason = failureReason,
          ),
      )
    } else {
      ApprovingReviewDriverStub.run(request)
    }
  }
}

internal fun crashingReviewFixDriver(
  convergeOnReview: Int,
  crashOnPass: Int,
  shouldCrash: () -> Boolean,
): FeatureTaskRuntimeReviewDriver {
  var reviewPasses = 0
  return FeatureTaskRuntimeReviewDriver { request ->
    reviewPasses += 1
    if (shouldCrash() && reviewPasses == crashOnPass) {
      ApprovingReviewDriverStub.run(request).copy(
        lane1 =
          ParallelReviewLaneStatus(
            agentId = request.agent1Id,
            success = false,
            failureReason = "spawn failed",
          ),
      )
    } else {
      val findings =
        if (reviewPasses < convergeOnReview) {
          listOf(
            ParallelReviewMergedFinding(
              fNumber = "F-001",
              agentIds = listOf(request.agent1Id),
              severity = BLOCKER,
              confidence = "High",
              location = "Foo.kt:1",
              description = REVIEW_BLOCKER_MESSAGE,
            ),
          )
        } else {
          emptyList()
        }
      ApprovingReviewDriverStub.run(request).copy(
        mergeResult =
          ParallelReviewMergeResult(
            findings = findings,
            formattedOutput = if (findings.isEmpty()) "NO_FINDINGS" else "findings",
          ),
      )
    }
  }
}

internal fun reviewFixLauncher(
  convergeOnReview: Int,
  onReviewLaunch: (Int) -> Unit = {},
  onPhaseLaunch: (String) -> Unit = {},
): RuntimeRecordingLauncher {
  var reviewLaunches = 0
  return RuntimeRecordingLauncher { request ->
    val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
    onPhaseLaunch(phaseId)
    if (phaseId == "review") {
      reviewLaunches += 1
      onReviewLaunch(reviewLaunches)
      val changesRequested = reviewLaunches < convergeOnReview
      harnessPendingVerifyFindingIds = if (changesRequested) listOf(REVIEW_FIX_BLOCKER_FINDING_ID) else emptyList()
      facts(
        reviewFindingsOutput(
          changesRequested = changesRequested,
          dispositionedBlockerIds = if (reviewLaunches > 1) listOf("pass1-blocker-1") else emptyList(),
        ),
      )
    } else {
      facts(validJsonOutput(phaseId))
    }
  }
}

internal val COMMIT_PUSH_NO_SHA_OUTPUT: String =
  """
  {
    "contract_version": "0.3",
    "phase_id": "commit_push",
    "status": "completed",
    "summary": "Phase produced a validated output.",
    "produced_outputs": {"commit_push_result": {"status": "committed"}}
  }
  """.trimIndent()

internal val COMMIT_PUSH_BLOCKED_OUTPUT: String =
  """
  {
    "contract_version": "0.3",
    "phase_id": "commit_push",
    "status": "blocked",
    "summary": "Validation failed before commit.",
    "produced_outputs": {
      "commit_push_result": {
        "commit_sha": null,
        "pushed_status": "not_attempted"
      },
      "blocking_reasons": ["Working tree contains unrelated changes."]
    }
  }
  """.trimIndent()

internal val VALIDATE_BLOCKED_OUTPUT: String =
  """
  {
    "contract_version": "0.3",
    "phase_id": "validate",
    "status": "blocked",
    "summary": "Validation failed before finalization.",
    "produced_outputs": {
      "validation_result": "fail",
      "blocking_reasons": ["Repository validation still fails."]
    }
  }
  """.trimIndent()

internal val VALIDATE_BLOCKED_NEEDS_USER_ACTION_OUTPUT: String =
  """
  {
    "contract_version": "0.3",
    "phase_id": "validate",
    "status": "blocked",
    "failure_disposition": "needs_user_action",
    "summary": "Docker daemon unavailable.",
    "produced_outputs": {
      "validation_result": "fail",
      "blocking_reasons": ["Cannot connect to docker.sock."]
    }
  }
  """.trimIndent()

internal val BUILD_BLOCKED_NEEDS_USER_ACTION_OUTPUT: String =
  """
  {
    "contract_version": "0.3",
    "phase_id": "build",
    "status": "blocked",
    "failure_disposition": "needs_user_action",
    "summary": "Gradle daemon unavailable.",
    "produced_outputs": {
      "blocking_reasons": ["Cannot connect to Gradle daemon."]
    }
  }
  """.trimIndent()

internal fun failThenPassValidationGateRunner(gateCalls: AtomicInteger): ValidationGateRunner =
  object : ValidationGateRunner {
    override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
      val call = gateCalls.getAndIncrement()
      val outcome =
        if (call == 0) {
          FAILED
        } else {
          PASSED
        }
      return ValidationGateRunResult(
        exitCode = if (call == 0) 1 else 0,
        durationMs = 1,
        outcome = outcome,
        cacheMode =
          if (call == 0) {
            CACHE_ELIGIBLE
          } else {
            request.cacheMode
          },
        executedWorkUnits = 1,
        executedCheckIdentities = emptyList(),
        findings =
          if (call == 0) {
            listOf(
              ValidationGateFinding("app", "t", "broken", "A.kt"),
            )
          } else {
            emptyList()
          },
      )
    }
  }

internal fun kotlinPackWithValidationGate(): PlatformManifest =
  PlatformManifest(
    slug = "kotlin",
    packRoot = Path.of("/tmp/repo/platform-packs/kotlin").toFileLocation(),
    contractVersion = "1.8",
    routingSignals =
      RoutingSignals(
        strong = listOf("src"),
        tieBreakers = emptyList(),
        path = listOf("src"),
      ),
    declaredCodeReviewAreas = emptyList(),
    declaredFiles = DeclaredFiles(null, emptyMap()),
    areaMetadata = emptyMap(),
    validationGate =
      ValidationGateDeclaration(
        fullGateCommand = listOf("echo", "cache"),
        cacheBypassingFullGateCommand = listOf("echo", "full"),
        collectAllFullGateCommand = listOf("echo", "collect-all"),
        cacheBypassingCollectAllFullGateCommand = listOf("echo", "collect-all-full"),
        findings =
          ValidationGateFindingsLocator(
            format = JUNIT_XML,
            artifactGlobs = listOf("**/*.xml"),
            compilerDiagnostics =
              ValidationGateCompilerDiagnosticsLocator(
                GRADLE_KOTLIN_COMPILER_STDOUT,
              ),
            executedWork =
              ValidationGateExecutedWorkSignal(
                GRADLE_ACTIONABLE_SUMMARY,
              ),
          ),
      ),
  )

internal fun kotlinPackWithBuildGate(): PlatformManifest =
  kotlinPackWithValidationGate().let { pack ->
    pack.copy(
      validationGate =
        pack.validationGate!!.copy(
          buildCommand = listOf("echo", "build"),
          cacheBypassingBuildCommand = listOf("echo", "build-full"),
        ),
    )
  }

internal fun goalContinuationLauncher(commitPushOutput: String): RuntimeRecordingLauncher =
  RuntimeRecordingLauncher { request ->
    val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
    facts(if (phaseId == "commit_push") commitPushOutput else validJsonOutput(phaseId))
  }

internal fun goalContinuationHarness(
  repoRoot: Path,
  git: RecordingWorkflowGitOperations,
  launcher: RuntimeRecordingLauncher,
  reviewDriver: FeatureTaskRuntimeReviewDriver =
    ApprovingReviewDriverStub,
): RunnerHarness =
  runnerHarness(
    runtimeConfig =
      RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        repoRoot = repoRoot,
        goalContinuation =
          FeatureTaskRuntimeGoalContinuationContext(
            parentIssueKey = ISSUE_KEY,
            subtaskId = 5,
            goalBranch = "feat/existing-runtime-branch",
            suppressPr = true,
            parentWorkflowId = "wfl-parent",
            reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
          ),
        reviewDriver = reviewDriver,
      ),
    core = RunnerHarnessCore(launcher = launcher, agentAssignment = phasePerAgentAssignment()),
  )

internal val DECOMPOSE_PLAN_OUTPUT: String =
  """
  {
    "contract_version": "0.3",
    "phase_id": "plan",
    "status": "completed",
    "summary": "Plan needs ordered subtasks.",
    "produced_outputs": {
      "decomposition_package": {
        "mode": "decompose",
        "reason": "Plan needs ordered subtasks.",
        "feature_name": "runtime decomposition parity",
        "parent_spec_overview": "Split the runtime work into ordered subtasks.",
        "validation_strategy": "bill-code-check",
        "base_branch": "main",
        "feature_branch": "feat/SKILL-65-runtime-decomposition-parity",
        "subtasks": [
          {
            "id": 1,
            "name": "domain contracts",
            "scope": "Add typed plan outcome detection.",
            "acceptance_criteria": ["Detect decompose mode."],
            "non_goals": [],
            "dependency_notes": "First subtask.",
            "validation_strategy": "unit tests",
            "next_path": "Work subtask 2 next.",
            "depends_on": []
          },
          {
            "id": 2,
            "name": "runtime stop",
            "scope": "Stop after writing decomposition.",
            "acceptance_criteria": ["Do not advance to implement."],
            "non_goals": [],
            "dependency_notes": "Depends on subtask 1.",
            "validation_strategy": "unit tests",
            "next_path": "Return to the parent workflow.",
            "depends_on": [1]
          }
        ]
      }
    }
  }
  """.trimIndent()
internal val MALFORMED_DECOMPOSE_PLAN_OUTPUT: String =
  """
  {
    "contract_version": "0.3",
    "phase_id": "plan",
    "status": "completed",
    "summary": "Plan needs ordered subtasks.",
    "produced_outputs": {
      "decomposition_package": {
        "mode": "decompose",
        "reason": "Plan needs ordered subtasks.",
        "feature_name": "runtime decomposition parity",
        "parent_spec_overview": "Split the runtime work into ordered subtasks.",
        "validation_strategy": "bill-code-check",
        "base_branch": "main",
        "feature_branch": "feat/SKILL-65-runtime-decomposition-parity",
        "subtasks": [
          {
            "id": 1,
            "name": "domain contracts",
            "scope": "Add typed plan outcome detection.",
            "acceptance_criteria": ["Detect decompose mode."],
            "non_goals": [],
            "dependency_notes": "First subtask.",
            "validation_strategy": "unit tests",
            "next_path": "Work subtask 2 next.",
            "depends_on": []
          },
          {
            "id": 2,
            "scope": "Stop after writing decomposition.",
            "acceptance_criteria": ["Do not advance to implement."],
            "non_goals": [],
            "dependency_notes": "Depends on subtask 1.",
            "validation_strategy": "unit tests",
            "next_path": "Return to the parent workflow.",
            "depends_on": [1]
          }
        ]
      }
    }
  }
  """.trimIndent()
internal val WRITER_INVALID_DECOMPOSE_PLAN_OUTPUT: String =
  """
  {
    "contract_version": "0.3",
    "phase_id": "plan",
    "status": "completed",
    "summary": "Plan needs ordered subtasks.",
    "produced_outputs": {
      "decomposition_package": {
        "mode": "decompose",
        "reason": "Plan needs ordered subtasks.",
        "feature_name": "runtime decomposition parity",
        "parent_spec_overview": "Split the runtime work into ordered subtasks.",
        "validation_strategy": "bill-code-check",
        "base_branch": "main",
        "feature_branch": "feat/SKILL-65-runtime-decomposition-parity",
        "subtasks": [
          {
            "id": 2,
            "name": "runtime stop",
            "scope": "Stop after writing decomposition.",
            "acceptance_criteria": ["Do not advance to implement."],
            "non_goals": [],
            "dependency_notes": "Listed first but ids descend.",
            "validation_strategy": "unit tests",
            "next_path": "Return to the parent workflow.",
            "depends_on": []
          },
          {
            "id": 1,
            "name": "domain contracts",
            "scope": "Add typed plan outcome detection.",
            "acceptance_criteria": ["Detect decompose mode."],
            "non_goals": [],
            "dependency_notes": "Listed second; out of ascending order.",
            "validation_strategy": "unit tests",
            "next_path": "Work subtask 2 next.",
            "depends_on": []
          }
        ]
      }
    }
  }
  """.trimIndent()

internal fun spawnFailedFacts(): AgentRunLaunchOutcome =
  agentRunLaunchFacts(
    agent = SupportedAgent.CLAUDE,
    termination = AgentRunTermination.SpawnFailed,
    stdout = "",
    stderr = "spawn failed",
  )

internal class RuntimeRecordingLauncher(
  private val handler: (GoalRunnerSubtaskLaunchRequest) -> AgentRunLaunchOutcome,
) : GoalRunnerSubtaskLauncher {
  val requests = mutableListOf<GoalRunnerSubtaskLaunchRequest>()

  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome {
    requests += request
    return handler(request)
  }
}

internal class ThrowingValidator(private val failPhases: Set<String>) : FeatureTaskRuntimePhaseOutputTestValidator() {
  override fun validatePhaseOutputText(
    phaseOutputText: String,
    sourceLabel: String,
  ) {
    if (sourceLabel in failPhases) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(sourceLabel, "rejected by fake validator")
    }
  }
}

internal object RepairingImplementOutputValidator : FeatureTaskRuntimePhaseOutputTestValidator() {
  override fun validatePhaseOutputText(
    phaseOutputText: String,
    sourceLabel: String,
  ) = Unit

  override fun validatePhaseOutput(
    phaseOutputText: String,
    sourceLabel: String,
  ): FeatureTaskRuntimePhaseOutputValidationResult {
    if (sourceLabel != "implement") return AlwaysValidValidator.validatePhaseOutput(phaseOutputText, sourceLabel)
    val canonical = validJsonOutput(sourceLabel)
    return FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair(
      normalizedOutput =
        NormalizedFeatureTaskRuntimePhaseOutput(
          canonicalJson = canonical,
          envelope = normalizePhaseOutput(canonical, sourceLabel).envelopeWireMap(),
        ),
      evidence =
        FeatureTaskRuntimePhaseOutputRepairEvidence(
          format = FeatureTaskRuntimePhaseOutputFormat.JSON,
          originalDigest = "a".repeat(64),
          repairedDigest = "b".repeat(64),
          operation = FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
          sourceLocation = FeatureTaskRuntimePhaseOutputSourceLocation(sourceLabel, 0, 1, 1),
        ),
    )
  }
}

internal object CanonicalWrapperTestValidator : FeatureTaskRuntimePhaseOutputTestValidator() {
  private val fencedBlock = Regex("```[ \\t]*[A-Za-z0-9_-]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

  override fun validatePhaseOutputText(
    phaseOutputText: String,
    sourceLabel: String,
  ) {
    parseAndValidate(phaseOutputText, sourceLabel)
  }

  override fun normalizePhaseOutput(
    phaseOutputText: String,
    sourceLabel: String,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val envelope = parseAndValidate(phaseOutputText, sourceLabel)
    return NormalizedFeatureTaskRuntimePhaseOutput(
      canonicalJson = JsonCodec.mapToJsonString(envelope),
      envelope = envelope,
    )
  }

  private fun parseAndValidate(
    phaseOutputText: String,
    sourceLabel: String,
  ): Map<String, Any?> {
    val trimmed = phaseOutputText.trim()
    val candidate =
      fencedBlock.findAll(trimmed).lastOrNull()?.groupValues?.get(1)?.trim()
        ?: trimmed.substring(trimmed.indexOf('{'), trimmed.lastIndexOf('}') + 1)
    val envelope =
      JsonCodec.parseObjectOrNull(candidate)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?: throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(sourceLabel, "test output is not an object")
    if (envelope["phase_id"] != sourceLabel) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(sourceLabel, "phase_id does not match")
    }
    return envelope
  }
}

private fun FeatureTaskRuntimePhaseRecorder.recordPhaseStateForTest(
  phaseId: String,
  status: String,
  attemptCount: Int,
  resolvedAgentId: String,
  outputArtifact: String?,
): Boolean =
  recordPhaseState(
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = WORKFLOW_ID,
      phaseId = phaseId,
      status = status,
      attemptCount = attemptCount,
      resolvedAgentId = resolvedAgentId,
      finished = status == "completed",
      outputArtifact = outputArtifact,
    ),
  )

internal object NoopWorkflowSnapshotValidator : WorkflowSnapshotValidator {
  override fun validate(
    snapshot: WorkflowStateSnapshot,
    slug: String,
  ) = Unit
}

internal data class ProducerEvidenceKey(
  val workflowId: String,
  val phaseId: String,
  val generation: Int,
  val attempt: Int,
  val agentId: String,
  val repairTurn: Int = 0,
)

internal fun samePayload(
  left: ByteArray?,
  right: ByteArray?,
): Boolean = (left == null && right == null) || (left != null && right != null && left.contentEquals(right))

private fun <T> noopPort(type: Class<T>): T {
  @Suppress("UNCHECKED_CAST")
  return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
    defaultPortReturn(method)
  } as T
}

private fun defaultPortReturn(method: Method): Any? =
  when {
    method.returnType == Void.TYPE -> null
    List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
    Map::class.java.isAssignableFrom(method.returnType) -> emptyMap<Any, Any>()
    method.returnType == TYPE -> false
    method.returnType == Integer.TYPE -> 0
    method.returnType == LongTYPE -> 0L
    method.returnType == DoubleTYPE -> 0.0
    else -> null
  }

private fun recordHarnessFindingVerdicts(
  verdicts: MutableList<ReviewFindingVerdict>,
  args: Array<out Any>?,
) {
  @Suppress("UNCHECKED_CAST")
  val incoming = args?.getOrNull(1) as? List<ReviewFindingVerdict> ?: return
  incoming.forEach { verdict ->
    verdicts.removeAll { it.findingRef == verdict.findingRef && it.stage == verdict.stage }
    verdicts += verdict
  }
}

internal fun harnessReviewRepository(): ReviewRepository {
  val verdicts = mutableListOf<ReviewFindingVerdict>()
  return Proxy.newProxyInstance(
    ReviewRepository::class.java.classLoader,
    arrayOf(ReviewRepository::class.java),
  ) { _, method, args ->
    when (method.name) {
      "fetchFindingVerdicts" -> verdicts.toList()
      "recordFindingVerdicts" -> recordHarnessFindingVerdicts(verdicts, args)
      else -> defaultPortReturn(method)
    }
  } as ReviewRepository
}

internal class RuntimeFakeDatabaseSessionFactory(
  private val repository: InMemoryRuntimeWorkflowRepository,
  private val lifecycle: LifecycleTelemetryRepository = RecordingLifecycleTelemetryRepository(),
  private val knownIssue: Boolean = true,
) : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/metrics.db")
  var transactionCount: Int = 0
  val ledgerRows = mutableListOf<UnaddressedFinding>()
  val outcomeRows = mutableListOf<ReviewFindingOutcomeRecord>()
  var producerOutputReadError: RejectedOutputDiagnosticError? = null
  private val diagnosticRecords =
    linkedMapOf<String, RejectedOutputDiagnosticRecord>()
  private val producerEvidence =
    linkedMapOf<ProducerEvidenceKey, ProducerOutputEvidence>()
  private val reviewsPort: ReviewRepository = harnessReviewRepository()
  private val learningsPort: LearningRepository = noopPort(LearningRepository::class.java)
  private val telemetryReconciliationPort: TelemetryReconciliationRepository =
    noopPort(TelemetryReconciliationRepository::class.java)
  private val telemetryOutboxPort: TelemetryOutboxRepository = noopPort(TelemetryOutboxRepository::class.java)

  fun rejectedDiagnostics(): List<RejectedOutputDiagnosticRecord> = diagnosticRecords.values.toList()

  fun retainedProducerEvidence(): List<ProducerOutputEvidence> = producerEvidence.values.toList()

  fun retainProducerEvidence(evidence: ProducerOutputEvidence) {
    unitOfWork().rejectedOutputDiagnostics.retainProducerOutput(evidence)
  }

  fun producerEvidenceAt(key: ProducerEvidenceKey): ProducerOutputEvidence? = producerEvidence[key]

  override fun resolveDbPath(): Path = dbPath

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> transaction(block: (UnitOfWork) -> T): T {
    transactionCount += 1
    return block(unitOfWork())
  }

  private fun unitOfWork(): UnitOfWork =
    object : UnitOfWorkDefaults() {
      override val dbPath: Path = this@RuntimeFakeDatabaseSessionFactory.dbPath
      override val reviews: ReviewRepository = this@RuntimeFakeDatabaseSessionFactory.reviewsPort
      override val learnings: LearningRepository = this@RuntimeFakeDatabaseSessionFactory.learningsPort
      override val lifecycleTelemetry: LifecycleTelemetryRepository = lifecycle
      override val telemetryReconciliation: TelemetryReconciliationRepository =
        this@RuntimeFakeDatabaseSessionFactory.telemetryReconciliationPort
      override val telemetryOutbox: TelemetryOutboxRepository =
        this@RuntimeFakeDatabaseSessionFactory.telemetryOutboxPort
      override val workflowStates: WorkflowStateRepository = repository
      override val rejectedOutputDiagnosticPermissions =
        RejectedOutputDiagnosticPermissions { }
      override val rejectedOutputDiagnostics =
        object : RejectedOutputDiagnosticRepository {
          override fun insert(record: RejectedOutputDiagnosticRecord): RejectedOutputDiagnosticRecord =
            diagnosticRecords.getOrPut(record.metadata.identity) { record }

          override fun select(selector: RejectedOutputDiagnosticSelector): List<RejectedOutputDiagnostic> =
            diagnosticRecords.values
              .map { it.metadata }
              .filter {
                it.workflowId == selector.workflowId &&
                  (selector.phaseId == null || it.phaseId == selector.phaseId) &&
                  (selector.attempt == null || it.attempt == selector.attempt)
              }

          override fun read(identity: String): RejectedOutputDiagnosticRecord =
            diagnosticRecords[identity]
              ?: throw Absent(identity)

          override fun markExpired(before: Instant): Int = 0

          override fun delete(selector: RejectedOutputDiagnosticSelector): Int = 0

          override fun deleteProducerOutputsBefore(before: Instant): Int = 0

          override fun retainProducerOutput(evidence: ProducerOutputEvidence) {
            val key =
              ProducerEvidenceKey(
                evidence.workflowId,
                evidence.phaseId,
                evidence.generation,
                evidence.attempt,
                evidence.agentId,
                evidence.repairTurn,
              )
            producerEvidence.putIfAbsent(key, evidence)
            val retained = producerEvidence.getValue(key)
            if (retained.sha256 != evidence.sha256 || retained.byteSize != evidence.byteSize ||
              !samePayload(retained.payload, evidence.payload)
            ) {
              throw Conflict(
                "${evidence.workflowId}:${evidence.phaseId}:${evidence.generation}:${evidence.attempt}:" +
                  "${evidence.repairTurn}:${evidence.agentId}",
              )
            }
          }

          override fun readProducerOutput(
            workflowId: String,
            phaseId: String,
            attempt: Int,
            agentId: String,
            generation: Int,
          ): ProducerOutputEvidence? {
            producerOutputReadError?.let { throw it }
            return producerEvidence.entries
              .filter {
                it.key.workflowId == workflowId && it.key.phaseId == phaseId &&
                  it.key.attempt == attempt && it.key.agentId == agentId &&
                  it.key.generation <= generation
              }
              .maxWithOrNull(compareBy({ it.key.generation }, { it.key.repairTurn }))
              ?.value
          }
        }
      override val unaddressedFindings =
        object : UnaddressedFindingsRepository {
          override fun replaceLedgerForPass(
            workflowId: String,
            reviewPassNumber: Int,
            findings: List<UnaddressedFinding>,
          ) {
            ledgerRows.removeAll { it.workflowId == workflowId && it.reviewPassNumber <= reviewPassNumber }
            ledgerRows.addAll(findings)
          }

          override fun clearWorkflowLedger(workflowId: String) {
            ledgerRows.removeAll { it.workflowId == workflowId }
          }

          override fun fetchLedger(issueKey: String): List<UnaddressedFinding> =
            ledgerRows.filter { it.issueKey == issueKey }

          override fun fetchWorkflowLedger(workflowId: String): List<UnaddressedFinding> =
            ledgerRows.filter { it.workflowId == workflowId }

          override fun workflowIdsForIssue(issueKey: String): List<String> =
            ledgerRows.filter { it.issueKey == issueKey }.map { it.workflowId }.distinct().sorted()

          override fun recordOutcomes(outcomes: List<ReviewFindingOutcomeRecord>) {
            outcomeRows.removeAll { existing ->
              outcomes.any {
                it.workflowId == existing.workflowId &&
                  it.reviewPassNumber == existing.reviewPassNumber &&
                  it.findingOrdinal == existing.findingOrdinal
              }
            }
            outcomeRows.addAll(outcomes)
          }

          override fun fetchOutcomes(workflowId: String): List<ReviewFindingOutcomeRecord> =
            outcomeRows.filter { it.workflowId == workflowId }

          override fun issueExists(issueKey: String): Boolean = knownIssue
        }
      override val workList = EmptyWorkListRepository
      override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
      override val goalRunnerControls = EmptyGoalRunnerControlRepository
    }
}

internal object EnabledRuntimeTelemetrySettingsProvider : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings =
    TelemetrySettings(
      configPath = Path.of("/fake/config.json").toFileLocation(),
      level = "full",
      enabled = true,
      installId = "install-1",
      proxyUrl = "",
      customProxyUrl = null,
      batchSize = 50,
    )
}

private fun FeatureTaskRuntimeWorkerOwnership.matchesActiveOwnership(
  workflowId: String,
  ownerToken: String,
  generation: Long,
): Boolean =
  this.workflowId == workflowId &&
    this.ownerToken == ownerToken &&
    this.generation == generation &&
    leaseState == FeatureTaskRuntimeWorkerLeaseState.ACTIVE

internal class InMemoryRuntimeWorkflowRepository : WorkflowStateRepositoryDefaults() {
  private var workerOwnership: FeatureTaskRuntimeWorkerOwnership? = null
  private val goalChildWorkflowIds = mutableMapOf<String, List<String>>()

  fun seedGoalChildWorkflowIds(
    parentWorkflowId: String,
    childWorkflowIds: List<String>,
  ) {
    synchronized(this) { goalChildWorkflowIds[parentWorkflowId] = childWorkflowIds }
  }

  override fun listGoalChildWorkflowIdsByParent(parentWorkflowId: String): List<String> =
    synchronized(this) { goalChildWorkflowIds[parentWorkflowId].orEmpty() }

  fun seedWorkerOwnership(ownership: FeatureTaskRuntimeWorkerOwnership) {
    workerOwnership = ownership
  }

  override fun getFeatureTaskRuntimeWorkerOwnership(workflowId: String) =
    synchronized(this) { workerOwnership?.takeIf { it.workflowId == workflowId } }

  override fun acquireFeatureTaskRuntimeWorker(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    expectedUpdatedAt: String?,
  ): Boolean =
    synchronized(this) {
      if (workerOwnership != null || taskRuntimeRows[ownership.workflowId]?.updatedAt != expectedUpdatedAt) return false
      workerOwnership = ownership
      true
    }

  override fun reserveFeatureTaskRuntimeWorkerTakeover(
    workflowId: String,
    expectedOwnerToken: String,
    expectedGeneration: Long,
  ): Boolean =
    synchronized(this) {
      val current = workerOwnership ?: return false
      if (!current.matchesActiveOwnership(workflowId, expectedOwnerToken, expectedGeneration)) return false
      workerOwnership =
        current.copy(
          leaseState = TAKEOVER_RESERVED,
        )
      true
    }

  override fun transferFeatureTaskRuntimeWorker(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    expectedOwnerToken: String,
    expectedGeneration: Long,
  ): Boolean =
    synchronized(this) {
      val current = workerOwnership ?: return false
      if (
        current.ownerToken != expectedOwnerToken || current.generation != expectedGeneration ||
        current.leaseState != TAKEOVER_RESERVED
      ) {
        return false
      }
      workerOwnership = ownership
      true
    }

  override fun heartbeatFeatureTaskRuntimeWorker(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean =
    synchronized(this) {
      val current = workerOwnership ?: return false
      if (current.ownerToken != ownership.ownerToken || current.generation != ownership.generation) return false
      workerOwnership = ownership
      true
    }

  override fun releaseFeatureTaskRuntimeWorker(
    workflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean =
    synchronized(this) {
      val current = workerOwnership ?: return false
      if (current.workflowId != workflowId || current.ownerToken != ownerToken || current.generation != generation) {
        return false
      }
      workerOwnership = null
      true
    }

  override fun findFeatureTaskRuntimeCrashReconciliationCandidates(
    nowInstant: String,
  ): List<FeatureTaskRuntimeCrashReconciliationCandidate> =
    synchronized(this) {
      val ownership = workerOwnership ?: return@synchronized emptyList()
      val row = taskRuntimeRows[ownership.workflowId] ?: return@synchronized emptyList()
      if (row.workflowStatus != "running" || !leaseExpiredBefore(ownership.expiresAt, nowInstant)) {
        return@synchronized emptyList()
      }
      listOf(
        FeatureTaskRuntimeCrashReconciliationCandidate(
          ownership = ownership,
          currentStepId = row.currentStepId,
          workflowStatus = row.workflowStatus,
        ),
      )
    }

  override fun reconcileFeatureTaskRuntimeCrashedWorker(
    workflowId: String,
    ownerToken: String,
    generation: Long,
    interruptionReason: String,
    nowInstant: String,
  ): Boolean =
    synchronized(this) {
      val current = workerOwnership ?: return@synchronized false
      if (current.workflowId != workflowId || current.ownerToken != ownerToken || current.generation != generation) {
        return@synchronized false
      }
      if (!leaseExpiredBefore(current.expiresAt, nowInstant)) return@synchronized false
      val row = taskRuntimeRows[workflowId] ?: return@synchronized false
      if (row.workflowStatus != "running") return@synchronized false
      workerOwnership = null
      taskRuntimeRows[workflowId] = row.copy(workflowStatus = WorkflowStatus.PENDING.wireValue)
      reconciledInterruptionReasons[workflowId] = interruptionReason
      true
    }

  val reconciledInterruptionReasons = linkedMapOf<String, String>()

  private fun leaseExpiredBefore(
    expiresAt: String,
    nowInstant: String,
  ): Boolean =
    runCatching { Instant.parse(expiresAt).isBefore(Instant.parse(nowInstant)) }
      .getOrDefault(false)

  private val identities = linkedMapOf<String, FeatureTaskExecutionIdentity>()

  override fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity) {
    identities[identity.workflowId] = identity
  }

  override fun getFeatureTaskExecutionIdentity(workflowId: String): FeatureTaskExecutionIdentity? =
    identities[workflowId]

  override fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> = emptyList()

  override fun findGoalChildFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> =
    identities.values
      .filter {
        it.normalizedIssueKey == normalizedIssueKey &&
          it.repositoryIdentity == repositoryIdentity &&
          it.routeScope == FeatureTaskRouteScope.GOAL_CHILD
      }
      .mapNotNull { identity ->
        getFeatureTaskWorkflow(identity.workflowId)?.let { FeatureTaskWorkflowCandidate(identity, it) }
      }

  override fun countGoalChildIdentities(normalizedIssueKey: String): Int =
    identities.values.count {
      it.normalizedIssueKey == normalizedIssueKey && it.routeScope == FeatureTaskRouteScope.GOAL_CHILD
    }

  override fun saveFeatureTaskWorkflow(
    row: WorkflowStateRecord,
    mode: FeatureTaskWorkflowMode,
  ) {
    when (mode) {
      FeatureTaskWorkflowMode.RUNTIME -> {
        if (failSaveWhen?.invoke(row) == true) {
          error("simulated process kill during the feature-task-runtime save")
        }
        taskRuntimeRows[row.workflowId] = row
      }
      FeatureTaskWorkflowMode.PROSE -> implementRows[row.workflowId] = row
    }
  }

  override fun getFeatureTaskWorkflow(workflowId: String): WorkflowStateRecord? =
    taskRuntimeRows[workflowId] ?: implementRows[workflowId]

  override fun getFeatureTaskWorkflowAsMode(
    workflowId: String,
    mode: FeatureTaskWorkflowMode,
  ): WorkflowStateRecord? =
    getFeatureTaskWorkflow(workflowId)?.takeIf { row ->
      (row.mode ?: FeatureTaskWorkflowMode.PROSE) == mode
    }

  override fun listFeatureTaskWorkflows(
    mode: FeatureTaskWorkflowMode,
    limit: Int,
  ): List<WorkflowStateRecord> =
    when (mode) {
      FeatureTaskWorkflowMode.RUNTIME -> taskRuntimeRows
      FeatureTaskWorkflowMode.PROSE -> implementRows
    }.values.toList().asReversed().take(limit)

  override fun latestFeatureTaskWorkflow(mode: FeatureTaskWorkflowMode): WorkflowStateRecord? =
    listFeatureTaskWorkflows(mode, Int.MAX_VALUE).firstOrNull()

  private val taskRuntimeRows = linkedMapOf<String, WorkflowStateRecord>()
  private val implementRows = linkedMapOf<String, WorkflowStateRecord>()

  fun taskRuntimeArtifacts(workflowId: String): Map<String, Any?> {
    val record = requireNotNull(taskRuntimeRows[workflowId]) { "no runtime row for $workflowId" }
    return JsonCodec.parseObjectOrNull(record.artifactsJson)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
      .orEmpty()
  }

  fun corruptRecordsArtifact(
    workflowId: String,
    corruptValue: Any?,
  ) {
    val record = requireNotNull(taskRuntimeRows[workflowId]) { "no runtime row for $workflowId" }
    val artifacts =
      LinkedHashMap(taskRuntimeArtifacts(workflowId)).apply {
        put(FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY, corruptValue)
      }
    taskRuntimeRows[workflowId] =
      record.copy(
        artifactsJson = JsonCodec.mapToJsonString(artifacts),
      )
  }

  fun replaceTaskRuntimeArtifacts(
    workflowId: String,
    artifacts: Map<String, Any?>,
  ) {
    val record = requireNotNull(taskRuntimeRows[workflowId]) { "no runtime row for $workflowId" }
    taskRuntimeRows[workflowId] =
      record.copy(
        artifactsJson = JsonCodec.mapToJsonString(artifacts),
      )
  }

  var failSaveWhen: ((WorkflowStateRecord) -> Boolean)? = null

  fun bumpUpdatedAt(workflowId: String) {
    val row = taskRuntimeRows[workflowId] ?: return
    val current = Instant.parse(row.updatedAt ?: "2026-01-01T00:00:00Z")
    taskRuntimeRows[workflowId] = row.copy(updatedAt = current.plusSeconds(1).toString())
  }

  override fun save(
    family: WorkflowFamily,
    snapshot: WorkflowStateSnapshot,
  ) = saveRecord(family, snapshot.toRecord(record(family, snapshot.workflowId)))

  override fun saveRecord(
    family: WorkflowFamily,
    record: WorkflowStateRecord,
  ) {
    when (family) {
      WorkflowFamily.VERIFY -> verifyRows[record.workflowId] = record
      WorkflowFamily.TASK_RUNTIME -> saveFeatureTaskWorkflow(record, FeatureTaskWorkflowMode.RUNTIME)
    }
  }

  override fun get(
    family: WorkflowFamily,
    workflowId: String,
  ): WorkflowStateSnapshot? = record(family, workflowId)?.toSnapshot()

  override fun getAll(
    family: WorkflowFamily,
    workflowIds: Set<String>,
  ): Map<String, WorkflowStateSnapshot> =
    workflowIds.mapNotNull { id -> record(family, id)?.let { id to it.toSnapshot() } }.toMap()

  override fun list(
    family: WorkflowFamily,
    limit: Int,
  ): List<WorkflowStateSnapshot> =
    when (family) {
      WorkflowFamily.VERIFY -> verifyRows.values.toList().asReversed().take(limit)
      WorkflowFamily.TASK_RUNTIME -> listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME, limit)
    }.map(WorkflowStateRecord::toSnapshot)

  override fun latest(family: WorkflowFamily): WorkflowStateSnapshot? = list(family, 1).firstOrNull()

  private val verifyRows = linkedMapOf<String, WorkflowStateRecord>()

  private fun record(
    family: WorkflowFamily,
    workflowId: String,
  ): WorkflowStateRecord? =
    when (family) {
      WorkflowFamily.VERIFY -> verifyRows[workflowId]
      WorkflowFamily.TASK_RUNTIME -> taskRuntimeRows[workflowId]
    }
}

internal object HarnessDeadProcessSupervisor : FeatureTaskRuntimeWorkerSupervisor {
  override fun currentProcess(): FeatureTaskRuntimeProcessIdentity =
    FeatureTaskRuntimeProcessIdentity("harness-host", "harness-boot", 4321, "harness-birth-4321")

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = FeatureTaskRuntimeProcessInspection.NotRunning

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
