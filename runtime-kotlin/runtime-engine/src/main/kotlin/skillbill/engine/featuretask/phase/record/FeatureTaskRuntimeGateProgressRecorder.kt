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
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.phase.record.requireCurrentWorker
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import java.time.Clock

interface FeatureTaskRuntimeReadinessEvidencePort {
  fun loadReadinessEvidence(workflowId: String): FeatureTaskRuntimeReadinessEvidence?

  fun persistReadinessEvidence(
    workflowId: String,
    evidence: FeatureTaskRuntimeReadinessEvidence,
  )
}

interface FeatureTaskRuntimeNoChangePausePort {
  /** The no-change pause a confirmed no-change claim left on [workflowId], if any. */
  fun loadNoChangePause(workflowId: String): FeatureTaskRuntimeNoChangePause?

  /** Persists the no-change pause of a confirmed no-change claim. */
  fun persistNoChangePause(
    workflowId: String,
    pause: FeatureTaskRuntimeNoChangePause,
  )
}

class FeatureTaskRuntimeGateProgressRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  private val clock: Clock,
) : FeatureTaskRuntimeReadinessEvidencePort,
  FeatureTaskRuntimeNoChangePausePort {
  fun loadValidationGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress? =
    database.read { unitOfWork ->
      val artifacts =
        unitOfWork.workflowStates.readFeatureTaskArtifacts(
          workflowId,
          setOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS),
        ) ?: return@read null
      val raw = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS.value(artifacts)
      val artifact = JsonCodec.anyToStringAnyMap(raw) ?: return@read null
      decodeValidationGateProgressFromArtifact(artifact)
    }

  fun persistOwnedValidationGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean =
    database.transaction { unitOfWork ->
      if (!requireCurrentWorker(admitted, unitOfWork.workflowStates, workflowId, clock)) {
        return@transaction false
      }
      persistValidationGateProgressInTransaction(unitOfWork, workflowId, progress)
      true
    }

  fun persistValidationGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  ) {
    database.transaction { unitOfWork ->
      persistValidationGateProgressInTransaction(unitOfWork, workflowId, progress)
    }
  }

  private fun persistValidationGateProgressInTransaction(
    unitOfWork: skillbill.ports.persistence.UnitOfWork,
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  ) {
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

  fun loadBuildGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress? =
    database.read { unitOfWork ->
      val artifacts =
        unitOfWork.workflowStates.readFeatureTaskArtifacts(
          workflowId,
          setOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS),
        ) ?: return@read null
      val raw = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS.value(artifacts)
      val artifact = JsonCodec.anyToStringAnyMap(raw) ?: return@read null
      decodeValidationGateProgressFromArtifact(artifact)
    }

  fun loadGoalContinuation(workflowId: String): FeatureTaskRuntimeGoalContinuationArtifact? =
    database.read { unitOfWork ->
      val artifacts =
        unitOfWork.workflowStates.readFeatureTaskArtifacts(
          workflowId,
          setOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION),
        ) ?: return@read null
      GoalSubtaskReviewArtifactDecoder.decodeContinuationOnly(artifacts)
    }

  override fun loadReadinessEvidence(workflowId: String): FeatureTaskRuntimeReadinessEvidence? =
    database.read { unitOfWork ->
      val artifacts =
        unitOfWork.workflowStates.readFeatureTaskArtifacts(
          workflowId,
          setOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_READINESS_EVIDENCE),
        ) ?: return@read null
      val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_READINESS_EVIDENCE
      val raw = family.value(artifacts)
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

  override fun loadNoChangePause(workflowId: String): FeatureTaskRuntimeNoChangePause? =
    database.read { unitOfWork ->
      val artifacts =
        unitOfWork.workflowStates.readFeatureTaskArtifacts(
          workflowId,
          setOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_NO_CHANGE_PAUSE),
        ) ?: return@read null
      decodeNoChangePauseFromArtifact(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_NO_CHANGE_PAUSE.value(artifacts),
      )
    }

  fun persistOwnedNoChangePause(
    workflowId: String,
    pause: FeatureTaskRuntimeNoChangePause,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean =
    database.transaction { unitOfWork ->
      if (!requireCurrentWorker(admitted, unitOfWork.workflowStates, workflowId, clock)) {
        return@transaction false
      }
      persistNoChangePauseInTransaction(unitOfWork, workflowId, pause)
      true
    }

  override fun persistNoChangePause(
    workflowId: String,
    pause: FeatureTaskRuntimeNoChangePause,
  ) {
    database.transaction { unitOfWork ->
      persistNoChangePauseInTransaction(unitOfWork, workflowId, pause)
    }
  }

  private fun persistNoChangePauseInTransaction(
    unitOfWork: skillbill.ports.persistence.UnitOfWork,
    workflowId: String,
    pause: FeatureTaskRuntimeNoChangePause,
  ) {
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

  fun persistOwnedBuildGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean =
    database.transaction { unitOfWork ->
      if (!requireCurrentWorker(admitted, unitOfWork.workflowStates, workflowId, clock)) {
        return@transaction false
      }
      persistBuildGateProgressInTransaction(unitOfWork, workflowId, progress)
      true
    }

  fun persistBuildGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  ) {
    database.transaction { unitOfWork ->
      persistBuildGateProgressInTransaction(unitOfWork, workflowId, progress)
    }
  }

  private fun persistBuildGateProgressInTransaction(
    unitOfWork: skillbill.ports.persistence.UnitOfWork,
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  ) {
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
