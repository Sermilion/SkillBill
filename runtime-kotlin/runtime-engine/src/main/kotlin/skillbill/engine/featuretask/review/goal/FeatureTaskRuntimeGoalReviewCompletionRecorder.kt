package skillbill.engine.featuretask.review.goal

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.phase.GoalReviewPhaseCompletionRequest
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.WorkflowRowAdvance
import skillbill.engine.featuretask.persist.stepUpdatesFrom
import skillbill.engine.featuretask.persist.workflowArtifactEntryMap
import skillbill.engine.featuretask.persist.workflowArtifactEntryMaps
import skillbill.engine.featuretask.persist.workflowStatusFor
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecordFor
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.goalrunner.subtaskreview.model.UnaddressedFindingLedgerScope
import skillbill.goalrunner.subtaskreview.recordedVerdicts
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.goalreview.GoalSubtaskBlockerDisposition
import skillbill.workflow.model.goalreview.GoalSubtaskReviewRevision
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.goalreview.unionRefutedBlockerDispositions
import skillbill.workflow.model.persistence.artifact.appendBoundedHistoryBySequence
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction.COMPLETE
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.time.Clock

class FeatureTaskRuntimeGoalReviewCompletionRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  private val clock: Clock,
) {
  fun completeGoalReviewPhase(completion: GoalReviewPhaseCompletionRequest): Boolean {
    val request = validatedGoalReviewPhaseState(completion)
    return database.transaction { unitOfWork ->
      persistCompletedGoalReview(unitOfWork, request, completion)
    }
  }

  private fun persistCompletedGoalReview(
    unitOfWork: UnitOfWork,
    request: FeatureTaskRuntimePhaseStateRequest,
    completion: GoalReviewPhaseCompletionRequest,
  ): Boolean {
    val write = goalReviewCompletionWrite(unitOfWork, request, completion) ?: return false
    if (!write.keptStoredPass) {
      persistUnaddressedFindings(
        unitOfWork,
        request,
        write.continuation,
        write.completedState.completedPassCount,
        write.dispositions,
      )
    }
    val rawResults =
      if (write.keptStoredPass) {
        write.persisted.rawResults
      } else {
        write.persisted.rawResults +
          (write.completedState.completedPassCount.toString() to completion.rawReviewResult)
      }
    workflowPersistence.persistArtifactsPatch(
      unitOfWork.workflowStates,
      write.record,
      mapOf(
        DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.entry(write.completedState.toPersistenceWire()),
        DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_RESULTS.entry(rawResults),
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
          write.persisted.updatedRecords.mapValues { (_, value) -> value.asWorkflowArtifactEntry() },
        ),
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.entry(
          goalReviewCompletionLedger(request, write.persisted.artifacts),
        ),
      ),
      WorkflowRowAdvance(
        currentStepId = request.phaseId,
        workflowStatus = workflowStatusFor(request),
        stepUpdates = stepUpdatesFrom(write.persisted.updatedRecords),
      ),
    )
    return true
  }

  private data class GoalReviewCompletionArtifacts(
    val artifacts: Map<String, Any?>,
    val rawResults: Map<String, String>,
    val updatedRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  )

  private data class GoalReviewCompletionWrite(
    val record: WorkflowStateSnapshot,
    val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
    val completedState: GoalSubtaskReviewState,
    val keptStoredPass: Boolean,
    val dispositions: List<GoalSubtaskBlockerDisposition>,
    val persisted: GoalReviewCompletionArtifacts,
  )

  private fun goalReviewCompletionWrite(
    unitOfWork: UnitOfWork,
    request: FeatureTaskRuntimePhaseStateRequest,
    completion: GoalReviewPhaseCompletionRequest,
  ): GoalReviewCompletionWrite? {
    val record =
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, request.workflowId)
        ?: return null
    val artifacts = record.artifacts
    val reviewArtifacts = GoalSubtaskReviewArtifactDecoder.decode(artifacts) ?: return null
    val reservedPass = reviewArtifacts.state.reservedPassNumber ?: 1
    val envelope =
      requireNotNull(request.normalizedOutput) {
        "Goal review completion requires normalized output to persist the unaddressed-findings ledger."
      }.envelopeWireMap()
    val recordedVerdicts =
      GoalSubtaskReviewSummaryReducer.recordedVerdicts(
        unitOfWork.reviews::fetchFindingVerdicts,
        envelope,
      )
    val currentFindings =
      GoalSubtaskReviewSummaryReducer.unaddressedFindings(
        output = envelope,
        scope =
          UnaddressedFindingLedgerScope(
            issueKey = reviewArtifacts.continuation.issueKey,
            subtaskId = reviewArtifacts.continuation.subtaskId,
            workflowId = request.workflowId,
            reviewPassNumber = reservedPass,
          ),
        recordedVerdicts = recordedVerdicts,
      )
    val dispositions =
      goalReviewCompletionDispositions(
        reservedPass,
        completion.blockerDispositions,
        unitOfWork.unaddressedFindings.fetchWorkflowLedger(request.workflowId),
        currentFindings,
        recordedVerdicts,
      )
    val existingRecords = decodePhaseRecords(artifacts)
    val priorState = reviewArtifacts.state
    val completedState =
      priorState.completeReservedPass(
        verdict = completion.verdict,
        unresolvedFindingCount = completion.unresolvedFindingCount,
        findings = completion.findings,
        blockerDispositions = dispositions,
        revision =
          GoalSubtaskReviewRevision(
            commitFocusedAccounting = completion.commitFocusedAccounting,
          ),
      )
    return GoalReviewCompletionWrite(
      record = record,
      continuation = reviewArtifacts.continuation,
      completedState = completedState,
      keptStoredPass = completedState == priorState,
      dispositions = dispositions,
      persisted =
        GoalReviewCompletionArtifacts(
          artifacts = artifacts,
          rawResults = reviewArtifacts.rawResults,
          updatedRecords =
            LinkedHashMap(existingRecords).apply {
              put(
                request.phaseId,
                featureTaskRuntimePhaseRecordFor(request, existingRecords[request.phaseId], clock.instant()),
              )
            },
        ),
    )
  }

  private fun goalReviewCompletionDispositions(
    reservedPass: Int,
    requested: List<GoalSubtaskBlockerDisposition>,
    priorFindings: List<UnaddressedFinding>,
    currentFindings: List<UnaddressedFinding>,
    recordedVerdicts: List<ReviewFindingVerdict>,
  ): List<GoalSubtaskBlockerDisposition> {
    if (reservedPass <= 1) return requested
    return unionRefutedBlockerDispositions(
      requested,
      GoalSubtaskReviewSummaryReducer.refutedBlockerSupersedes(priorFindings, currentFindings, recordedVerdicts),
    )
  }

  private fun goalReviewCompletionLedger(
    request: FeatureTaskRuntimePhaseStateRequest,
    artifacts: Map<String, Any?>,
  ): List<Map<String, Any?>> {
    val ledger = decodePhaseLedger(artifacts)
    val completionEntry =
      FeatureTaskRuntimePhaseLedgerEntry(
        action = COMPLETE,
        sequenceNumber = (ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
        timestamp = clock.instant().toString(),
        phaseId = request.phaseId,
        attemptCount = request.attemptCount,
        resolvedAgentId = request.resolvedAgentId,
        loopId = request.loopId,
        edgeIteration = request.edgeIteration,
      )
    return appendBoundedHistoryBySequence(
      workflowArtifactEntryMaps(ledger.map { it.asWorkflowArtifactEntry() }),
      workflowArtifactEntryMap(completionEntry.asWorkflowArtifactEntry()),
      FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
    ).mapNotNull { entry -> JsonCodec.anyToStringAnyMap(entry) }
  }

  private fun persistUnaddressedFindings(
    unitOfWork: UnitOfWork,
    request: FeatureTaskRuntimePhaseStateRequest,
    continuation: FeatureTaskRuntimeGoalContinuationArtifact,
    passNumber: Int,
    blockerDispositions: List<GoalSubtaskBlockerDisposition>,
  ) {
    val output =
      requireNotNull(request.normalizedOutput) {
        "Goal review completion requires normalized output to persist the unaddressed-findings ledger."
      }.envelopeWireMap()
    val recordedVerdicts =
      GoalSubtaskReviewSummaryReducer.recordedVerdicts(
        unitOfWork.reviews::fetchFindingVerdicts,
        output,
      )
    val findings =
      GoalSubtaskReviewSummaryReducer.unaddressedFindings(
        output = output,
        scope =
          UnaddressedFindingLedgerScope(
            issueKey = continuation.issueKey,
            subtaskId = continuation.subtaskId,
            workflowId = request.workflowId,
            reviewPassNumber = passNumber,
          ),
        recordedVerdicts = recordedVerdicts,
      )
    val superseded = unitOfWork.unaddressedFindings.fetchWorkflowLedger(request.workflowId)
    unitOfWork.unaddressedFindings.replaceLedgerForPass(request.workflowId, passNumber, findings)
    unitOfWork.unaddressedFindings.recordOutcomes(
      GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
        supersededFindings = superseded,
        currentFindings = findings,
        blockerDispositions = blockerDispositions,
      ),
    )
  }

  private fun validatedGoalReviewPhaseState(
    completion: GoalReviewPhaseCompletionRequest,
  ): FeatureTaskRuntimePhaseStateRequest {
    val request = completion.phaseState
    require(request.reviewPassNumber != null) {
      "Goal review completion can only persist the review phase."
    }
    require(request.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED && request.finished) {
      "Goal review completion must persist a finished completed review phase."
    }
    require(completion.rawReviewResult.isNotBlank()) { "Goal-subtask review pass result must be non-blank." }
    return request
  }
}
