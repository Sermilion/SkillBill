package skillbill.application.workflow.persist

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.model.PersistOpenedWorkflowArgs
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.contracts.JsonCodec
import skillbill.contracts.issuekey.normalizeIssueKey
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.MissingFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.isInvalidWorkflowStateFailure
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateWriteResult
import skillbill.ports.workflow.toRecord
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowSummaryView

@Inject
class WorkflowRecordPersistence(
  private val validator: WorkflowSnapshotValidator,
  private val admissionReplay: WorkflowAdmissionReplay,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun open(args: PersistOpenedWorkflowArgs): WorkflowOpenResult =
    args.database.transaction { unitOfWork ->
      val engine = args.engine
      val family = args.family
      val workflowId = args.workflowId
      val stepId = args.stepId
      if (family == WorkflowFamily.TASK_RUNTIME && args.executionIdentity != null && args.executionPlan == null) {
        throw MissingFeatureTaskRuntimeExecutionPlanError()
      }
      if (args.executionIdentity != null) {
        admissionReplay.replay(args, unitOfWork, engine, family)?.let { return@transaction it }
      }
      val record =
        engine.openRecord(
          family.definition,
          workflowId,
          args.effectiveSessionId,
          stepId,
        )
      val planArtifacts =
        args.executionPlan?.let { descriptor ->
          mapOf(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(
              JsonCodec.parseValue(descriptor.encoded().toString(Charsets.UTF_8)),
            ),
          )
        }.orEmpty()
      val goalQualityReadiness = initialGoalQualityReadiness(args)
      val withExecutionPlan =
        if (planArtifacts.isEmpty() && args.initialArtifacts.isEmpty() && goalQualityReadiness.isEmpty()) {
          record
        } else {
          record.copy(
            artifacts =
              DurableWorkflowArtifacts.fromMap(
                record.artifacts + planArtifacts + args.initialArtifacts + goalQualityReadiness,
              ),
          )
        }
      validator.validate(withExecutionPlan, family.definition.workflowName)
      unitOfWork.workflowStates.saveRecord(
        family,
        withExecutionPlan.toRecord().copy(
          startedAt = null,
          issueKey = normalizeIssueKey(args.issueKey),
        ),
      )
      args.executionIdentity?.let(unitOfWork.workflowStates::saveFeatureTaskExecutionIdentity)
      val saved = unitOfWork.workflowStates.get(family, workflowId) ?: withExecutionPlan
      val currentStep =
        engine.snapshotView(family.definition, saved).steps
          .firstOrNull { it.stepId == stepId }
      val launchProjection =
        launchProjectionIfReady(
          engine,
          family.definition,
          engine.snapshotView(family.definition, saved),
          ProjectionLaunchRequest(
            stepId = stepId,
            producerIteration = currentStep?.attemptCount ?: 0,
            repositoryCheckpointIdentity = args.repositoryCheckpointIdentity,
          ),
        )
      WorkflowOpenResult.Ok(
        workflowId = saved.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        snapshot = engine.snapshotView(family.definition, saved),
        launchProjection = launchProjection,
      )
    }

  internal fun get(
    family: WorkflowFamily,
    workflowId: String,
    unitOfWork: UnitOfWork,
  ): WorkflowStateSnapshot? =
    unitOfWork.workflowStates.get(family, workflowId)?.also {
      validator.validate(it, family.definition.workflowName)
    }

  internal fun summaries(
    family: WorkflowFamily,
    rows: List<WorkflowStateSnapshot>,
    engine: WorkflowEngine,
  ): List<WorkflowSummaryView> =
    rows.mapNotNull { row ->
      try {
        validator.validate(row, family.definition.workflowName)
        engine.summaryView(family.definition, row)
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.isInvalidWorkflowStateFailure())
        diagnostics.warning(
          "seam=workflow_list value_expected=readable_workflow_row value_used=skipped " +
            "workflow_id=${row.workflowId} error=${error.message.orEmpty()}",
          error,
        )
        null
      }
    }

  internal fun saveOrdinary(
    family: WorkflowFamily,
    record: WorkflowStateSnapshot,
    unitOfWork: UnitOfWork,
  ): WorkflowStateWriteResult {
    validator.validate(record, family.definition.workflowName)
    return unitOfWork.workflowStates.saveOrdinary(family, record)
  }
}
