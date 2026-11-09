package skillbill.engine.featuretask.persist.acceptance

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.migration.AcceptanceCriterionMigrationFailureCode
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.isPreviousVersionAcceptanceCriterionAuditResult
import skillbill.workflow.taskruntime.artifact.isPreviousVersionAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

@Inject
public class AcceptanceCriterionRepairVersionRecovery(
  private val boundary: RuntimeOwnedPersistenceBoundary,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun requirePreparedVersions(worker: AcquiredFeatureTaskRuntimeWorker): Unit =
    boundary.requiredRead("serial_repair_version_probe", "preserving schema readiness") { work ->
      val artifacts = work.workflowStates.get(WorkflowFamily.TASK_RUNTIME, worker.workflowId)?.artifacts.orEmpty()
      val repair =
        FeatureTaskRuntimeWorkflowArtifactMap.from(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR.value(artifacts),
        )
      val audit =
        FeatureTaskRuntimeWorkflowArtifactMap.from(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_AUDIT_RESULT.value(artifacts),
        )
      val unmigrated =
        repair.isPreviousVersionAcceptanceCriterionRepairState() ||
          audit.isPreviousVersionAcceptanceCriterionAuditResult()
      if (unmigrated) {
        diagnostics.warning(
          "seam=serial_repair_version_probe workflow_id=${worker.workflowId} " +
            "value_used=retained_source action=prepare_database_before_worker",
        )
        throw SkillBillRuntimeException(
          AcceptanceCriterionMigrationFailureCode.ARTIFACT_MIGRATION_REFUSED,
          "Supported criterion history requires database readiness before worker admission. " +
            "Original artifacts are retained; release the worker and retry readiness with no active workers.",
        )
      }
    }
}
