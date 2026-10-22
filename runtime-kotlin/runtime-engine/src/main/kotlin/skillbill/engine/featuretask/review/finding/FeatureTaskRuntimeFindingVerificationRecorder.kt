package skillbill.engine.featuretask.review.finding

import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.WorkflowRowAdvance
import skillbill.engine.featuretask.phase.record.requireCurrentWorker
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition

class FeatureTaskRuntimeFindingVerificationRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
) {
  fun loadFindingVerificationCheckpoint(workflowId: String): List<FeatureTaskRuntimeFindingVerificationDisposition>? =
    database.transaction { unitOfWork ->
      val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@transaction null
      val artifacts = record.artifacts
      findingVerificationCheckpointFrom(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT.value(artifacts),
      )
    }

  fun loadFindingVerificationBoundarySelection(
    workflowId: String,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? =
    database.transaction { unitOfWork ->
      val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@transaction null
      val artifacts = record.artifacts
      findingVerificationBoundarySelectionFrom(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION.value(artifacts),
      )
    }

  fun persistOwnedFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean {
    if (selections.isEmpty()) return false
    return database.transaction { unitOfWork ->
      if (!requireCurrentWorker(admitted, unitOfWork.workflowStates, workflowId)) {
        return@transaction false
      }
      persistFindingVerificationBoundarySelectionInTransaction(unitOfWork, workflowId, selections)
    }
  }

  fun persistFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean {
    if (selections.isEmpty()) return false
    return database.transaction { unitOfWork ->
      persistFindingVerificationBoundarySelectionInTransaction(unitOfWork, workflowId, selections)
    }
  }

  private fun persistFindingVerificationBoundarySelectionInTransaction(
    unitOfWork: UnitOfWork,
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean {
    val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return false
    val artifacts = record.artifacts
    workflowPersistence.persistArtifactsPatch(
      unitOfWork.workflowStates,
      record,
      mapOf(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION.entry(
          selections.mapValues { (_, headings) -> headings.map { it.asWorkflowArtifactEntry() } },
        ),
      ),
      WorkflowRowAdvance.keepFrom(record),
    )
    return true
  }

  fun loadFindingVerificationDispositions(
    workflowId: String,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>? =
    database.transaction { unitOfWork ->
      val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@transaction null
      val artifacts = record.artifacts
      findingVerificationCheckpointFrom(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS.value(artifacts),
      )
    }

  fun persistOwnedFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean {
    if (dispositions.isEmpty()) return false
    return database.transaction { unitOfWork ->
      if (!requireCurrentWorker(admitted, unitOfWork.workflowStates, workflowId)) {
        return@transaction false
      }
      persistFindingVerificationCheckpointInTransaction(unitOfWork, workflowId, dispositions)
    }
  }

  fun persistFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean {
    if (dispositions.isEmpty()) return false
    return database.transaction { unitOfWork ->
      persistFindingVerificationCheckpointInTransaction(unitOfWork, workflowId, dispositions)
    }
  }

  private fun persistFindingVerificationCheckpointInTransaction(
    unitOfWork: UnitOfWork,
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean {
    val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return false
    val serialized = dispositions.map { it.asWorkflowArtifactEntry() }
    workflowPersistence.persistArtifactsPatch(
      unitOfWork.workflowStates,
      record,
      mapOf(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT.entry(serialized),
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS.entry(serialized),
      ),
      WorkflowRowAdvance.keepFrom(record),
    )
    return true
  }

  fun clearFindingVerificationCheckpoint(workflowId: String): Boolean =
    database.transaction { unitOfWork ->
      val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@transaction false
      val artifacts = record.artifacts
      if (
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT.value(artifacts) == null
      ) {
        return@transaction true
      }
      workflowPersistence.persistArtifactsPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT.entry(null)),
        WorkflowRowAdvance.keepFrom(record),
      )
      true
    }

  private fun findingVerificationBoundarySelectionFrom(
    raw: Any?,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? {
    val entries = raw as? Map<*, *> ?: return null
    return entries.mapNotNull { (findingIdRaw, headingsRaw) ->
      val findingId = findingIdRaw as? String ?: return@mapNotNull null
      val headings =
        FeatureTaskRuntimeVerificationBoundaryHeadingProvenance.parseList(
          headingsRaw,
          "${DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION.label()}" +
            ".$findingId",
        )
      findingId to headings
    }.toMap()
  }

  private fun findingVerificationCheckpointFrom(raw: Any?): List<FeatureTaskRuntimeFindingVerificationDisposition>? {
    if (raw == null) return null
    return FeatureTaskRuntimeFindingVerificationDisposition.parseList(
      raw,
      "finding_verification_checkpoint",
    )
  }
}
