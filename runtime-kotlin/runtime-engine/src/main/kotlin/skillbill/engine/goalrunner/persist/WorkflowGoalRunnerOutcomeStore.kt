package skillbill.engine.goalrunner.persist

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.execution.support.authoritativeOutcomesBySubtask
import skillbill.engine.goalrunner.execution.support.workflowFamilyFor
import skillbill.engine.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerReconcileGate
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.model.GoalSubtaskIdentity
import skillbill.goalrunner.goalSubtaskReviewArtifacts
import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary
import skillbill.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerWirePayload
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.validatedGoalReviewPasses
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalreview.GoalSubtaskReviewPassResult
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import java.nio.file.Path
import java.time.Clock

internal data class RecoverMissingResultPrefixTerminalOutcomeArgs(
  internal val workflowStates: WorkflowStateRepository,
  internal val family: WorkflowFamily,
  internal val record: WorkflowStateSnapshot,
  internal val output: GoalRunnerWirePayload,
  internal val issueKey: String,
  internal val subtaskId: Int,
  internal val workflowId: String,
)

class WorkflowGoalRunnerOutcomeStore
  @Inject
  constructor(
    private val database: DatabaseSessionFactory,
    workflowSnapshotValidator: WorkflowSnapshotValidator,
    wireArtifactValidator: FeatureTaskRuntimeWireArtifactValidator,
    gitOperations: WorkflowGitOperations,
    workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
    clock: Clock,
  ) : GoalRunnerWorkflowOutcomeStore,
    GoalRunnerAttemptLedgerStore {
    private val engine = WorkflowEngine()
    private val blockWrites = WorkflowGoalRunnerBlockWrites(engine, clock)
    private val terminalPersistence =
      WorkflowGoalRunnerOutcomeTerminalPersistence(
        engine,
        gitOperations,
        workerSupervisor,
        clock,
      )
    private val outcomeReconcile =
      WorkflowGoalRunnerOutcomeReconcile(
        engine,
        gitOperations,
        blockWrites,
        terminalPersistence,
        clock,
      )
    private val progressRecording =
      WorkflowGoalRunnerProgressRecording(
        database,
        engine,
        workflowSnapshotValidator,
        wireArtifactValidator,
      )
    private val terminal = WorkflowGoalRunnerTerminalBridge(database, terminalPersistence, gitOperations)
    private val review = WorkflowGoalRunnerReviewBridge(database, engine)
    private val reconcile = WorkflowGoalRunnerReconcileBridge(database, outcomeReconcile)
    private val blocks = WorkflowGoalRunnerBlockBridge(database, blockWrites)

    override fun terminalOutcome(
      workflowId: String,
      issueKey: String,
      subtaskId: Int,
    ): GoalRunnerStoredOutcome? = terminal.terminalOutcome(workflowId, issueKey, subtaskId)

    override fun recoverAndPersistTerminalOutcome(
      workflowId: String,
      issueKey: String,
      subtaskId: Int,
      repoRoot: Path,
    ): GoalRunnerStoredOutcome? = terminal.recoverAndPersistTerminalOutcome(workflowId, issueKey, subtaskId, repoRoot)

    override fun recoverMissingResultPrefixOutput(
      workflowId: String,
      issueKey: String,
      subtaskId: Int,
      output: GoalRunnerWirePayload,
    ): GoalRunnerStoredOutcome? = terminal.recoverMissingResultPrefixOutput(workflowId, issueKey, subtaskId, output)

    override fun goalSubtaskReviewState(workflowId: String): GoalSubtaskReviewState? =
      review.goalSubtaskReviewState(workflowId)

    override fun unemittedGoalReviewPasses(workflowId: String): List<GoalSubtaskReviewPassResult> =
      review.unemittedGoalReviewPasses(workflowId)

    override fun acknowledgeGoalReviewPass(
      workflowId: String,
      passNumber: Int,
    ): Boolean = review.acknowledgeGoalReviewPass(workflowId, passNumber)

    override fun progress(workflowId: String): GoalRunnerWorkflowProgress? = progressRecording.progress(workflowId)

    override fun recordObservabilityEvent(request: GoalRunnerObservabilityRecordRequest): Boolean =
      progressRecording.recordObservabilityEvent(request)

    override fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest): Boolean =
      progressRecording.recordProgressEvent(request)

    override fun progressEvents(workflowId: String): List<GoalProgressEvent> =
      progressRecording.progressEvents(workflowId)

    override fun recordAttemptLedgerEntry(request: GoalRunnerAttemptLedgerRecordRequest): Boolean =
      progressRecording.recordAttemptLedgerEntry(request)

    override fun recordWorkerSubtaskRequestOutcomes(
      workflowId: String,
      outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
    ): Boolean = progressRecording.recordWorkerSubtaskRequestOutcomes(workflowId, outcomes)

    override fun ledgerSequenceWatermarks(issueKey: String): GoalRunnerLedgerSequenceWatermarks =
      progressRecording.ledgerSequenceWatermarks(issueKey)

    override fun childWorkflowLoopIterations(workflowId: String): Map<String, Int> =
      progressRecording.childWorkflowLoopIterations(workflowId)

    override fun authoritativeOutcomes(issueKey: String): Map<Int, GoalRunnerStoredOutcome> =
      reconcile.authoritativeOutcomes(issueKey)

    override fun reconcileAuthoritativeOutcomes(
      issueKey: String,
      activeWorkflowIds: Set<String>,
      gate: GoalRunnerReconcileGate,
      repoRoot: Path?,
    ): Map<Int, GoalRunnerStoredOutcome> =
      reconcile.reconcileAuthoritativeOutcomes(issueKey, activeWorkflowIds, gate, repoRoot)

    override fun markBlocked(
      workflowId: String,
      blockedReason: String,
      lastResumableStep: String,
      supervisionEvent: GoalRunnerSupervisionEvent?,
    ): String? = blocks.markBlocked(workflowId, blockedReason, lastResumableStep, supervisionEvent)

    override fun reopenBlockedPhaseForOperatorResume(
      workflowId: String,
      preferredPhaseId: String,
      reason: String,
      expectedIdentity: FeatureTaskExecutionIdentity,
      expectedExecutionPlan: ValidatedFeatureTaskRuntimeExecutionPlan,
    ): Boolean =
      blocks.reopenBlockedPhaseForOperatorResume(
        workflowId,
        preferredPhaseId,
        reason,
        expectedIdentity,
        expectedExecutionPlan,
      )

    override fun readAttemptLedgerSummary(issueKey: String): GoalRunnerAttemptLedgerSummary =
      progressRecording.readAttemptLedgerSummary(issueKey)
  }

internal class WorkflowGoalRunnerTerminalBridge(
  private val database: DatabaseSessionFactory,
  private val terminalPersistence: WorkflowGoalRunnerOutcomeTerminalPersistence,
  private val gitOperations: WorkflowGitOperations,
) : GoalRunnerTerminalOutcomeStore {
  override fun terminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ): GoalRunnerStoredOutcome? =
    database.read { unitOfWork ->
      terminalPersistence.resolveTerminalOutcome(unitOfWork.workflowStates, workflowId, issueKey, subtaskId) { null }
    }

  override fun recoverAndPersistTerminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
  ): GoalRunnerStoredOutcome? =
    database.transaction { unitOfWork ->
      terminalPersistence.displaceStaleBlockedContinuationOutcomeIfPresent(
        unitOfWork.workflowStates,
        workflowId,
        issueKey,
        subtaskId,
      )
      val resolved =
        terminalPersistence.resolveTerminalOutcome(
          unitOfWork.workflowStates,
          workflowId,
          issueKey,
          subtaskId,
        ) {
          gitOperations.headCommitSha(repoRoot).measuredCommitSha()
        } ?: return@transaction terminalPersistence.crashReconcileToResumable(
          unitOfWork.workflowStates,
          workflowId,
          issueKey,
          subtaskId,
        )
      val recovered =
        terminalPersistence.recoverResolvedCommitPushBlock(
          workflowStates = unitOfWork.workflowStates,
          identity = GoalSubtaskIdentity(workflowId, issueKey, subtaskId),
          repoRoot = repoRoot,
          outcome = resolved,
        ) ?: resolved
      recovered.also { outcome ->
        terminalPersistence.persistMeasuredCompletion(
          unitOfWork.workflowStates,
          workflowId,
          issueKey,
          subtaskId,
          outcome,
        )
      }
    }

  override fun recoverMissingResultPrefixOutput(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    output: GoalRunnerWirePayload,
  ): GoalRunnerStoredOutcome? =
    database.transaction { unitOfWork ->
      val family = workflowFamilyFor(unitOfWork.workflowStates, workflowId) ?: return@transaction null
      val record = unitOfWork.workflowStates.get(family, workflowId) ?: return@transaction null
      terminalPersistence.recoverMissingResultPrefixTerminalOutcome(
        RecoverMissingResultPrefixTerminalOutcomeArgs(
          workflowStates = unitOfWork.workflowStates,
          family = family,
          record = record,
          output = output,
          issueKey = issueKey,
          subtaskId = subtaskId,
          workflowId = workflowId,
        ),
      )
    }
}

internal class WorkflowGoalRunnerReviewBridge(
  private val database: DatabaseSessionFactory,
  private val engine: WorkflowEngine,
) : GoalRunnerReviewOutcomeStore {
  override fun goalSubtaskReviewState(workflowId: String): GoalSubtaskReviewState? =
    database.read { unitOfWork ->
      val record = taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId) ?: return@read null
      record.artifacts.goalSubtaskReviewArtifacts()?.state
    }

  override fun unemittedGoalReviewPasses(workflowId: String): List<GoalSubtaskReviewPassResult> =
    database.read { unitOfWork ->
      val record = taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId) ?: return@read emptyList()
      val artifacts = record.artifacts
      if (!DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.contains(artifacts)) return@read emptyList()
      val review = artifacts.goalSubtaskReviewArtifacts() ?: return@read emptyList()
      validatedGoalReviewPasses(
        review,
        ::goalReviewEmissionEnvelope,
        unitOfWork.reviews::fetchFindingVerdicts,
      )
        .drop(review.state.emittedPassCount)
    }

  override fun acknowledgeGoalReviewPass(
    workflowId: String,
    passNumber: Int,
  ): Boolean =
    database.transaction { unitOfWork ->
      val record = taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId) ?: return@transaction false
      val artifacts = record.artifacts
      val review = artifacts.goalSubtaskReviewArtifacts() ?: return@transaction false
      val state = review.state
      validatedGoalReviewPasses(
        review,
        ::goalReviewEmissionEnvelope,
        unitOfWork.reviews::fetchFindingVerdicts,
      )
      if (passNumber != state.emittedPassCount + 1 || passNumber > state.completedPassCount) {
        return@transaction false
      }
      val updated =
        engine.updateRecord(
          WorkflowFamily.TASK_RUNTIME.definition,
          record,
          WorkflowUpdateInput(
            workflowStatus = record.workflowStatus,
            currentStepId = record.currentStepId,
            stepUpdates = null,
            artifactsPatch =
              WorkflowArtifactPatch.from(
                mapOf(
                  DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.entry(
                    state.acknowledgeSummariesThrough(passNumber).toPersistenceWire(),
                  ),
                ),
              ),
            sessionId = record.sessionId.orEmpty(),
          ),
        )
      unitOfWork.workflowStates.save(WorkflowFamily.TASK_RUNTIME, updated)
      true
    }
}

internal class WorkflowGoalRunnerReconcileBridge(
  private val database: DatabaseSessionFactory,
  private val outcomeReconcile: WorkflowGoalRunnerOutcomeReconcile,
) {
  fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String>,
    gate: GoalRunnerReconcileGate,
    repoRoot: Path?,
  ): Map<Int, GoalRunnerStoredOutcome> =
    database.transaction { unitOfWork ->
      outcomeReconcile.reconcileAuthoritativeOutcomesInTransaction(
        unitOfWork,
        issueKey,
        activeWorkflowIds,
        gate,
        repoRoot,
      )
    }

  fun authoritativeOutcomes(issueKey: String): Map<Int, GoalRunnerStoredOutcome> =
    database.read { unitOfWork ->
      outcomeReconcile.loadContinuationCandidates(unitOfWork.workflowStates, issueKey.trim(), repoRoot = null)
        .authoritativeOutcomesBySubtask()
    }
}

internal class WorkflowGoalRunnerBlockBridge(
  private val database: DatabaseSessionFactory,
  private val blockWrites: WorkflowGoalRunnerBlockWrites,
) {
  fun markBlocked(
    workflowId: String,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent?,
  ): String? =
    database.transaction { unitOfWork ->
      blockWrites.markBlocked(
        workflowId,
        blockedReason,
        lastResumableStep,
        supervisionEvent,
        unitOfWork.workflowStates,
      )
    }

  fun reopenBlockedPhaseForOperatorResume(
    workflowId: String,
    preferredPhaseId: String,
    reason: String,
    expectedIdentity: FeatureTaskExecutionIdentity,
    expectedExecutionPlan: ValidatedFeatureTaskRuntimeExecutionPlan,
  ): Boolean {
    require(workflowId == expectedIdentity.workflowId) { "Operator resume identity belongs to another workflow." }
    return database.transaction { unitOfWork ->
      blockWrites.reopenBlockedPhaseForOperatorResume(
        unitOfWork,
        preferredPhaseId,
        reason,
        expectedIdentity,
        expectedExecutionPlan,
      )
    }
  }
}
