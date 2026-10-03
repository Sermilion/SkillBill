package skillbill.engine.goalrunner.persist

import skillbill.contracts.JsonCodec
import skillbill.engine.goalrunner.execution.support.maxHistorySequence
import skillbill.engine.goalrunner.execution.support.workflowFamilyFor
import skillbill.engine.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.goalrunner.GoalObservabilityArtifacts
import skillbill.goalrunner.WORKER_SUBTASK_REQUEST_OUTCOME_LIMIT
import skillbill.goalrunner.ledger.backwardEdgeCountsFromLedger
import skillbill.goalrunner.ledger.declaredProgressEventFrom
import skillbill.goalrunner.ledger.decodeDeclaredGoalProgressEvent
import skillbill.goalrunner.ledger.progressEventFrom
import skillbill.goalrunner.ledger.summarizeAttemptLedgerFromEntries
import skillbill.goalrunner.ledger.summary
import skillbill.goalrunner.ledger.toProgressEvent
import skillbill.goalrunner.model.GOAL_ATTEMPT_LEDGER_LIMIT
import skillbill.goalrunner.model.GoalObservabilityRuntimeEventInput
import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary
import skillbill.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.goalrunner.model.GoalRunnerWirePayload
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.toPersistenceWire
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.engine.progressToken
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

private fun nextSequence(highest: Int?): Int = highest?.let { it + 1 } ?: 0

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
    "progress_event",
    DurableWorkflowArtifactFamily.GOAL_PROGRESS_LATEST_EVENT.label(),
    DurableWorkflowArtifactFamily.GOAL_OBSERVABILITY_LATEST_EVENT.label(),
  )

internal class WorkflowGoalRunnerProgressRecording(
  private val database: DatabaseSessionFactory,
  private val engine: WorkflowEngine,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val wireArtifactValidator: FeatureTaskRuntimeWireArtifactValidator,
) : GoalRunnerWorkflowProgressStore,
  GoalRunnerWorkflowLedgerWriteStore,
  GoalRunnerAttemptLedgerStore {
  override fun progress(workflowId: String): GoalRunnerWorkflowProgress? =
    database.read { unitOfWork ->
      val family = workflowFamilyFor(unitOfWork.workflowStates, workflowId) ?: return@read null
      val record = unitOfWork.workflowStates.get(family, workflowId) ?: return@read null
      workflowSnapshotValidator.validate(record, family.definition.workflowName)
      val steps = record.steps
      val artifacts = record.artifacts.filterKeys(PROGRESS_POLL_ARTIFACT_KEYS::contains)
      val finishCompleted =
        steps.any {
            step ->
          step.stepId == "pr" && step.status == WorkflowStepStatus.COMPLETED
        }
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
      GoalRunnerWorkflowProgress(
        workflowId = record.workflowId,
        workflowStatus = record.workflowStatus,
        currentStepId = currentStep,
        progressToken = record.progressToken(),
        latestDurableProgressEvent = progressEvent,
        latestGoalObservabilityEvent = observabilityEvent?.toProgressEvent(),
        latestDeclaredProgressEvent = declaredProgressEvent,
        latestLivenessSignal =
          observabilityEvent?.compactLivenessSummary()
            ?: progressEvent?.summary()
            ?: "workflow_status=${record.workflowStatus}; step=$currentStep",
        lastSnapshotUpdatedAt = record.updatedAt?.toString(),
      )
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
      val updated =
        engine.updateRecord(
          family.definition,
          record,
          WorkflowUpdateInput(
            workflowStatus = record.workflowStatus,
            currentStepId = record.currentStepId,
            stepUpdates = null,
            artifactsPatch = WorkflowArtifactPatch.from(observabilityPatch),
            sessionId = record.sessionId.orEmpty(),
          ),
        )
      unitOfWork.workflowStates.save(family, updated)
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
    ) { sequenceNumber ->
      GoalRunnerWirePayload.from(request.draft.toEvent(sequenceNumber).toPersistenceWire()).payload.also { entryMap ->
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
    ) { sequenceNumber ->
      GoalRunnerWirePayload.from(request.draft.toEntry(sequenceNumber).toPersistenceWire()).payload
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
      val updated =
        engine.updateRecord(
          family.definition,
          record,
          WorkflowUpdateInput(
            workflowStatus = record.workflowStatus,
            currentStepId = record.currentStepId,
            stepUpdates = null,
            artifactsPatch =
              WorkflowArtifactPatch.from(
                mapOf(DurableWorkflowArtifactFamily.WORKER_SUBTASK_REQUEST_OUTCOMES.entry(updatedOutcomes)),
              ),
            sessionId = record.sessionId.orEmpty(),
          ),
        )
      unitOfWork.workflowStates.save(family, updated)
      true
    }

  override fun ledgerSequenceWatermarks(issueKey: String): GoalRunnerLedgerSequenceWatermarks =
    database.read { unitOfWork ->
      val normalizedIssueKey = issueKey.trim()
      val backwardEdgeCounts = mutableMapOf<String, Int>()
      unitOfWork.workflowStates.list(WorkflowFamily.TASK_RUNTIME, Int.MAX_VALUE).forEach { snapshot ->
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
    database.read { unitOfWork ->
      val normalizedIssueKey = issueKey.trim()
      val entries =
        buildList {
          listOf(WorkflowFamily.TASK_RUNTIME).forEach { family ->
            unitOfWork.workflowStates.list(family, Int.MAX_VALUE).forEach { snapshot ->
              val artifacts = snapshot.artifacts
              if (
                DurableWorkflowArtifacts.fromMap(artifacts).goalContinuation()?.issueKey != normalizedIssueKey
              ) {
                return@forEach
              }
              (DurableWorkflowArtifactFamily.GOAL_ATTEMPT_LEDGER.value(artifacts) as? List<*>)
                .orEmpty()
                .forEach { item ->
                  (item as? Map<*, *>)?.let(::add)
                }
            }
          }
        }
      summarizeAttemptLedgerFromEntries(entries)
    }

  private fun appendSequencedHistoryArtifact(
    workflowId: String,
    issueKey: String,
    history: SequencedHistoryArtifact,
    buildEntry: (Int) -> Any,
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
      val normalizedIssueKey = issueKey.trim()
      var highest = maxHistorySequence(artifacts, historyFamily, null)
      unitOfWork.workflowStates.list(WorkflowFamily.TASK_RUNTIME, Int.MAX_VALUE).forEach { snapshot ->
        if (
          DurableWorkflowArtifacts.fromMap(snapshot.artifacts).goalContinuation()?.issueKey == normalizedIssueKey
        ) {
          highest = maxHistorySequence(snapshot.artifacts, historyFamily, highest)
        }
      }
      val entryMap =
        checkNotNull(JsonCodec.anyToStringAnyMap(buildEntry(nextSequence(highest)))) {
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
      val updated =
        engine.updateRecord(
          family.definition,
          record,
          WorkflowUpdateInput(
            workflowStatus = record.workflowStatus,
            currentStepId = record.currentStepId,
            stepUpdates = null,
            artifactsPatch = WorkflowArtifactPatch.from(patch),
            sessionId = record.sessionId.orEmpty(),
          ),
        )
      unitOfWork.workflowStates.save(family, updated)
      true
    }
}
