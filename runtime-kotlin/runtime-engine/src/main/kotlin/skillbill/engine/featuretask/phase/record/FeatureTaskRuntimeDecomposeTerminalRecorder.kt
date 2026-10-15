package skillbill.engine.featuretask.phase.record

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodePlanBundleFromArtifact
import skillbill.workflow.taskruntime.artifact.decomposeTerminalFromWorkflowArtifacts
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock

@Inject
class FeatureTaskRuntimeDecomposeTerminalRecorder(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
) {
  private val engine: WorkflowEngine = WorkflowEngine()

  fun recordDecomposeTerminal(
    workflowId: String,
    terminal: FeatureTaskRuntimeDecomposeTerminal,
    planStepId: String,
  ): Boolean =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@transaction false
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
      true
    }

  fun recordPlanBundle(
    workflowId: String,
    bundle: FeatureTaskRuntimePlanBundle,
  ): Boolean = recordPlanBundleWithUnits(workflowId, bundle, emptyMap())

  fun recordPlanBundleWithUnits(
    workflowId: String,
    bundle: FeatureTaskRuntimePlanBundle,
    unitRecords: Map<Int, FeatureTaskRuntimePhaseRecord>,
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
      val updated =
        engine.updateRecord(
          WorkflowFamily.TASK_RUNTIME.definition,
          record,
          WorkflowUpdateInput(
            workflowStatus = record.workflowStatus,
            currentStepId = record.currentStepId,
            stepUpdates = null,
            artifactsPatch = WorkflowArtifactPatch.from(patch),
            sessionId = record.sessionId.orEmpty(),
          ),
        )
      unitOfWork.workflowStates.save(WorkflowFamily.TASK_RUNTIME, updated)
      true
    }

  fun loadPlanBundle(workflowId: String): FeatureTaskRuntimePlanBundle? =
    database.read { unitOfWork ->
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
        ?.artifacts
        ?.toMap()
        ?.let(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE::value)
        ?.let(::decodePlanBundleFromArtifact)
    }

  fun loadDecomposeTerminal(workflowId: String): FeatureTaskRuntimeDecomposeTerminal? =
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
