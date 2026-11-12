package skillbill.engine.featuretask.phase.record

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityRepairAppliedWrite
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWrite
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWriteResult
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibilityStateCodec
import skillbill.engine.featuretask.phase.planning.feasibilityRepairWriteResult
import skillbill.engine.featuretask.phase.planning.planBundleFeasibilityWriteResult
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodePlanBundleFromArtifact
import skillbill.workflow.taskruntime.artifact.decodePlanFanOutHistoryFromArtifact
import skillbill.workflow.taskruntime.artifact.decomposeTerminalFromWorkflowArtifacts
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanFanOutHistory
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock

@Inject
public class FeatureTaskRuntimeDecomposeTerminalRecorder(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val feasibilityCodec: PlanBundleFeasibilityStateCodec,
  private val diagnostics: RuntimeDiagnostics,
) {
  public val engine: WorkflowEngine = WorkflowEngine()

  internal fun recordOwnedDecomposeTerminal(
    workflowId: String,
    terminal: FeatureTaskRuntimeDecomposeTerminal,
    planStepId: String,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean =
    database.transaction { unitOfWork ->
      if (!requireCurrentWorker(admitted, unitOfWork.workflowStates, workflowId)) {
        return@transaction false
      }
      recordDecomposeTerminalInTransaction(unitOfWork, workflowId, terminal, planStepId)
    }

  public fun recordDecomposeTerminal(
    workflowId: String,
    terminal: FeatureTaskRuntimeDecomposeTerminal,
    planStepId: String,
  ): Boolean =
    database.transaction { unitOfWork ->
      recordDecomposeTerminalInTransaction(unitOfWork, workflowId, terminal, planStepId)
    }

  private fun recordDecomposeTerminalInTransaction(
    unitOfWork: UnitOfWork,
    workflowId: String,
    terminal: FeatureTaskRuntimeDecomposeTerminal,
    planStepId: String,
  ): Boolean {
    val record =
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
        ?: return false
    val updated =
      engine.updateRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        record,
        WorkflowUpdateInput(
          terminalInstant = clock.instant(),
          workflowStatus = WorkflowStatus.COMPLETED,
          currentStepId = planStepId,
          stepUpdates = null,
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf(
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL.entry(
                  terminal.asWorkflowArtifactEntry(),
                ),
              ),
            ),
          sessionId = record.sessionId.orEmpty(),
        ),
      )
    unitOfWork.workflowStates.save(WorkflowFamily.TASK_RUNTIME, updated)
    return true
  }

  public fun recordPlanBundle(
    workflowId: String,
    bundle: FeatureTaskRuntimePlanBundle,
  ): Boolean = recordPlanBundleWithUnits(workflowId, bundle, emptyMap())

  public fun replacePlanBundle(
    workflowId: String,
    bundle: FeatureTaskRuntimePlanBundle,
  ): Boolean = replacePlanBundleWithUnits(workflowId, bundle, emptyMap())

  public fun recordPlanBundleWithUnits(
    workflowId: String,
    bundle: FeatureTaskRuntimePlanBundle,
    unitRecords: Map<Int, FeatureTaskRuntimePhaseRecord>,
  ): Boolean = writePlanBundle(workflowId, bundle, unitRecords, replace = false)

  public fun replacePlanBundleWithUnits(
    workflowId: String,
    bundle: FeatureTaskRuntimePlanBundle,
    unitRecords: Map<Int, FeatureTaskRuntimePhaseRecord>,
  ): Boolean = writePlanBundle(workflowId, bundle, unitRecords, replace = true)

  private fun writePlanBundle(
    workflowId: String,
    bundle: FeatureTaskRuntimePlanBundle,
    unitRecords: Map<Int, FeatureTaskRuntimePhaseRecord>,
    replace: Boolean,
  ): Boolean =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@transaction false
      val bundleEntry =
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.entry(bundle.asWorkflowArtifactEntry())
      val patch =
        if (unitRecords.isEmpty()) {
          mapOf(bundleEntry)
        } else {
          val records =
            LinkedHashMap(decodePhaseRecords(record.artifacts)).apply {
              unitRecords.forEach { (subtaskId, unitRecord) ->
                put(FeatureTaskRuntimePhaseWorkflowDefinition.planSubtaskUnitRecordId(subtaskId), unitRecord)
              }
            }
          mapOf(
            bundleEntry,
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
              records.mapValues { (_, value) -> value.asWorkflowArtifactEntry() },
            ),
          )
        }
      val completePatch =
        patch.toMutableMap().apply {
          if (replace) {
            putAll(
              mapOf(
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_FAN_OUT_HISTORY.entry(
                  FeatureTaskRuntimePlanFanOutHistory(emptyList()).asWorkflowArtifactEntry(),
                ),
              ),
            )
          }
        }
      val updated =
        engine.updateRecord(
          WorkflowFamily.TASK_RUNTIME.definition,
          record,
          WorkflowUpdateInput(
            workflowStatus = record.workflowStatus,
            currentStepId = record.currentStepId,
            stepUpdates = null,
            artifactsPatch = WorkflowArtifactPatch.from(completePatch),
            sessionId = record.sessionId.orEmpty(),
          ),
        )
      unitOfWork.workflowStates.save(WorkflowFamily.TASK_RUNTIME, updated)
      true
    }

  public fun loadPlanFanOutHistory(workflowId: String): FeatureTaskRuntimePlanFanOutHistory? =
    database.read { unitOfWork ->
      val artifacts =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)?.artifacts?.toMap()
          ?: return@read null
      val raw =
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_FAN_OUT_HISTORY.value(artifacts)
          ?: return@read null
      decodePlanFanOutHistoryFromArtifact(raw)
    }

  public fun recordPlanFanOutWave(
    workflowId: String,
    phase: Pair<String, Int>,
    capacity: Int,
    unitIds: List<Int>,
  ): Boolean =
    database.transaction { unitOfWork ->
      recordPlanFanOutWaveInTransaction(unitOfWork, workflowId, phase, capacity, unitIds)
    }

  internal fun recordOwnedPlanFanOutWave(
    workflowId: String,
    phase: Pair<String, Int>,
    capacity: Int,
    unitIds: List<Int>,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean =
    database.transaction { unitOfWork ->
      if (!requireCurrentWorker(admitted, unitOfWork.workflowStates, workflowId)) return@transaction false
      recordPlanFanOutWaveInTransaction(unitOfWork, workflowId, phase, capacity, unitIds)
    }

  private fun recordPlanFanOutWaveInTransaction(
    unitOfWork: UnitOfWork,
    workflowId: String,
    phase: Pair<String, Int>,
    capacity: Int,
    unitIds: List<Int>,
  ): Boolean {
    val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return false
    val phaseRecord = decodePhaseRecords(record.artifacts)[phase.first]
    val phaseAttempt =
      if (phaseRecord?.status == WorkflowStepStatus.RUNNING) {
        phaseRecord.attemptCount
      } else {
        (phaseRecord?.attemptCount ?: 0) + 1
      }
    if (phaseAttempt != phase.second) return false
    val artifacts = record.artifacts.toMap()
    val current =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_FAN_OUT_HISTORY.value(artifacts)
        ?.let { raw ->
          decodePlanFanOutHistoryFromArtifact(raw)
        } ?: FeatureTaskRuntimePlanFanOutHistory(emptyList(), complete = false)
    val updatedHistory = current.record(phase.second, capacity, unitIds)
    if (updatedHistory == current) return true
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
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_FAN_OUT_HISTORY.entry(
                  updatedHistory.asWorkflowArtifactEntry(),
                ),
              ),
            ),
          sessionId = record.sessionId.orEmpty(),
        ),
      )
    unitOfWork.workflowStates.save(WorkflowFamily.TASK_RUNTIME, updated)
    return true
  }

  public fun loadPlanBundle(workflowId: String): FeatureTaskRuntimePlanBundle? =
    database.read { unitOfWork ->
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
        ?.artifacts
        ?.toMap()
        ?.let(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE::value)
        ?.let(::decodePlanBundleFromArtifact)
    }

  public fun loadPlanBundleFeasibility(workflowId: String): FeatureTaskRuntimePlanBundleFeasibilityState? =
    try {
      database.read { unitOfWork ->
        val artifacts =
          unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)?.artifacts?.toMap()
            ?: return@read null
        if (!DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY.contains(artifacts)) {
          return@read null
        }
        feasibilityCodec.decode(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY.value(artifacts),
          "$workflowId/${DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY.label()}",
        )
      }
    } catch (failure: SkillBillRuntimeException) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Could not read plan bundle feasibility for workflow '$workflowId', artifact " +
          "'${DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY.label()}'; " +
          "preserve the original artifact and recover with a compatible runtime or restore the source workflow record.",
        failure,
      )
      throw failure
    }

  public fun writePlanBundleFeasibility(
    request: PlanBundleFeasibilityStateWrite,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): PlanBundleFeasibilityStateWriteResult {
    val result =
      try {
        database.transaction { unitOfWork ->
          if (admitted != null && !requireCurrentWorker(admitted, unitOfWork.workflowStates, request.workflowId)) {
            return@transaction PlanBundleFeasibilityStateWriteResult.Refused("worker ownership changed")
          }
          val record =
            unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, request.workflowId)
              ?: return@transaction PlanBundleFeasibilityStateWriteResult.Refused("workflow is missing")
          val phases = decodePhaseRecords(record.artifacts)
          val phase = phases[request.phaseId]
          val effectiveAttempt =
            if (phase?.status == WorkflowStepStatus.RUNNING) phase.attemptCount else (phase?.attemptCount ?: 0) + 1
          if (effectiveAttempt != request.attempt) {
            return@transaction PlanBundleFeasibilityStateWriteResult.Refused("phase attempt changed")
          }
          val artifacts = record.artifacts.toMap()
          val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY
          val current =
            if (family.contains(artifacts)) {
              feasibilityCodec.decode(family.value(artifacts), "${request.workflowId}/${family.label()}")
            } else {
              null
            }
          val writeResult = planBundleFeasibilityWriteResult(current, request)
          if (writeResult is PlanBundleFeasibilityStateWriteResult.Applied) {
            val encoded = feasibilityCodec.encode(writeResult.state)
            saveFeasibilityState(unitOfWork, record, encoded)
          }
          writeResult
        }
      } catch (failure: SkillBillRuntimeException) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Could not read plan bundle feasibility for workflow '${request.workflowId}', artifact " +
            "'${DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY.label()}', " +
            "phase '${request.phaseId}' attempt ${request.attempt}; preserve the original artifact and recover " +
            "with a compatible runtime or restore the source workflow record.",
          failure,
        )
        throw failure
      }
    if (result is PlanBundleFeasibilityStateWriteResult.Refused) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Refused plan bundle feasibility write for workflow '${request.workflowId}', artifact " +
          "'${DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY.label()}', " +
          "phase '${request.phaseId}' attempt ${request.attempt}, expected revision ${request.expectedRevision}, " +
          "observed revision ${result.observedRevision}: ${result.reason}.",
      )
    }
    return result
  }

  public fun recordFeasibilityRepairApplied(
    request: PlanBundleFeasibilityRepairAppliedWrite,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): PlanBundleFeasibilityStateWriteResult =
    database.transaction { unitOfWork ->
      if (admitted != null && !requireCurrentWorker(admitted, unitOfWork.workflowStates, request.workflowId)) {
        return@transaction PlanBundleFeasibilityStateWriteResult.Refused("worker ownership changed")
      }
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, request.workflowId)
          ?: return@transaction PlanBundleFeasibilityStateWriteResult.Refused("workflow is missing")
      val phases = decodePhaseRecords(record.artifacts)
      val phase = phases[request.phaseId]
      val effectiveAttempt =
        if (phase?.status == WorkflowStepStatus.RUNNING) {
          phase.attemptCount
        } else {
          (phase?.attemptCount ?: 0) + 1
        }
      if (effectiveAttempt != request.attempt) {
        return@transaction PlanBundleFeasibilityStateWriteResult.Refused("phase attempt changed")
      }
      val artifacts = record.artifacts.toMap()
      val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY
      if (!family.contains(artifacts)) {
        return@transaction PlanBundleFeasibilityStateWriteResult.Refused("feasibility artifact is missing")
      }
      val current = feasibilityCodec.decode(family.value(artifacts), "${request.workflowId}/${family.label()}")
      val beforeBundle =
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE
          .value(artifacts)?.let(::decodePlanBundleFromArtifact)
      val decision = feasibilityRepairWriteResult(current, request, beforeBundle)
      if (decision !is PlanBundleFeasibilityStateWriteResult.Applied) return@transaction decision
      val encoded = feasibilityCodec.encode(decision.state)
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
                  family.entry(encoded),
                  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.entry(
                    request.bundle.asWorkflowArtifactEntry(),
                  ),
                ),
              ),
            sessionId = record.sessionId.orEmpty(),
          ),
        )
      unitOfWork.workflowStates.save(WorkflowFamily.TASK_RUNTIME, updated)
      decision
    }

  private fun saveFeasibilityState(
    unitOfWork: UnitOfWork,
    record: WorkflowStateSnapshot,
    encoded: FeatureTaskRuntimeWorkflowArtifactMap,
  ) {
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
              mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY.entry(encoded)),
            ),
          sessionId = record.sessionId.orEmpty(),
        ),
      )
    unitOfWork.workflowStates.save(WorkflowFamily.TASK_RUNTIME, updated)
  }

  public fun loadDecomposeTerminal(workflowId: String): FeatureTaskRuntimeDecomposeTerminal? =
    database.read { unitOfWork ->
      val artifacts =
        unitOfWork.workflowStates.readFeatureTaskArtifacts(
          workflowId,
          setOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL),
        )
          ?: return@read null
      decomposeTerminalFromWorkflowArtifacts(artifacts)
    }
}
