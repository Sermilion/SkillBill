package skillbill.engine.goalrunner.history

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePhasePayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.GoalChildHistoryDraftPayloadKeys
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.FeatureTaskRuntimeExecutionPlanValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.engine.model.isTerminalStatus
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.phaseRecordsFromWorkflowArtifacts
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

@Inject
class GoalChildHistoryEvidenceStore(
  private val snapshots: WorkflowSnapshotValidator,
  private val wireArtifacts: FeatureTaskRuntimeWireArtifactValidator,
  private val executionPlans: FeatureTaskRuntimeExecutionPlanValidator,
) {
  private val engine = WorkflowEngine()
  private val family = DurableWorkflowArtifactFamily.GOAL_CHILD_HISTORY_EVIDENCE

  internal fun capture(
    unit: UnitOfWork,
    parentId: String,
    subtaskId: Int,
    childId: String,
    acceptance: GoalRunnerOutOfBandAcceptance? = null,
  ) = guarded {
    val child = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, childId)
    if (child == null) {
      read(unit, parentId)
      return@guarded
    }
    snapshots.validate(child, child.workflowName)
    val continuationFamily = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION
    val continuation =
      JsonCodec.anyToStringAnyMap(continuationFamily.value(child.artifacts))
        ?: throw invalid("Child '$childId' has no continuation to preserve.")
    val record =
      GoalChildHistoryEvidence(
        parentId, subtaskId, childId,
        JsonCodec.anyToStringAnyMap(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(child.artifacts),
        ),
        continuation,
        phaseRecordsFromWorkflowArtifacts(
          child.artifacts,
        )[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY]
          ?.asWorkflowArtifactEntry()?.toMap(),
        acceptance,
        restoreEligible = acceptance != null,
        historyStepStatus =
          child.steps.singleOrNull {
            it.stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY
          }?.status,
      )
    val childDefinition = definition(record.executionPlan)
    validateIdentity(record, childDefinition)
    val recordedParentId = continuationParent(record)
    if (recordedParentId == null && !isCurrentChild(unit, parentId, subtaskId, childId)) {
      throw invalid("Legacy child '$childId' has no verified parent association to preserve.")
    }
    if (unit.workflowStates.getFeatureTaskExecutionIdentity(childId)?.routeScope != FeatureTaskRouteScope.GOAL_CHILD) {
      throw invalid("Child '$childId' is not owned by the goal-child route.")
    }
    val records = read(unit, parentId)
    val previous = records.singleOrNull { it.subtaskId == subtaskId && it.childWorkflowId == childId }
    val captured = mergeCapture(unit, previous, record, acceptance)
    if (acceptance != null && acceptance.subtaskId != subtaskId) {
      throw invalid("Preserved acceptance does not match subtask '$subtaskId'.")
    }
    save(unit, parentId, records.filterNot { it.subtaskId == subtaskId && it.childWorkflowId == childId } + captured)
  }

  private fun mergeCapture(
    unit: UnitOfWork,
    previous: GoalChildHistoryEvidence?,
    record: GoalChildHistoryEvidence,
    acceptance: GoalRunnerOutOfBandAcceptance?,
  ): GoalChildHistoryEvidence {
    val childId = record.childWorkflowId
    return if (previous == null) {
      record
    } else {
      validatePreservedIdentity(previous, record)
      val previousReady = historyReady(unit, previous)
      val previousDefinition = definition(previous.executionPlan)
      val historyRecord =
        if (previousReady && previousDefinition != SkeletonDefinition.GOAL_CHILD_REVISION_5) {
          if (historyReady(unit, record) && legacyDisposition(previous) != legacyDisposition(record)) {
            throw invalid("Preserved child '$childId' conflicts with its settled history disposition.")
          }
          previous.historyRecord to previous.historyStepStatus
        } else {
          validateProgress(previous, record)
          record.historyRecord to record.historyStepStatus
        }
      if (previous.acceptance != null && acceptance != null && previous.acceptance != acceptance) {
        throw invalid("Preserved child '$childId' conflicts with its recorded acceptance.")
      }
      record.copy(
        historyRecord = historyRecord.first,
        historyStepStatus = historyRecord.second,
        acceptance = acceptance ?: previous.acceptance,
        restoreEligible = acceptance != null || previous.restoreEligible,
        restored = false,
      )
    }
  }

  private fun validatePreservedIdentity(
    previous: GoalChildHistoryEvidence,
    record: GoalChildHistoryEvidence,
  ) {
    if (previous.executionPlan != record.executionPlan || previous.continuation != record.continuation) {
      throw invalid("Preserved child '${record.childWorkflowId}' conflicts with its recorded identity.")
    }
  }

  internal fun read(
    unit: UnitOfWork,
    parentId: String,
  ): List<GoalChildHistoryEvidence> =
    guarded {
      val parent =
        unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentId)
          ?: throw invalid("Parent '$parentId' is missing while reading history evidence.")
      snapshots.validate(parent, parent.workflowName)
      if (!family.contains(parent.artifacts)) return@guarded emptyList()
      val items =
        family.value(parent.artifacts) as? List<*>
          ?: throw invalid("Parent history evidence must be a list.")
      val records =
        items.map { item ->
          val map = JsonCodec.anyToStringAnyMap(item) ?: throw invalid("History evidence must be an object.")
          wireArtifacts.validate(
            FeatureTaskRuntimeWireArtifactKind.GOAL_CHILD_HISTORY_EVIDENCE,
            FeatureTaskRuntimeWorkflowArtifactMap.from(map),
            "preserved goal child",
          )
          decode(map).also {
            if (it.parentWorkflowId != parentId) {
              throw invalid("History evidence belongs to a different parent.")
            }
            validateIdentity(it, definition(it.executionPlan))
          }
        }
      if (records.map { it.subtaskId to it.childWorkflowId }.distinct().size != records.size) {
        throw invalid("History evidence contains duplicate child identities.")
      }
      records
    }

  internal fun retire(
    unit: UnitOfWork,
    parentId: String,
    subtaskId: Int,
  ) {
    retireEvidence(unit, parentId, subtaskId, null)
    val acceptances = unit.goalRunnerControls.outOfBandAcceptances(parentId)
    if (subtaskId in acceptances) {
      unit.goalRunnerControls.clearOutOfBandAcceptances(parentId)
      acceptances.filterKeys { it != subtaskId }.values.forEach {
        unit.goalRunnerControls.persistOutOfBandAcceptance(parentId, it)
      }
    }
  }

  internal fun retireOtherChildren(
    unit: UnitOfWork,
    parentId: String,
    subtaskId: Int,
    retainedChildId: String,
  ) {
    retireEvidence(unit, parentId, subtaskId, retainedChildId)
    val acceptances = unit.goalRunnerControls.outOfBandAcceptances(parentId)
    if (subtaskId in acceptances) {
      unit.goalRunnerControls.clearOutOfBandAcceptances(parentId)
      acceptances.filterKeys { it != subtaskId }.values.forEach {
        unit.goalRunnerControls.persistOutOfBandAcceptance(parentId, it)
      }
    }
  }

  internal fun retireEvidence(
    unit: UnitOfWork,
    parentId: String,
    subtaskId: Int,
    retainedChildId: String?,
  ) {
    val records = read(unit, parentId)
    val retired =
      records.map {
        if (it.subtaskId == subtaskId && it.childWorkflowId != retainedChildId) {
          it.copy(
            restoreEligible = false,
            restored = false,
          )
        } else {
          it
        }
      }
    if (records != retired) save(unit, parentId, retired)
  }

  internal fun retireChildEvidence(
    unit: UnitOfWork,
    parentId: String,
    subtaskId: Int,
    childWorkflowId: String,
  ) {
    val records = read(unit, parentId)
    val retired =
      records.map {
        if (it.subtaskId == subtaskId && it.childWorkflowId == childWorkflowId) {
          it.copy(restoreEligible = false, restored = false)
        } else {
          it
        }
      }
    if (records != retired) save(unit, parentId, retired)
  }

  internal fun restoredChildId(
    unit: UnitOfWork,
    parentId: String,
    subtaskId: Int,
  ): String? {
    val acceptance = unit.goalRunnerControls.outOfBandAcceptances(parentId)[subtaskId] ?: return null
    val subtask =
      unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentId)
        ?.decompositionRuntime()?.subtasks?.singleOrNull { it.id == subtaskId } ?: return null
    val incomplete = subtask.status.decompositionStatus() != DecompositionStatus.COMPLETE
    val stillActive = subtask.workflowId != null || subtask.branch != null
    val mismatched = subtask.commitSha != acceptance.commitSha || subtask.blockedReason != null
    val unsettled = mismatched || subtask.lastResumableStep != null
    if (incomplete || stillActive || unsettled) return null
    return read(unit, parentId).singleOrNull {
      it.subtaskId == subtaskId &&
        it.restored &&
        it.restoreEligible &&
        it.acceptance == acceptance
    }?.takeIf {
      historyReady(unit, it)
    }?.childWorkflowId
  }

  internal fun isCurrentChild(
    unit: UnitOfWork,
    parentId: String,
    subtaskId: Int,
    childWorkflowId: String,
  ): Boolean =
    unit.workflowStates.getFeatureTaskWorkflow(parentId)?.toSnapshot()
      ?.decompositionRuntime()?.subtasks?.singleOrNull { it.id == subtaskId }
      ?.workflowId == childWorkflowId

  internal fun markRestored(
    unit: UnitOfWork,
    record: GoalChildHistoryEvidence,
  ) {
    val records = read(unit, record.parentWorkflowId)
    save(
      unit,
      record.parentWorkflowId,
      records.map {
        if (it.subtaskId == record.subtaskId) it.copy(restored = it.childWorkflowId == record.childWorkflowId) else it
      },
    )
  }

  internal fun hasRequired(
    unit: UnitOfWork,
    parentId: String,
    subtaskId: Int,
    childId: String,
  ): Boolean =
    guarded {
      val child = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, childId)
      if (child == null) {
        return@guarded read(unit, parentId)
          .singleOrNull { it.subtaskId == subtaskId && it.childWorkflowId == childId }
          ?.let { historyReady(unit, it) } ?: false
      }
      snapshots.validate(child, child.workflowName)
      val route = unit.workflowStates.getFeatureTaskExecutionIdentity(childId)?.routeScope
      if (route != FeatureTaskRouteScope.GOAL_CHILD) {
        throw invalid("Child '$childId' is not owned by the goal-child route.")
      }
      val continuation =
        JsonCodec.anyToStringAnyMap(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.value(child.artifacts),
        )
          ?: throw invalid("Recorded child continuation is missing.")
      val evidence =
        GoalChildHistoryEvidence(
          parentId, subtaskId, childId,
          JsonCodec.anyToStringAnyMap(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(child.artifacts),
          ),
          continuation,
          phaseRecordsFromWorkflowArtifacts(
            child.artifacts,
          )[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY]
            ?.asWorkflowArtifactEntry()?.toMap(),
          null, false,
          historyStepStatus =
            child.steps.singleOrNull {
              it.stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY
            }?.status,
        )
      val childDefinition = definition(evidence.executionPlan)
      validateIdentity(evidence, childDefinition)
      if (!isCurrentChild(unit, parentId, subtaskId, childId)) return@guarded false
      historyReady(unit, evidence)
    }

  internal fun historyReady(
    unit: UnitOfWork,
    evidence: GoalChildHistoryEvidence,
  ): Boolean =
    guarded {
      val definition = definition(evidence.executionPlan)
      if (definition == SkeletonDefinition.GOAL_CHILD_REVISION_5) {
        val parent =
          unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, evidence.parentWorkflowId)
            ?: throw invalid("History evidence parent is missing.")
        return@guarded decodeHistoryDrafts(parent.artifacts, evidence.parentWorkflowId, wireArtifacts).any {
          it.subtaskId == evidence.subtaskId && it.childWorkflowId == evidence.childWorkflowId
        }
      }
      evidence.historyStepStatus == WorkflowStepStatus.SKIPPED || legacyHistoryReady(evidence.historyRecord)
    }

  internal fun legacyHistoryReady(record: Map<String, Any?>?): Boolean {
    if (record == null) return false
    val history =
      phaseRecordsFromWorkflowArtifacts(
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
            mapOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to record),
          ),
        ),
      )[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY]
        ?: throw invalid("Preserved history record is missing.")
    if (history.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY) {
      throw invalid("Preserved history phase identity is invalid.")
    }
    if (history.status == WorkflowStepStatus.SKIPPED) return true
    val text = history.outputArtifact
    if (history.status != WorkflowStepStatus.COMPLETED || text.isNullOrBlank()) return false
    val output =
      NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(
        text,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
      )
    return output.status == WorkflowStepStatus.COMPLETED.wireValue
  }

  private fun legacyDisposition(evidence: GoalChildHistoryEvidence): Pair<WorkflowStepStatus, String?>? {
    if (evidence.historyStepStatus == WorkflowStepStatus.SKIPPED) return WorkflowStepStatus.SKIPPED to null
    val record = evidence.historyRecord ?: return null
    val history =
      phaseRecordsFromWorkflowArtifacts(
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
            mapOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to record),
          ),
        ),
      )[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY] ?: return null
    return history.status to history.outputArtifact
  }

  private fun validateProgress(
    previous: GoalChildHistoryEvidence,
    current: GoalChildHistoryEvidence,
  ) {
    val priorAttempts = attemptCount(previous.historyRecord)
    val currentAttempts = attemptCount(current.historyRecord)
    if (priorAttempts != null && currentAttempts != null && currentAttempts < priorAttempts) {
      throw invalid("Preserved child '${current.childWorkflowId}' history attempt count moved backwards.")
    }
    if (priorAttempts != null && priorAttempts == currentAttempts) {
      val prior = previous.historyStepStatus
      val next = current.historyStepStatus
      val regressed = prior != null && next != null && historyProgressRank(next) < historyProgressRank(prior)
      val changedTerminal = prior != null && next != null && historyProgressRank(prior) == 2 && next != prior
      if (regressed || changedTerminal) {
        throw invalid("Preserved child '${current.childWorkflowId}' history progress conflicts at one attempt.")
      }
    }
  }

  private fun attemptCount(record: Map<String, Any?>?): Int? =
    (record?.get(FeatureTaskRuntimePhasePayloadKeys.ATTEMPT_COUNT) as? Number)
      ?.toString()?.toBigDecimalOrNull()?.intValueExact()

  private fun historyProgressRank(status: WorkflowStepStatus): Int =
    when (status) {
      WorkflowStepStatus.PENDING -> 0
      WorkflowStepStatus.RUNNING -> 1
      WorkflowStepStatus.FAILED,
      WorkflowStepStatus.BLOCKED,
      WorkflowStepStatus.COMPLETED,
      WorkflowStepStatus.SKIPPED,
      WorkflowStepStatus.PAUSED,
      -> 2
    }

  internal fun definition(plan: Map<String, Any?>?): SkeletonDefinition =
    guarded {
      if (plan == null) {
        throw invalid("Recorded child execution plan is missing.")
      }
      val canonical =
        executionPlans.canonicalize(
          JsonCodec.mapToJsonString(plan).toByteArray(),
          "recorded goal child",
        )
      val validated =
        JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(canonical.toString(Charsets.UTF_8)))
          ?: throw invalid("Recorded child execution plan is malformed.")
      val descriptor =
        JsonCodec.anyToStringAnyMap(validated[FeatureTaskRuntimeExecutionPlanKeys.DEFINITION])
          ?: throw invalid("Recorded child definition is missing.")
      val revision =
        (descriptor[FeatureTaskRuntimeExecutionPlanKeys.SEMANTIC_REVISION] as? Number)
          ?.toString()?.toBigDecimalOrNull()
      SkeletonDefinition.entries.singleOrNull {
        it.id == SkeletonDefinition.GOAL_CHILD.id && descriptor[FeatureTaskRuntimeExecutionPlanKeys.ID] == it.id &&
          revision == it.semanticRevision.toBigDecimal()
      } ?: throw invalid("Recorded goal-child definition is unsupported.")
    }

  private fun validateIdentity(
    record: GoalChildHistoryEvidence,
    childDefinition: SkeletonDefinition,
  ) {
    val artifacts =
      mapOf(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.entry(record.continuation),
      )
    val continuation = DurableWorkflowArtifacts.fromMap(artifacts).goalContinuationArtifact()
    val parentId = continuation?.parentWorkflowId
    val parentMismatch = parentId != null && parentId != record.parentWorkflowId
    val requiredParentMissing =
      childDefinition == SkeletonDefinition.GOAL_CHILD_REVISION_5 &&
        parentId != record.parentWorkflowId
    val subtaskMismatch = continuation == null || continuation.subtaskId != record.subtaskId
    if (subtaskMismatch || parentMismatch || requiredParentMissing) {
      throw invalid("Preserved continuation does not match parent and subtask identity.")
    }
  }

  private fun continuationParent(record: GoalChildHistoryEvidence): String? =
    DurableWorkflowArtifacts.fromMap(
      mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.entry(record.continuation)),
    ).goalContinuationArtifact()?.parentWorkflowId?.takeIf(String::isNotBlank)

  private fun save(
    unit: UnitOfWork,
    parentId: String,
    records: List<GoalChildHistoryEvidence>,
  ) = guarded {
    records.forEach {
      wireArtifacts.validate(
        FeatureTaskRuntimeWireArtifactKind.GOAL_CHILD_HISTORY_EVIDENCE,
        FeatureTaskRuntimeWorkflowArtifactMap.from(it.toWire()),
        "preserved goal child",
      )
    }
    val parent =
      unit.workflowStates.get(
        WorkflowFamily.TASK_RUNTIME,
        parentId,
      ) ?: throw invalid("History parent is missing.")
    val workflowFamily = WorkflowFamily.TASK_RUNTIME
    val terminal = workflowFamily.definition.isTerminalStatus(parent.workflowStatus)
    val updated =
      engine.updateRecord(
        workflowFamily.definition,
        parent,
        WorkflowUpdateInput(
          workflowStatus = parent.workflowStatus,
          currentStepId = parent.currentStepId,
          stepUpdates = null,
          artifactsPatch = WorkflowArtifactPatch.from(mapOf(family.entry(records.map { it.toWire() }))),
          sessionId = parent.sessionId,
          acceptedSnapshotMutation = terminal,
        ),
      )
    if (terminal) {
      unit.workflowStates.saveAcceptedSnapshotMutation(workflowFamily, updated)
    } else {
      unit.workflowStates.save(workflowFamily, updated)
    }
  }

  private fun decode(map: Map<String, Any?>): GoalChildHistoryEvidence {
    val identity = GoalChildHistoryDraftPayloadKeys
    val keys = GoalChildHistoryEvidenceKeys
    val subtaskId =
      (map[identity.SUBTASK_ID] as? Number)?.toString()?.toBigDecimalOrNull()?.intValueExact()
        ?: throw invalid("History evidence subtask is invalid.")
    val acceptance =
      JsonCodec.anyToStringAnyMap(map[keys.ACCEPTANCE])?.let {
        GoalRunnerOutOfBandAcceptance(
          subtaskId,
          it[DecompositionManifestPayloadKeys.COMMIT_SHA] as String,
          it[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ACCEPTANCE_REASON] as String,
          it[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ACCEPTED_AT] as String,
        )
      }
    return GoalChildHistoryEvidence(
      map[identity.PARENT_WORKFLOW_ID] as String, subtaskId, map[identity.CHILD_WORKFLOW_ID] as String,
      JsonCodec.anyToStringAnyMap(map[keys.EXECUTION_PLAN]),
      JsonCodec.anyToStringAnyMap(map[keys.CONTINUATION]) ?: throw invalid("Preserved continuation is missing."),
      JsonCodec.anyToStringAnyMap(map[keys.HISTORY_RECORD]), acceptance,
      map[keys.RESTORE_ELIGIBLE] as Boolean, map[keys.RESTORED] as Boolean,
      (map[keys.HISTORY_STEP_STATUS] as? String)?.let { WorkflowStepStatus.fromWire(it) },
    )
  }

  private inline fun <T> guarded(block: () -> T): T =
    try {
      block()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: SkillBillRuntimeException) {
      throw failure
    } catch (failure: IOException) {
      throw invalid("Required goal-child history evidence operation failed.", failure)
    } catch (failure: ArithmeticException) {
      throw invalid("Required goal-child history evidence operation failed.", failure)
    } catch (failure: IllegalArgumentException) {
      throw invalid("Required goal-child history evidence operation failed.", failure)
    }

  private fun invalid(
    message: String,
    cause: Throwable? = null,
  ) = SkillBillRuntimeException(WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE, message, cause)
}
