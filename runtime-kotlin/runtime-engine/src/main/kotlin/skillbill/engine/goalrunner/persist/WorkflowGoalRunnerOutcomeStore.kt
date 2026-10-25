package skillbill.engine.goalrunner.persist

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys
import skillbill.engine.diagnostics.block.BlockIncidentCapture
import skillbill.engine.diagnostics.block.BlockIncidentCaptureOutcome
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.goalrunner.model.GoalRunnerAcceptedBlock
import skillbill.engine.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerBlockRequest
import skillbill.engine.goalrunner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerReconcileGate
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.model.GoalSubtaskIdentity
import skillbill.engine.goalrunner.model.GoalSubtaskQaSummary
import skillbill.engine.goalrunner.model.GoalSubtaskUnknownScenarios
import skillbill.engine.goalrunner.model.GoalUnknownScenario
import skillbill.engine.goalrunner.persist.assessment.GoalAssessmentRecordRead
import skillbill.engine.goalrunner.persist.assessment.WorkflowGoalRunnerAssessmentRecording
import skillbill.engine.goalrunner.persist.assessment.toTraceProbeCounts
import skillbill.engine.workflow.support.workflowFamilyFor
import skillbill.error.core.SkillBillRuntimeException
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.goalSubtaskReviewArtifacts
import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary
import skillbill.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.GoalRunnerWirePayload
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.validatedGoalReviewPasses
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.telemetry.model.GoalSubtaskTraceProbeCounts
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalreview.GoalSubtaskReviewPassResult
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionInteractionTrace
import skillbill.workflow.taskruntime.model.audit.interaction.unknownScenarios
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.goalContinuation
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
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
    crashReconciler: FeatureTaskRuntimeCrashReconciler,
    clock: Clock,
    private val blockIncidentCapture: BlockIncidentCapture,
  ) : GoalRunnerWorkflowOutcomeStore,
    GoalRunnerAttemptLedgerStore {
    private val engine = WorkflowEngine()
    private val blockWrites = WorkflowGoalRunnerBlockWrites(clock)
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
    private val assessmentRecording = WorkflowGoalRunnerAssessmentRecording(database, engine, wireArtifactValidator)
    private val terminal =
      WorkflowGoalRunnerTerminalBridge(database, terminalPersistence, gitOperations, crashReconciler)
    private val review = WorkflowGoalRunnerReviewBridge(database, engine)
    private val reconcile = WorkflowGoalRunnerReconcileBridge(database, outcomeReconcile, blockIncidentCapture)
    private val blocks = WorkflowGoalRunnerBlockBridge(database, blockWrites, blockIncidentCapture, clock)

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

    override fun recordedQualityGateSelection(workflowId: String): FeatureTaskRuntimeQualityGateSelection? =
      review.recordedQualityGateSelection(workflowId)

    override fun recordedQaOutcome(workflowId: String): GoalSubtaskQaSummary = review.recordedQaOutcome(workflowId)

    override fun hasQaComposedPlan(workflowId: String): Boolean =
      database.read { unitOfWork ->
        storedExecutionPlanComposesQa(taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId)?.artifacts)
      }

    override fun recordedGoalQaOutcome(parentWorkflowId: String): FeatureTaskQaOutcome? =
      database.read { unitOfWork -> unitOfWork.featureTaskQaOutcomes.find(parentWorkflowId) }

    override fun readGoalAssessmentRecord(parentWorkflowId: String): GoalAssessmentRecordRead =
      assessmentRecording.read(parentWorkflowId)

    override fun recordGoalAssessmentRecord(
      parentWorkflowId: String,
      record: GoalAssessmentRecord,
    ): Boolean = assessmentRecording.record(parentWorkflowId, record)

    override fun childQaSelection(
      workflowId: String,
      requested: FeatureTaskRuntimeQaSelection?,
    ): FeatureTaskRuntimeQaSelection? = review.childQaSelection(workflowId, requested)

    override fun recordedUnknownScenarios(workflowId: String): GoalSubtaskUnknownScenarios =
      review.recordedUnknownScenarios(workflowId)

    override fun recordedTraceProbeCounts(workflowId: String): GoalSubtaskTraceProbeCounts =
      review.recordedTraceProbeCounts(workflowId)

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

    override fun authoritativeOutcomes(
      issueKey: String,
      parentWorkflowId: String,
    ): Map<Int, GoalRunnerStoredOutcome> = reconcile.authoritativeOutcomes(issueKey, parentWorkflowId)

    override fun authoritativeOutcomesForSubtasks(
      issueKey: String,
      parentWorkflowId: String,
      subtaskIds: Set<Int>,
    ): Map<Int, GoalRunnerStoredOutcome> =
      reconcile.authoritativeOutcomesForSubtasks(
        issueKey,
        parentWorkflowId,
        subtaskIds,
      )

    override fun reconcileAuthoritativeOutcomes(
      issueKey: String,
      parentWorkflowId: String,
      activeWorkflowIds: Set<String>,
      gate: GoalRunnerReconcileGate,
      repoRoot: Path?,
    ): Map<Int, GoalRunnerStoredOutcome> =
      reconcile.reconcileAuthoritativeOutcomes(issueKey, parentWorkflowId, activeWorkflowIds, gate, repoRoot)

    override fun markBlocked(request: GoalRunnerBlockRequest): GoalRunnerAcceptedBlock? = blocks.markBlocked(request)

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

    override fun readAttemptLedgerSummaryForSubtasks(
      issueKey: String,
      subtaskIds: Set<Int>,
    ): GoalRunnerAttemptLedgerSummary = progressRecording.readAttemptLedgerSummaryForSubtasks(issueKey, subtaskIds)
  }

internal class WorkflowGoalRunnerTerminalBridge(
  private val database: DatabaseSessionFactory,
  private val terminalPersistence: WorkflowGoalRunnerOutcomeTerminalPersistence,
  private val gitOperations: WorkflowGitOperations,
  private val crashReconciler: FeatureTaskRuntimeCrashReconciler,
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
  ): GoalRunnerStoredOutcome? {
    val resolvedInTransaction =
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
          } ?: return@transaction null
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
    if (resolvedInTransaction != null) return resolvedInTransaction
    val expiredLease =
      database.transaction { unitOfWork ->
        terminalPersistence.crashReconcileToResumable(
          unitOfWork.workflowStates,
          workflowId,
          issueKey,
          subtaskId,
        )
      }
    return expiredLease ?: persistReconciledTerminalOutcome(workflowId, issueKey, subtaskId)
  }

  private fun persistReconciledTerminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ): GoalRunnerStoredOutcome? {
    val reconciled = crashReconciler.reconcile(workflowId)
    if (reconciled.reconciledCount <= 0) return null
    val reconcilableOutcome =
      database.read { unitOfWork ->
        val row =
          unitOfWork.workflowStates.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
            ?: return@read null
        val continuation =
          DurableWorkflowArtifacts.fromMap(row.toSnapshot().artifacts).goalContinuation()
            ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
            ?: return@read null
        if (row.workflowStatus.workflowStatus() != WorkflowStatus.RUNNING) return@read null
        GoalRunnerStoredOutcome(
          status = GoalRunnerTerminalStatus.RECONCILABLE,
          workflowId = workflowId,
          commitSha = null,
          blockedReason = null,
          lastResumableStep = row.currentStepId.ifBlank { "preplan" },
          suppressPr = continuation.suppressPr,
        )
      } ?: return null
    database.transaction { unitOfWork ->
      terminalPersistence.persistMeasuredCompletion(
        unitOfWork.workflowStates,
        workflowId,
        issueKey,
        subtaskId,
        reconcilableOutcome,
      )
    }
    return reconcilableOutcome
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

  override fun recordedQualityGateSelection(workflowId: String): FeatureTaskRuntimeQualityGateSelection? =
    database.read { unitOfWork ->
      val artifacts = taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId)?.artifacts ?: return@read null
      artifacts.goalContinuationArtifact()?.qualityGateSelection ?: storedExecutionPlanQualityGate(artifacts)
    }

  override fun childQaSelection(
    workflowId: String,
    requested: FeatureTaskRuntimeQaSelection?,
  ): FeatureTaskRuntimeQaSelection? =
    database.read { unitOfWork ->
      val artifacts = taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId)?.artifacts
      val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN
      if (artifacts == null || !family.contains(artifacts)) return@read requested
      val plan = JsonCodec.anyToStringAnyMap(family.value(artifacts))
      val token = plan?.get(FeatureTaskRuntimeExecutionPlanKeys.QA_SELECTION) as? String
      token?.let(FeatureTaskRuntimeQaSelection::fromWire)
    }

  override fun recordedQaOutcome(workflowId: String): GoalSubtaskQaSummary =
    database.read { unitOfWork ->
      unitOfWork.featureTaskQaOutcomes.find(workflowId)?.let(GoalSubtaskQaSummary::Recorded)
        ?: if (storedExecutionPlanComposesQa(
            taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId)?.artifacts,
          )
        ) {
          GoalSubtaskQaSummary.NotRecorded
        } else {
          GoalSubtaskQaSummary.NotComposed
        }
    }

  override fun recordedUnknownScenarios(workflowId: String): GoalSubtaskUnknownScenarios =
    database.read { unitOfWork ->
      val artifacts = taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId)?.artifacts
      val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR
      if (artifacts == null || !family.contains(artifacts)) return@read GoalSubtaskUnknownScenarios.Absent
      val state =
        try {
          FeatureTaskRuntimeWorkflowArtifactMap.from(family.value(artifacts))
            .toAcceptanceCriterionRepairState(family.label())
        } catch (failure: SkillBillRuntimeException) {
          return@read GoalSubtaskUnknownScenarios.Malformed(failure.message.orEmpty())
        }
      state.unknownScenarios()
        .map { GoalUnknownScenario(it.scenarioRef, it.reason) }
        .takeIf { it.isNotEmpty() }
        ?.let(GoalSubtaskUnknownScenarios::Recorded)
        ?: GoalSubtaskUnknownScenarios.Empty
    }

  override fun recordedTraceProbeCounts(workflowId: String): GoalSubtaskTraceProbeCounts =
    database.read { unitOfWork ->
      val artifacts =
        taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId)?.artifacts
          ?: return@read GoalSubtaskTraceProbeCounts()
      val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR
      val trace =
        if (family.contains(artifacts)) {
          FeatureTaskRuntimeWorkflowArtifactMap.from(family.value(artifacts))
            .toAcceptanceCriterionRepairState(family.label())
            .interactionTrace
        } else {
          AcceptanceCriterionInteractionTrace.EMPTY
        }
      trace.toTraceProbeCounts(
        decodePhaseRecords(artifacts)[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE]?.outputArtifact,
      )
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
      engine.saveRunnerArtifactPatch(
        unitOfWork.workflowStates,
        WorkflowFamily.TASK_RUNTIME,
        record,
        mapOf(
          DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.entry(
            state.acknowledgeSummariesThrough(passNumber).toPersistenceWire(),
          ),
        ),
      )
      true
    }
}

internal class WorkflowGoalRunnerReconcileBridge(
  private val database: DatabaseSessionFactory,
  private val outcomeReconcile: WorkflowGoalRunnerOutcomeReconcile,
  private val blockIncidentCapture: BlockIncidentCapture,
) {
  fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    parentWorkflowId: String,
    activeWorkflowIds: Set<String>,
    gate: GoalRunnerReconcileGate,
    repoRoot: Path?,
  ): Map<Int, GoalRunnerStoredOutcome> {
    val result =
      database.transaction { unitOfWork ->
        outcomeReconcile.reconcileAuthoritativeOutcomesInTransaction(
          unitOfWork,
          GoalOutcomeReconciliationRequest(issueKey, parentWorkflowId, activeWorkflowIds, gate, repoRoot),
        )
      }
    result.blockObservations.forEach(blockIncidentCapture::capture)
    return result.outcomes
  }

  fun authoritativeOutcomesForSubtasks(
    issueKey: String,
    parentWorkflowId: String,
    subtaskIds: Set<Int>,
  ): Map<Int, GoalRunnerStoredOutcome> =
    database.read {
        unitOfWork ->
      outcomeReconcile.authoritativeOutcomesForSubtasks(
        unitOfWork.workflowStates,
        issueKey,
        parentWorkflowId,
        subtaskIds,
      )
    }

  fun authoritativeOutcomes(
    issueKey: String,
    parentWorkflowId: String,
  ): Map<Int, GoalRunnerStoredOutcome> =
    database.read { unitOfWork ->
      outcomeReconcile.authoritativeOutcomesForSubtasks(unitOfWork.workflowStates, issueKey, parentWorkflowId, null)
    }
}

internal class WorkflowGoalRunnerBlockBridge(
  private val database: DatabaseSessionFactory,
  private val blockWrites: WorkflowGoalRunnerBlockWrites,
  private val blockIncidentCapture: BlockIncidentCapture,
  private val clock: Clock,
) {
  fun markBlocked(request: GoalRunnerBlockRequest): GoalRunnerAcceptedBlock? {
    val result =
      database.transaction { unitOfWork ->
        if (!unitOfWork.holdsExecutionLease(request)) return@transaction null
        blockWrites.markBlocked(request, unitOfWork.workflowStates, unitOfWork.blockIncidents)
      }
    val capture = result?.let { blockIncidentCapture.capture(it.observation) }
    val incidentToken =
      when (capture) {
        is BlockIncidentCaptureOutcome.Captured -> capture.occurrenceToken
        is BlockIncidentCaptureOutcome.Spooled -> capture.occurrenceToken
        BlockIncidentCaptureOutcome.Unsaved, null -> null
      }
    return result?.let { GoalRunnerAcceptedBlock(it.stepId, incidentToken, it.acceptedBlockReference) }
  }

  private fun UnitOfWork.holdsExecutionLease(request: GoalRunnerBlockRequest): Boolean {
    val ownerToken = request.executionOwnerToken ?: return true
    val lease = goalRunnerControls.controlState(request.workflowId).executionLease ?: return false
    val owned = lease.ownerToken == ownerToken && lease.generation == request.executionGeneration
    return owned && lease.expiresAtInstant.isAfter(clock.instant())
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
        OperatorBlockedPhaseReopenRequest(preferredPhaseId, reason),
        expectedIdentity,
        expectedExecutionPlan,
      )
    }
  }
}

private fun storedExecutionPlanComposesQa(artifacts: Map<String, Any?>?): Boolean {
  val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN
  if (artifacts == null || !family.contains(artifacts)) return false
  return JsonCodec.anyToStringAnyMap(
    family.value(artifacts),
  )?.get(FeatureTaskRuntimeExecutionPlanKeys.QA_SELECTION) != null
}

private fun storedExecutionPlanQualityGate(artifacts: Map<String, Any?>): FeatureTaskRuntimeQualityGateSelection? {
  val plan =
    JsonCodec.anyToStringAnyMap(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(artifacts))
  val token = plan?.get(FeatureTaskRuntimeExecutionPlanKeys.QUALITY_GATE_SELECTION) as? String
  return token?.let(FeatureTaskRuntimeQualityGateSelection::fromWire)
}
