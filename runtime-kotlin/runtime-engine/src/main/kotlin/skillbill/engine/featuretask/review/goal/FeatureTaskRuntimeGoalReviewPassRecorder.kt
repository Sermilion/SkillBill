package skillbill.engine.featuretask.review.goal

import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskRuntimeGoalContinuationArtifactPatcher
import skillbill.engine.featuretask.lifecycle.continuation.GoalReviewPassCompletionRequest
import skillbill.engine.featuretask.lifecycle.continuation.continuationFromArtifacts
import skillbill.engine.featuretask.lifecycle.continuation.rawReviewResultsFromArtifacts
import skillbill.engine.featuretask.lifecycle.continuation.reviewStateFromArtifacts
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassCarryForward
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassInFlight
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassReservation
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassReserved
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.goalrunner.subtaskreview.model.UnaddressedFindingLedgerScope
import skillbill.goalrunner.subtaskreview.recordedVerdicts
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.goalreview.GoalSubtaskBlockerDisposition
import skillbill.workflow.model.goalreview.GoalSubtaskReviewRevision
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState

class FeatureTaskRuntimeGoalReviewPassRecorder(
  private val database: DatabaseSessionFactory,
  private val patcher: FeatureTaskRuntimeGoalContinuationArtifactPatcher,
  private val runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
) {
  fun reserveGoalReviewPass(workflowId: String): GoalSubtaskReviewPassReservation =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@transaction GoalSubtaskReviewPassReservation.MissingState
      val artifacts = record.artifacts
      val state =
        reviewStateFromArtifacts(artifacts)
          ?: return@transaction GoalSubtaskReviewPassReservation.MissingState
      if (state.reviewCapReached || state.reviewSkippedByUser) {
        return@transaction GoalSubtaskReviewPassCarryForward(state)
      }
      if (state.reservedPassNumber != null) {
        return@transaction GoalSubtaskReviewPassInFlight(state)
      }
      val reserved = state.reserveNextPass()
      if (reserved == state) {
        return@transaction GoalSubtaskReviewPassCarryForward(state)
      }
      patcher.save(
        record,
        unitOfWork.workflowStates,
        mapOf(DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.entry(reserved.toPersistenceWire())),
      )
      GoalSubtaskReviewPassReserved(reserved)
    }

  fun persistGoalReviewInput(
    workflowId: String,
    input: GoalSubtaskReviewInput,
  ): GoalSubtaskReviewState? =
    database.transaction { unitOfWork ->
      val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@transaction null
      val artifacts = record.artifacts
      val state =
        reviewStateFromArtifacts(artifacts)
          ?: return@transaction null
      check(input.reviewBaseSha == state.reviewBaseSha || input.reviewBaseSha == state.remediationBaseSha) {
        "Goal-subtask review input does not match the durable review baseline or its recorded remediation base."
      }
      val updated =
        state.copy(
          reviewInputArtifact = DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_INPUT.label(),
          reviewedDeltaDigest =
            if (input.reviewBaseSha == state.reviewBaseSha) {
              input.deltaDigest
            } else {
              state.reviewedDeltaDigest
            },
        )
      patcher.save(
        record,
        unitOfWork.workflowStates,
        mapOf(
          DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.entry(updated.toPersistenceWire()),
          DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_INPUT.entry(goalReviewInputArtifactMap(input)),
        ),
      )
      updated
    }

  fun updateReviewState(
    workflowId: String,
    transform: (GoalSubtaskReviewState) -> GoalSubtaskReviewState,
  ): GoalSubtaskReviewState? =
    database.transaction { unitOfWork ->
      val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@transaction null
      val state =
        reviewStateFromArtifacts(
          record.artifacts,
        ) ?: return@transaction null
      val updated = transform(state)
      if (updated == state) return@transaction state
      patcher.save(
        record,
        unitOfWork.workflowStates,
        mapOf(DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.entry(updated.toPersistenceWire())),
      )
      updated
    }

  internal fun completeGoalReviewPass(request: GoalReviewPassCompletionRequest): GoalSubtaskReviewState? =
    runtimeOwnedPersistence.requiredWrite(
      seam = "FeatureTaskRuntimeGoalContinuationRecorder.completeGoalReviewPass",
      expected = "runtime-owned review completion persistence",
    ) { unitOfWork ->
      val loaded = loadGoalReviewPassWrite(unitOfWork, request) ?: return@requiredWrite null
      val completed =
        loaded.state.completeReservedPass(
          request.verdict,
          request.unresolvedFindingCount,
          request.findings,
          loaded.dispositions,
          GoalSubtaskReviewRevision(commitFocusedAccounting = request.commitFocusedAccounting),
        )
      if (completed == loaded.state) {
        return@requiredWrite completed
      }
      persistGoalReviewPassWrite(unitOfWork, loaded, request, completed)
      completed
    }

  fun lastGoalReviewResult(workflowId: String): String? =
    database.read { unitOfWork ->
      val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@read null
      val artifacts = record.artifacts
      val state =
        reviewStateFromArtifacts(artifacts)
          ?: return@read null
      val passNumber = state.passResults.lastOrNull()?.passNumber ?: return@read null
      rawReviewResultsFromArtifacts(artifacts, state)[passNumber.toString()]
    }

  private data class GoalReviewPassWrite(
    val record: WorkflowStateSnapshot,
    val state: GoalSubtaskReviewState,
    val previousResults: Map<String, String>,
    val ledgerFindings: List<UnaddressedFinding>,
    val supersededFindings: List<UnaddressedFinding>,
    val dispositions: List<GoalSubtaskBlockerDisposition>,
  )

  private fun loadGoalReviewPassWrite(
    unitOfWork: UnitOfWork,
    request: GoalReviewPassCompletionRequest,
  ): GoalReviewPassWrite? {
    val record =
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, request.workflowId)
        ?: return null
    val artifacts = record.artifacts
    val state = reviewStateFromArtifacts(artifacts) ?: return null
    require(request.rawReviewResult.isNotBlank()) { "Goal-subtask review pass result must be non-blank." }
    val continuation =
      continuationFromArtifacts(artifacts)
        ?: error("Goal-subtask review continuation is missing during reserved-pass recovery.")
    val reservedPass = state.reservedPassNumber ?: 1
    val recordedVerdicts =
      GoalSubtaskReviewSummaryReducer.recordedVerdicts(
        unitOfWork.reviews::fetchFindingVerdicts,
        request.normalizedOutput,
      )
    val ledgerFindings =
      GoalSubtaskReviewSummaryReducer.unaddressedFindings(
        output = request.normalizedOutput,
        scope =
          UnaddressedFindingLedgerScope(
            issueKey = continuation.issueKey,
            subtaskId = continuation.subtaskId,
            workflowId = request.workflowId,
            reviewPassNumber = reservedPass,
          ),
        recordedVerdicts = recordedVerdicts,
      )
    val supersededFindings = unitOfWork.unaddressedFindings.fetchWorkflowLedger(request.workflowId)
    val dispositions = request.blockerDispositions
    return GoalReviewPassWrite(
      record = record,
      state = state,
      previousResults = rawReviewResultsFromArtifacts(artifacts, state),
      ledgerFindings = ledgerFindings,
      supersededFindings = supersededFindings,
      dispositions = dispositions,
    )
  }

  private fun persistGoalReviewPassWrite(
    unitOfWork: UnitOfWork,
    loaded: GoalReviewPassWrite,
    request: GoalReviewPassCompletionRequest,
    completed: GoalSubtaskReviewState,
  ) {
    val passNumber = completed.completedPassCount.toString()
    unitOfWork.unaddressedFindings.replaceLedgerForPass(request.workflowId, passNumber.toInt(), loaded.ledgerFindings)
    unitOfWork.unaddressedFindings.recordOutcomes(
      GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
        supersededFindings = loaded.supersededFindings,
        currentFindings = loaded.ledgerFindings,
        blockerDispositions = loaded.dispositions,
      ),
    )
    patcher.save(
      loaded.record,
      unitOfWork.workflowStates,
      mapOf(
        DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.entry(completed.toPersistenceWire()),
        DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_RESULTS.entry(
          loaded.previousResults + (passNumber to request.rawReviewResult),
        ),
      ),
    )
  }
}
