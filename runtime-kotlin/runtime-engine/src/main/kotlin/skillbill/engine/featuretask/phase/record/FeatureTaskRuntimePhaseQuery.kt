package skillbill.engine.featuretask.phase.record

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

class FeatureTaskRuntimePhaseQuery
  @Inject
  constructor(
    private val database: DatabaseSessionFactory,
  ) {
    fun existingWorkflowMode(workflowId: String): FeatureTaskWorkflowMode? =
      database.read { unitOfWork ->
        unitOfWork.workflowStates.getFeatureTaskWorkflow(workflowId)?.mode
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
        val record =
          unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
            ?: return@read null
        decodePhaseRecords(record.artifacts)
      }

    fun loadPhaseLedger(workflowId: String): List<FeatureTaskRuntimePhaseLedgerEntry>? =
      database.read { unitOfWork ->
        val record =
          unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
            ?: return@read null
        decodePhaseLedger(record.artifacts)
      }
  }
