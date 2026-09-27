package skillbill.engine.featuretask.phase.record

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodeReadinessEvidenceFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeValidationGateProgressFromArtifact
import skillbill.workflow.taskruntime.model.persistence.task.runtime.goal.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.task.runtime.goal.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeReadinessEvidence
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress

interface FeatureTaskRuntimeReadinessEvidencePort {
  fun loadReadinessEvidence(workflowId: String): FeatureTaskRuntimeReadinessEvidence?

  fun persistReadinessEvidence(
    workflowId: String,
    evidence: FeatureTaskRuntimeReadinessEvidence,
  )
}

class FeatureTaskRuntimeGateProgressRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
) : FeatureTaskRuntimeReadinessEvidencePort {
  fun loadValidationGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress? =
    database.read { unitOfWork ->
      val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@read null
      val raw = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS.value(record.artifacts)
      val artifact = JsonCodec.anyToStringAnyMap(raw) ?: return@read null
      decodeValidationGateProgressFromArtifact(artifact)
    }

  fun persistValidationGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  ) {
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: throw InvalidWorkflowStateSchemaError(
            "Cannot persist validation gate progress: workflow '$workflowId' is missing.",
          )
      workflowPersistence.persistArtifactsPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS.entry(
            progress.asWorkflowArtifactEntry(),
          ),
        ),
      )
    }
  }

  fun loadBuildGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress? =
    database.read { unitOfWork ->
      val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@read null
      val raw = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS.value(record.artifacts)
      val artifact = JsonCodec.anyToStringAnyMap(raw) ?: return@read null
      decodeValidationGateProgressFromArtifact(artifact)
    }

  fun loadGoalContinuation(workflowId: String): FeatureTaskRuntimeGoalContinuationArtifact? =
    database.read { unitOfWork ->
      val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@read null
      GoalSubtaskReviewArtifactDecoder.decodeContinuationOnly(record.artifacts)
    }

  override fun loadReadinessEvidence(workflowId: String): FeatureTaskRuntimeReadinessEvidence? =
    database.read { unitOfWork ->
      val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@read null
      val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_READINESS_EVIDENCE
      val raw = family.value(record.artifacts)
      val artifact = JsonCodec.anyToStringAnyMap(raw) ?: return@read null
      decodeReadinessEvidenceFromArtifact(artifact, family.label())
    }

  override fun persistReadinessEvidence(
    workflowId: String,
    evidence: FeatureTaskRuntimeReadinessEvidence,
  ) {
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: throw InvalidWorkflowStateSchemaError(
            "Cannot persist readiness evidence: workflow '$workflowId' is missing.",
          )
      workflowPersistence.persistArtifactsPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_READINESS_EVIDENCE.entry(
            evidence.asWorkflowArtifactEntry(),
          ),
        ),
      )
    }
  }

  fun persistBuildGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  ) {
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: throw InvalidWorkflowStateSchemaError(
            "Cannot persist build gate progress: workflow '$workflowId' is missing.",
          )
      workflowPersistence.persistArtifactsPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS.entry(
            progress.asWorkflowArtifactEntry(),
          ),
        ),
      )
    }
  }
}
