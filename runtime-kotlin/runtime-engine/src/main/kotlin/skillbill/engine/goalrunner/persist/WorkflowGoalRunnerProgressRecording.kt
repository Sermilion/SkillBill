package skillbill.engine.goalrunner.persist

import skillbill.contracts.JsonCodec
import skillbill.contracts.issuekey.normalizeRequiredIssueKey
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.contracts.workflow.identity.status.WorkStatusArtifactKeys
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.phase.record.requireCurrentWorker
import skillbill.engine.goalrunner.execution.support.maxHistorySequence
import skillbill.engine.goalrunner.history.GoalChildHistoryEvidenceStore
import skillbill.engine.goalrunner.history.decodeHistoryDrafts
import skillbill.engine.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.model.history.GoalFinalizationHistoryPayloadKeys
import skillbill.engine.goalrunner.persist.quality.GoalFinalizationHistoryMigration
import skillbill.engine.goalrunner.persist.quality.fromPersistenceWire
import skillbill.engine.workflow.support.workflowFamilyFor
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.GoalObservabilityArtifacts
import skillbill.goalrunner.WORKER_SUBTASK_REQUEST_OUTCOME_LIMIT
import skillbill.goalrunner.goalProgressCurrentStepEnteredAt
import skillbill.goalrunner.ledger.backwardEdgeCountsFromLedger
import skillbill.goalrunner.ledger.declaredProgressEventFrom
import skillbill.goalrunner.ledger.decodeDeclaredGoalProgressEvent
import skillbill.goalrunner.ledger.progressEventFrom
import skillbill.goalrunner.ledger.summarizeAttemptLedgerStatusEntries
import skillbill.goalrunner.ledger.toProgressEvent
import skillbill.goalrunner.model.GOAL_ATTEMPT_LEDGER_LIMIT
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalObservabilityRuntimeEventInput
import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary
import skillbill.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.goalrunner.model.GoalRunnerWirePayload
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.model.history.GoalChildHistoryDraft
import skillbill.goalrunner.model.history.GoalChildHistoryDraftPayloadKeys
import skillbill.goalrunner.model.history.GoalChildHistoryDraftSaveResult
import skillbill.goalrunner.model.history.GoalFinalRepairContext
import skillbill.goalrunner.model.history.GoalFinalRepairOutput
import skillbill.goalrunner.model.history.GoalFinalizationHistoryRecord
import skillbill.goalrunner.model.history.GoalHistoryCompletion
import skillbill.goalrunner.model.history.GoalHistoryWriterEntry
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.toPersistenceWire
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.progressToken
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.goalobservability.GOAL_PROGRESS_HISTORY_LIMIT
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.goalObservabilityLatestEventFromArtifacts
import skillbill.workflow.model.persistence.artifact.appendBoundedHistoryBySequence
import skillbill.workflow.taskruntime.artifact.phaseRecordsFromWorkflowArtifacts
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.goalContinuation
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.io.IOException
import java.sql.SQLException
import kotlin.coroutines.cancellation.CancellationException

private fun nextSequence(highest: Int?): Int = highest?.let { it + 1 } ?: 0

private inline fun <T> withGoalChildHistoryDraftRead(block: () -> T): T =
  try {
    block()
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (failure: SkillBillRuntimeException) {
    throw failure
  } catch (failure: IOException) {
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
      "Required goal-child history draft persistence operation failed.",
      failure,
    )
  } catch (failure: IllegalArgumentException) {
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
      "Required goal-child history draft persistence operation failed.",
      failure,
    )
  }

private fun validateGoalObservabilityPatch(
  validator: FeatureTaskRuntimeWireArtifactValidator,
  patch: FeatureTaskRuntimeWorkflowArtifactMap,
) {
  val latestEvent = DurableWorkflowArtifactFamily.GOAL_OBSERVABILITY_LATEST_EVENT
  validator.validate(
    FeatureTaskRuntimeWireArtifactKind.GOAL_OBSERVABILITY_EVENT,
    FeatureTaskRuntimeWorkflowArtifactMap.from(latestEvent.value(patch)),
    latestEvent.label(),
  )
  val runHistory = DurableWorkflowArtifactFamily.GOAL_OBSERVABILITY_RUN_HISTORY
  (runHistory.value(patch) as List<*>).forEachIndexed { index, item ->
    validator.validate(
      FeatureTaskRuntimeWireArtifactKind.GOAL_OBSERVABILITY_EVENT,
      FeatureTaskRuntimeWorkflowArtifactMap.from(item),
      "${runHistory.label()}[$index]",
    )
  }
}

private class SequencedHistoryArtifact(
  val latestFamily: DurableWorkflowArtifactFamily?,
  val historyFamily: DurableWorkflowArtifactFamily,
  val retentionLimit: Int,
)

private val PROGRESS_POLL_ARTIFACT_KEYS =
  setOf(
    WorkStatusArtifactKeys.PROGRESS_EVENT,
    DurableWorkflowArtifactFamily.GOAL_PROGRESS_LATEST_EVENT.label(),
    DurableWorkflowArtifactFamily.GOAL_OBSERVABILITY_LATEST_EVENT.label(),
    DurableWorkflowArtifactFamily.GOAL_PROGRESS_RUN_HISTORY.label(),
  )

internal class WorkflowGoalRunnerProgressRecording(
  private val database: DatabaseSessionFactory,
  private val engine: WorkflowEngine,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val wireArtifactValidator: FeatureTaskRuntimeWireArtifactValidator,
  private val historyEvidence: GoalChildHistoryEvidenceStore,
) : GoalRunnerWorkflowProgressStore,
  GoalRunnerWorkflowLedgerWriteStore,
  GoalRunnerGoalChildHistoryDraftStore,
  GoalRunnerAttemptLedgerStore {
  override fun progress(workflowId: String): GoalRunnerWorkflowProgress? =
    database.read { unitOfWork -> progress(unitOfWork, workflowId) }

  override fun progress(
    unitOfWork: UnitOfWork,
    workflowId: String,
  ): GoalRunnerWorkflowProgress? {
    val family = WorkflowFamily.TASK_RUNTIME
    val record = unitOfWork.workflowStates.readGoalStatusSnapshot(workflowId) ?: return null
    workflowSnapshotValidator.validate(record, family.definition.workflowName)
    val steps = record.steps
    val artifacts = record.artifacts.filterKeys(PROGRESS_POLL_ARTIFACT_KEYS::contains)
    val finishCompleted = steps.any { step -> step.stepId == "pr" && step.status == WorkflowStepStatus.COMPLETED }
    val currentStep =
      if (record.workflowStatus == WorkflowStatus.COMPLETED || finishCompleted) {
        "pr"
      } else {
        record.currentStepId
      }
    val progressEvent = progressEventFrom(artifacts)
    val declaredProgressEvent = declaredProgressEventFrom(artifacts)
    val observabilityEvent =
      runCatching {
        goalObservabilityLatestEventFromArtifacts(artifacts)
      }.getOrNull()
    val progress =
      GoalRunnerWorkflowProgress(
        workflowId = record.workflowId,
        workflowStatus = record.workflowStatus,
        currentStepId = currentStep,
        progressToken = record.progressToken(),
        latestDurableProgressEvent = progressEvent,
        latestGoalObservabilityEvent = observabilityEvent?.toProgressEvent(),
        latestDeclaredProgressEvent = declaredProgressEvent,
        lastSnapshotUpdatedAt = record.updatedAt?.toString(),
        currentStepEnteredAt =
          runCatching {
            DurableWorkflowArtifacts.fromMap(artifacts).goalProgressCurrentStepEnteredAt()
          }.getOrNull(),
      )
    return progress.copy(latestLivenessSignal = progress.resolvedLivenessSignal())
  }

  override fun recordObservabilityEvent(request: GoalRunnerObservabilityRecordRequest): Boolean =
    database.transaction { unitOfWork ->
      val family =
        workflowFamilyFor(unitOfWork.workflowStates, request.workflowId)
          ?: return@transaction false
      val record =
        unitOfWork.workflowStates.get(family, request.workflowId)
          ?: return@transaction false
      val artifacts = record.artifacts
      val observabilityPatch =
        GoalObservabilityArtifacts.patchForRuntimeEvent(
          input =
            GoalObservabilityRuntimeEventInput(
              artifacts = FeatureTaskRuntimeWorkflowArtifactMap.from(artifacts),
              request = request,
            ),
        )
      validateGoalObservabilityPatch(wireArtifactValidator, observabilityPatch)
      engine.saveRunnerArtifactPatch(unitOfWork.workflowStates, family, record, observabilityPatch)
      true
    }

  override fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest): Boolean =
    appendSequencedHistoryArtifact(
      workflowId = request.workflowId,
      issueKey = request.issueKey,
      history =
        SequencedHistoryArtifact(
          latestFamily = DurableWorkflowArtifactFamily.GOAL_PROGRESS_LATEST_EVENT,
          historyFamily = DurableWorkflowArtifactFamily.GOAL_PROGRESS_RUN_HISTORY,
          retentionLimit = GOAL_PROGRESS_HISTORY_LIMIT,
        ),
    ) { sequenceNumber, record ->
      val event =
        request.draft.toEvent(sequenceNumber)
          .withContinuingLaunch(declaredProgressEventFrom(record.artifacts))
      GoalRunnerWirePayload.from(event.toPersistenceWire()).payload.also { entryMap ->
        wireArtifactValidator.validate(
          FeatureTaskRuntimeWireArtifactKind.GOAL_PROGRESS_EVENT,
          FeatureTaskRuntimeWorkflowArtifactMap.from(entryMap),
          DurableWorkflowArtifactFamily.GOAL_PROGRESS_LATEST_EVENT.label(),
        )
      }
    }

  override fun recordAttemptLedgerEntry(request: GoalRunnerAttemptLedgerRecordRequest): Boolean =
    appendSequencedHistoryArtifact(
      workflowId = request.workflowId,
      issueKey = request.issueKey,
      history =
        SequencedHistoryArtifact(
          latestFamily = null,
          historyFamily = DurableWorkflowArtifactFamily.GOAL_ATTEMPT_LEDGER,
          retentionLimit = GOAL_ATTEMPT_LEDGER_LIMIT,
        ),
    ) { sequenceNumber, _ ->
      GoalRunnerWirePayload.from(request.draft.toEntry(sequenceNumber).toPersistenceWire()).payload
    }

  override fun saveGoalChildHistoryDraft(
    draft: GoalChildHistoryDraft,
    admitted: AdmittedFeatureTaskRuntimeExecution?,
  ): GoalChildHistoryDraftSaveResult {
    try {
      draft.validate()
    } catch (failure: IllegalArgumentException) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
        "Invalid goal-child history draft.",
        failure,
      )
    }
    validateHistoryDraft(draft.toPersistenceWire(), "accepted goal-child history draft")
    return withGoalChildHistoryDraftRead {
      database.transaction { unitOfWork ->
        if (admitted != null && !requireCurrentWorker(admitted, unitOfWork.workflowStates, draft.childWorkflowId)) {
          return@transaction GoalChildHistoryDraftSaveResult.Conflict
        }
        val parent = draftParent(unitOfWork, draft.parentWorkflowId)
        val stored = decodeHistoryDrafts(parent.artifacts, draft.parentWorkflowId, wireArtifactValidator)
        val prior =
          stored.firstOrNull {
            it.subtaskId == draft.subtaskId && it.childWorkflowId == draft.childWorkflowId
          }
        validateRecordedDraftChild(unitOfWork, draft, prior == draft)
        if (prior != null) {
          if (prior == draft) GoalChildHistoryDraftSaveResult.Saved(prior) else GoalChildHistoryDraftSaveResult.Conflict
        } else {
          engine.saveRunnerArtifactPatch(
            unitOfWork.workflowStates,
            WorkflowFamily.TASK_RUNTIME,
            parent,
            mapOf(
              DurableWorkflowArtifactFamily.GOAL_CHILD_HISTORY_DRAFTS.entry(
                stored.map(GoalChildHistoryDraft::toPersistenceWire) + draft.toPersistenceWire(),
              ),
            ),
          )
          GoalChildHistoryDraftSaveResult.Saved(draft)
        }
      }
    }
  }

  private fun draftParent(
    unit: UnitOfWork,
    parentId: String,
  ): WorkflowStateSnapshot {
    val parent =
      unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentId)
        ?: throw invalidGoalChildDefinition("Parent task-runtime workflow '$parentId' is missing.")
    workflowSnapshotValidator.validate(parent, WorkflowFamily.TASK_RUNTIME.definition.workflowName)
    return parent
  }

  private fun validateRecordedDraftChild(
    unit: UnitOfWork,
    draft: GoalChildHistoryDraft,
    replay: Boolean,
  ) {
    val child = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, draft.childWorkflowId)
    if (child == null) {
      if (replay && historyEvidence.hasRequired(unit, draft.parentWorkflowId, draft.subtaskId, draft.childWorkflowId)) {
        return
      }
      throw invalidGoalChildDefinition("Recorded child workflow is missing.")
    }
    workflowSnapshotValidator.validate(child, WorkflowFamily.TASK_RUNTIME.definition.workflowName)
    val continuation =
      DurableWorkflowArtifacts.fromMap(child.artifacts).goalContinuationArtifact()

    if (recordedGoalChildDefinition(child.artifacts) != SkeletonDefinition.GOAL_CHILD_REVISION_5 ||
      continuation?.parentWorkflowId != draft.parentWorkflowId || continuation.subtaskId != draft.subtaskId
    ) {
      throw invalidGoalChildDefinition("Draft identity does not match a revision 5 goal child.")
    }
  }

  override fun goalChildHistoryDrafts(parentWorkflowId: String): List<GoalChildHistoryDraft> =
    withGoalChildHistoryDraftRead {
      database.read { unitOfWork ->
        val parent = draftParent(unitOfWork, parentWorkflowId)
        decodeHistoryDrafts(parent.artifacts, parentWorkflowId, wireArtifactValidator)
      }
    }

  fun recordGoalFinalRepairOutput(
    parentWorkflowId: String,
    expectedInvocationId: String,
    output: GoalFinalRepairOutput,
  ): Boolean =
    withFinalHistoryPersistence {
      if (output.invocationId != expectedInvocationId) return@withFinalHistoryPersistence false
      updateGoalFinalizationHistory(parentWorkflowId, expectedInvocationId) { prior ->
        val context = prior.repairContext ?: GoalFinalRepairContext(emptyList())
        val sameIdentity =
          context.outputs.firstOrNull {
            it.phaseId == output.phaseId && it.invocationId == output.invocationId &&
              it.occurrence == output.occurrence
          }
        if (sameIdentity != null && sameIdentity != output) {
          invalidFinalHistory("Conflicting accepted repair output identity.")
        }
        if (sameIdentity != null) {
          prior
        } else {
          prior.copy(
            repairContext = context.copy(outputs = context.outputs + output),
            completion = null,
          )
        }
      }
    }

  fun recordGoalFinalRepairWarning(
    parentWorkflowId: String,
    expectedInvocationId: String,
    warning: String?,
  ): Boolean =
    withFinalHistoryPersistence {
      updateGoalFinalizationHistory(parentWorkflowId, expectedInvocationId) { prior ->
        val context = prior.repairContext ?: GoalFinalRepairContext(emptyList())
        if (prior.repairContext != null && context.warning == warning) {
          prior
        } else {
          prior.copy(repairContext = context.copy(warning = warning), completion = null)
        }
      }
    }

  fun goalFinalRepairContext(parentWorkflowId: String): GoalFinalRepairContext? =
    withFinalHistoryPersistence {
      readGoalFinalizationHistory(parentWorkflowId).repairContext
    }

  fun saveGoalHistoryCompletion(
    parentWorkflowId: String,
    expectedInvocationId: String,
    completion: GoalHistoryCompletion,
  ): Boolean =
    withFinalHistoryPersistence {
      updateGoalFinalizationHistory(
        parentWorkflowId,
        expectedInvocationId,
      ) { prior -> prior.copy(completion = completion) }
    }

  fun goalHistoryCompletion(parentWorkflowId: String): GoalHistoryCompletion? =
    withFinalHistoryPersistence {
      readGoalFinalizationHistory(parentWorkflowId).completion
    }

  fun goalHistoryWriterEntries(
    parentWorkflowId: String,
    expectedSelection: List<Pair<Int, String?>>,
  ): List<GoalHistoryWriterEntry> =
    withFinalHistoryPersistence {
      database.transaction { unitOfWork ->
        expectedSelection.forEach { (subtaskId, childId) ->
          if (childId != null) historyEvidence.capture(unitOfWork, parentWorkflowId, subtaskId, childId)
        }
        historyEvidence.goalHistoryWriterEntries(unitOfWork, parentWorkflowId, expectedSelection)
      }
    }

  private fun updateGoalFinalizationHistory(
    parentWorkflowId: String,
    expectedInvocationId: String,
    transform: (GoalFinalizationHistoryRecord) -> GoalFinalizationHistoryRecord,
  ): Boolean =
    database.transaction { unitOfWork ->
      val parent = draftParent(unitOfWork, parentWorkflowId)
      val qualityPayload =
        DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.value(parent.artifacts)
          ?: return@transaction false
      val qualityMap =
        JsonCodec.anyToStringAnyMap(qualityPayload)
          ?: invalidFinalHistory("Quality execution is not an object.")
      wireArtifactValidator.validate(
        FeatureTaskRuntimeWireArtifactKind.GOAL_QUALITY_EXECUTION,
        FeatureTaskRuntimeWorkflowArtifactMap.from(qualityMap),
        DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.label(),
      )
      val quality = GoalQualityExecutionRecord.fromPersistenceWire(qualityMap).execution
      if (quality == null || quality.invocationId != expectedInvocationId ||
        quality.status == GoalQualityExecutionStatus.RETIRED
      ) {
        return@transaction false
      }
      val raw = DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.value(parent.artifacts)
      val prior = decodeGoalFinalizationHistory(raw)
      val updated = transform(prior)
      val payload = updated.toPersistenceWire()
      validateGoalFinalizationHistory(payload)
      engine.saveRunnerArtifactPatch(
        unitOfWork.workflowStates,
        WorkflowFamily.TASK_RUNTIME,
        parent,
        mapOf(DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.entry(payload)),
      )
      true
    }

  private fun readGoalFinalizationHistory(parentWorkflowId: String): GoalFinalizationHistoryRecord =
    database.transaction { unitOfWork ->
      val parent = draftParent(unitOfWork, parentWorkflowId)
      val payload = DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.value(parent.artifacts)
      if (payload == null) {
        GoalFinalizationHistoryRecord()
      } else {
        val source = JsonCodec.anyToStringAnyMap(payload) ?: invalidFinalHistory("Stored record is not an object.")
        val map = GoalFinalizationHistoryMigration.upgrade(source)
        validateGoalFinalizationHistory(map)
        if (map != source) {
          engine.saveRunnerArtifactPatch(
            unitOfWork.workflowStates,
            WorkflowFamily.TASK_RUNTIME,
            parent,
            mapOf(DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.entry(map)),
          )
        }
        decodeGoalFinalizationHistory(map)
      }
    }

  private fun decodeGoalFinalizationHistory(payload: Any?): GoalFinalizationHistoryRecord {
    if (payload == null) return GoalFinalizationHistoryRecord()
    val map = JsonCodec.anyToStringAnyMap(payload) ?: invalidFinalHistory("Stored record is not an object.")
    val current = GoalFinalizationHistoryMigration.upgrade(map)
    validateGoalFinalizationHistory(current)
    val contextMap = JsonCodec.anyToStringAnyMap(current[GoalFinalizationHistoryPayloadKeys.REPAIR_CONTEXT])
    val outputs =
      (contextMap?.get(GoalFinalizationHistoryPayloadKeys.OUTPUTS) as? List<*>)?.map { value ->
        val item = JsonCodec.anyToStringAnyMap(value) ?: invalidFinalHistory("Repair output is not an object.")
        GoalFinalRepairOutput(
          phaseId = item.getValue(GoalFinalizationHistoryPayloadKeys.PHASE_ID) as String,
          invocationId = item.getValue(GoalFinalizationHistoryPayloadKeys.INVOCATION_ID) as String,
          occurrence = (item.getValue(GoalFinalizationHistoryPayloadKeys.OCCURRENCE) as Number).toInt(),
          output = item.getValue(GoalFinalizationHistoryPayloadKeys.OUTPUT) as String,
        )
      }.orEmpty()
    val repairContext =
      contextMap?.let {
        GoalFinalRepairContext(outputs, it[GoalFinalizationHistoryPayloadKeys.WARNING] as String?)
      }
    val completionMap = JsonCodec.anyToStringAnyMap(current[GoalFinalizationHistoryPayloadKeys.COMPLETION])
    val completion =
      completionMap?.let {
        GoalHistoryCompletion(
          inputFingerprint = it.getValue(GoalFinalizationHistoryPayloadKeys.INPUT_FINGERPRINT) as String,
          preWriterContentFingerprint =
            it.getValue(GoalFinalizationHistoryPayloadKeys.PRE_WRITER_CONTENT_FINGERPRINT) as String,
          postWriterContentFingerprint =
            it.getValue(GoalFinalizationHistoryPayloadKeys.POST_WRITER_CONTENT_FINGERPRINT) as String,
          acceptedOutput = it.getValue(GoalFinalizationHistoryPayloadKeys.ACCEPTED_OUTPUT) as String,
        )
      }
    return GoalFinalizationHistoryRecord(repairContext = repairContext, completion = completion)
  }

  private fun validateGoalFinalizationHistory(payload: Map<String, Any?>) {
    wireArtifactValidator.validate(
      FeatureTaskRuntimeWireArtifactKind.GOAL_FINALIZATION_HISTORY,
      FeatureTaskRuntimeWorkflowArtifactMap.from(payload),
      DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.label(),
    )
  }

  private fun invalidFinalHistory(message: String): Nothing =
    throw SkillBillRuntimeException(WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY, message)

  private fun <T> withFinalHistoryPersistence(block: () -> T): T =
    try {
      block()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: SkillBillRuntimeException) {
      throw failure
    } catch (failure: IOException) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
        "Required goal finalization history persistence operation failed.",
        failure,
      )
    } catch (failure: SQLException) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
        "Required goal finalization history persistence operation failed.",
        failure,
      )
    }

  override fun hasRequiredGoalChildHistoryDraft(
    parentWorkflowId: String,
    subtaskId: Int,
    childWorkflowId: String,
  ): Boolean =
    withGoalChildHistoryDraftRead {
      database.read { unitOfWork ->
        hasRequiredGoalChildHistoryDraft(unitOfWork, parentWorkflowId, subtaskId, childWorkflowId)
      }
    }

  override fun hasRequiredGoalChildHistoryDraft(
    unitOfWork: UnitOfWork,
    parentWorkflowId: String,
    subtaskId: Int,
    childWorkflowId: String,
  ): Boolean =
    withGoalChildHistoryDraftRead {
      historyEvidence.hasRequired(unitOfWork, parentWorkflowId, subtaskId, childWorkflowId)
    }

  internal fun isCurrentGoalChild(
    unitOfWork: UnitOfWork,
    parentWorkflowId: String,
    subtaskId: Int,
    childWorkflowId: String,
  ): Boolean = historyEvidence.isCurrentChild(unitOfWork, parentWorkflowId, subtaskId, childWorkflowId)

  private fun recordedGoalChildDefinition(artifacts: Map<String, Any?>): SkeletonDefinition =
    historyEvidence.definition(
      JsonCodec.anyToStringAnyMap(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(artifacts),
      ),
    )

  private fun invalidGoalChildDefinition(message: String): SkillBillRuntimeException =
    SkillBillRuntimeException(WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT, message)

  private fun validateHistoryDraft(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) {
    wireArtifactValidator.validate(
      FeatureTaskRuntimeWireArtifactKind.GOAL_CHILD_HISTORY_DRAFT,
      FeatureTaskRuntimeWorkflowArtifactMap.from(payload),
      sourceLabel,
    )
  }

  override fun progressEvents(workflowId: String): List<GoalProgressEvent> =
    database.transaction { unitOfWork ->
      val family =
        workflowFamilyFor(unitOfWork.workflowStates, workflowId)
          ?: return@transaction emptyList()
      val record =
        unitOfWork.workflowStates.get(family, workflowId)
          ?: return@transaction emptyList()
      (DurableWorkflowArtifactFamily.GOAL_PROGRESS_RUN_HISTORY.value(record.artifacts) as? List<*>)
        .orEmpty()
        .mapNotNull { item -> item as? Map<*, *> }
        .mapNotNull { item -> JsonCodec.anyToStringAnyMap(item) }
        .map {
            map ->
          map.decodeDeclaredGoalProgressEvent(DurableWorkflowArtifactFamily.GOAL_PROGRESS_RUN_HISTORY.label())
        }
    }

  override fun recordWorkerSubtaskRequestOutcomes(
    workflowId: String,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
  ): Boolean =
    database.transaction { unitOfWork ->
      val family =
        workflowFamilyFor(unitOfWork.workflowStates, workflowId)
          ?: return@transaction false
      val record =
        unitOfWork.workflowStates.get(family, workflowId)
          ?: return@transaction false
      val artifacts = record.artifacts
      val existing =
        (DurableWorkflowArtifactFamily.WORKER_SUBTASK_REQUEST_OUTCOMES.value(artifacts) as? List<*>)
          .orEmpty()
          .mapNotNull { item -> item as? Map<*, *> }
          .map { item -> JsonCodec.anyToStringAnyMap(item) }
      val updatedOutcomes =
        (existing + outcomes.map(GoalRunnerWorkerSubtaskRequestOutcome::toPersistenceWire))
          .takeLast(WORKER_SUBTASK_REQUEST_OUTCOME_LIMIT)
      engine.saveRunnerArtifactPatch(
        unitOfWork.workflowStates,
        family,
        record,
        mapOf(DurableWorkflowArtifactFamily.WORKER_SUBTASK_REQUEST_OUTCOMES.entry(updatedOutcomes)),
      )
      true
    }

  override fun ledgerSequenceWatermarks(issueKey: String): GoalRunnerLedgerSequenceWatermarks =
    database.read { unitOfWork ->
      val normalizedIssueKey = normalizeRequiredIssueKey(issueKey)
      val backwardEdgeCounts = mutableMapOf<String, Int>()
      unitOfWork.workflowStates.findFeatureTaskWorkflowsForIssue(
        FeatureTaskWorkflowMode.RUNTIME,
        normalizedIssueKey,
      ).forEach { row ->
        val snapshot = row.toSnapshot()
        val artifacts = snapshot.artifacts
        if (DurableWorkflowArtifacts.fromMap(artifacts).goalContinuation()?.issueKey != normalizedIssueKey) {
          return@forEach
        }
        backwardEdgeCountsFromLedger(artifacts).forEach { (key, count) ->
          backwardEdgeCounts.merge(key, count, ::maxOf)
        }
      }
      GoalRunnerLedgerSequenceWatermarks(backwardEdgeCounts = backwardEdgeCounts)
    }

  override fun childWorkflowLoopIterations(workflowId: String): Map<String, Int> =
    database.read { unitOfWork ->
      val family = workflowFamilyFor(unitOfWork.workflowStates, workflowId) ?: return@read emptyMap()
      val record = unitOfWork.workflowStates.get(family, workflowId) ?: return@read emptyMap()
      val artifacts = record.artifacts
      val result = mutableMapOf<String, Int>()
      phaseRecordsFromWorkflowArtifacts(artifacts).values.forEach { phaseRecord ->
        val loopId = phaseRecord.loopId ?: return@forEach
        val edgeIteration = phaseRecord.edgeIteration ?: return@forEach
        result.merge(loopId, edgeIteration, ::maxOf)
      }
      result
    }

  override fun readAttemptLedgerSummary(issueKey: String): GoalRunnerAttemptLedgerSummary =
    attemptLedgerSummary(issueKey, null)

  override fun readAttemptLedgerSummaryForSubtasks(
    issueKey: String,
    subtaskIds: Set<Int>,
  ): GoalRunnerAttemptLedgerSummary = attemptLedgerSummary(issueKey, subtaskIds)

  private fun attemptLedgerSummary(
    issueKey: String,
    subtaskIds: Set<Int>?,
  ): GoalRunnerAttemptLedgerSummary =
    database.read { unitOfWork ->
      val normalizedIssueKey = normalizeRequiredIssueKey(issueKey)
      val backwardEdgeToken = GoalAttemptLedgerAction.BACKWARD_EDGE_ENTRY.wireValue
      val entries =
        sequence {
          var childOffset = 0
          do {
            val children =
              unitOfWork.workflowStates.readGoalStatusChildPage(
                normalizedIssueKey,
                childOffset,
                WorkPaginationLimits.MAX_PAGE_SIZE,
              )
            children.forEach { child ->
              var ledgerOffset = 0
              do {
                val page =
                  unitOfWork.workflowStates.readGoalAttemptLedgerPage(
                    child.workflowId,
                    ledgerOffset,
                    WorkPaginationLimits.MAX_PAGE_SIZE,
                  )
                yieldAll(
                  page.filter { entry ->
                    val backwardEdge =
                      entry.action == backwardEdgeToken
                    subtaskIds == null || !backwardEdge ||
                      entry.subtaskId in subtaskIds
                  },
                )
                ledgerOffset += page.size
              } while (page.size == WorkPaginationLimits.MAX_PAGE_SIZE)
            }
            childOffset += children.size
          } while (children.size == WorkPaginationLimits.MAX_PAGE_SIZE)
        }
      summarizeAttemptLedgerStatusEntries(entries.asIterable())
    }

  private fun appendSequencedHistoryArtifact(
    workflowId: String,
    issueKey: String,
    history: SequencedHistoryArtifact,
    buildEntry: (Int, WorkflowStateSnapshot) -> Any,
  ): Boolean =
    database.transaction { unitOfWork ->
      val historyFamily = history.historyFamily
      val family =
        workflowFamilyFor(unitOfWork.workflowStates, workflowId)
          ?: return@transaction false
      val record =
        unitOfWork.workflowStates.get(family, workflowId)
          ?: return@transaction false
      val artifacts = record.artifacts
      val normalizedIssueKey = normalizeRequiredIssueKey(issueKey)
      var highest = maxHistorySequence(artifacts, historyFamily, null)
      unitOfWork.workflowStates.findFeatureTaskWorkflowsForIssue(
        FeatureTaskWorkflowMode.RUNTIME,
        normalizedIssueKey,
      ).forEach { row ->
        val snapshot = row.toSnapshot()
        if (
          DurableWorkflowArtifacts.fromMap(snapshot.artifacts).goalContinuation()?.issueKey == normalizedIssueKey
        ) {
          highest = maxHistorySequence(snapshot.artifacts, historyFamily, highest)
        }
      }
      val entryMap =
        checkNotNull(JsonCodec.anyToStringAnyMap(buildEntry(nextSequence(highest), record))) {
          "${historyFamily.label()} entry must encode as an object."
        }
      val existing =
        (historyFamily.value(artifacts) as? List<*>)
          .orEmpty()
          .mapNotNull { item -> item as? Map<*, *> }
          .mapNotNull { item -> JsonCodec.anyToStringAnyMap(item) }
      val updatedHistory = appendBoundedHistoryBySequence(existing, entryMap, history.retentionLimit)
      val patch =
        buildMap<String, Any?> {
          historyFamily.putInto(this, updatedHistory)
          history.latestFamily?.putInto(this, entryMap)
        }
      engine.saveRunnerArtifactPatch(unitOfWork.workflowStates, family, record, patch)
      true
    }
}

private fun GoalChildHistoryDraft.toPersistenceWire(): Map<String, Any?> =
  buildMap {
    put(GoalChildHistoryDraftPayloadKeys.CONTRACT_VERSION, contractVersion)
    put(GoalChildHistoryDraftPayloadKeys.PARENT_WORKFLOW_ID, parentWorkflowId)
    put(GoalChildHistoryDraftPayloadKeys.SUBTASK_ID, subtaskId)
    put(GoalChildHistoryDraftPayloadKeys.CHILD_WORKFLOW_ID, childWorkflowId)
    put(GoalChildHistoryDraftPayloadKeys.DISPOSITION, disposition.wireValue)
    boundary?.let { put(GoalChildHistoryDraftPayloadKeys.BOUNDARY, it) }
    history?.let { put(GoalChildHistoryDraftPayloadKeys.HISTORY, it) }
    decisions?.let { put(GoalChildHistoryDraftPayloadKeys.DECISIONS, it) }
    skipReason?.let { put(GoalChildHistoryDraftPayloadKeys.SKIP_REASON, it) }
  }

private fun GoalFinalizationHistoryRecord.toPersistenceWire(): Map<String, Any?> =
  buildMap {
    put(GoalFinalizationHistoryPayloadKeys.CONTRACT_VERSION, contractVersion)
    put(
      GoalFinalizationHistoryPayloadKeys.REPAIR_CONTEXT,
      repairContext?.let { context ->
        buildMap {
          put(
            GoalFinalizationHistoryPayloadKeys.OUTPUTS,
            context.outputs.map { output ->
              mapOf(
                GoalFinalizationHistoryPayloadKeys.PHASE_ID to output.phaseId,
                GoalFinalizationHistoryPayloadKeys.INVOCATION_ID to output.invocationId,
                GoalFinalizationHistoryPayloadKeys.OCCURRENCE to output.occurrence,
                GoalFinalizationHistoryPayloadKeys.OUTPUT to output.output,
              )
            },
          )
          put(GoalFinalizationHistoryPayloadKeys.WARNING, context.warning)
        }
      },
    )
    put(
      GoalFinalizationHistoryPayloadKeys.COMPLETION,
      completion?.let {
        mapOf(
          GoalFinalizationHistoryPayloadKeys.INPUT_FINGERPRINT to it.inputFingerprint,
          GoalFinalizationHistoryPayloadKeys.PRE_WRITER_CONTENT_FINGERPRINT to it.preWriterContentFingerprint,
          GoalFinalizationHistoryPayloadKeys.POST_WRITER_CONTENT_FINGERPRINT to it.postWriterContentFingerprint,
          GoalFinalizationHistoryPayloadKeys.ACCEPTED_OUTPUT to it.acceptedOutput,
        )
      },
    )
  }
