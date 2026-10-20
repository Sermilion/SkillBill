package skillbill.engine.featuretask.phase.record

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.taskruntime.artifact.phaseRecords
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

class FeatureTaskRuntimePhaseQuery
  @Inject
  constructor(
    private val database: DatabaseSessionFactory,
  ) {
    fun existingWorkflowMode(workflowId: String): FeatureTaskWorkflowMode? =
      database.read { unitOfWork ->
        unitOfWork.workflowStates.readFeatureTaskWorkflowHeader(workflowId)?.mode
      }

    fun currentStepId(workflowId: String): String? =
      database.read { unitOfWork ->
        unitOfWork.workflowStates.readFeatureTaskWorkflowHeader(workflowId)?.currentStepId
      }

    internal fun terminalWorkflow(workflowId: String): WorkflowStateRecord? =
      database.read { unitOfWork ->
        unitOfWork.workflowStates.getFeatureTaskWorkflow(workflowId)
          ?.takeIf { it.workflowStatus.workflowStatus() in WorkflowStatus.terminalStatuses }
      }

    fun workerOwnership(workflowId: String): FeatureTaskRuntimeWorkerOwnership? =
      database.read { unitOfWork ->
        unitOfWork.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId)
      }

    fun loadPhaseRecords(workflowId: String): Map<String, FeatureTaskRuntimePhaseRecord>? =
      database.read { unitOfWork ->
        unitOfWork.workflowStates.readFeatureTaskArtifacts(
          workflowId,
          setOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS),
        )?.phaseRecords()
      }

    fun loadPhaseResultArtifacts(workflowId: String): DurableWorkflowArtifacts? =
      database.read { unitOfWork ->
        unitOfWork.workflowStates.readFeatureTaskArtifacts(
          workflowId,
          setOf(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS,
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION,
            DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE,
            DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_RESULTS,
          ),
        )
      }

    fun visitPhaseLedger(
      workflowId: String,
      consume: (FeatureTaskRuntimePhaseLedgerEntry) -> Unit,
    ): Boolean =
      database.read { unit ->
        var offset: Int? = 0
        do {
          val page =
            unit.workflowStates.readFeatureTaskPhaseLedgerPage(
              workflowId,
              requireNotNull(offset),
              WorkPaginationLimits.MAX_PAGE_SIZE,
            ) ?: return@read false
          page.entries.forEach(consume)
          offset = page.nextOffset
        } while (offset != null)
        true
      }

    fun loadPhaseLedger(workflowId: String): List<FeatureTaskRuntimePhaseLedgerEntry>? =
      database.read { unitOfWork ->
        val record =
          unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
            ?: return@read null
        decodePhaseLedger(record.artifacts)
      }
  }
