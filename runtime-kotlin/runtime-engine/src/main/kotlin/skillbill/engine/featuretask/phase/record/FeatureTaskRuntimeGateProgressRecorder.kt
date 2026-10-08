package skillbill.engine.featuretask.phase.record

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodeNoChangePauseFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeReadinessEvidenceFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeValidationGateProgressFromArtifact
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangePause
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.GoalSubtaskReviewArtifactDecoder
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
          ?: throw invalidWorkflowStateSchemaError(
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
          ?: throw invalidWorkflowStateSchemaError(
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

  fun loadNoChangePause(workflowId: String): FeatureTaskRuntimeNoChangePause? =
    database.read { unitOfWork ->
      val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@read null
      decodeNoChangePauseFromArtifact(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_NO_CHANGE_PAUSE.value(record.artifacts),
      )
    }

  fun persistNoChangePause(
    workflowId: String,
    pause: FeatureTaskRuntimeNoChangePause,
  ) {
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: throw invalidWorkflowStateSchemaError(
            "Cannot persist no-change pause: workflow '$workflowId' is missing.",
          )
      workflowPersistence.persistArtifactsPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_NO_CHANGE_PAUSE.entry(pause.asWorkflowArtifactEntry()),
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
          ?: throw invalidWorkflowStateSchemaError(
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
