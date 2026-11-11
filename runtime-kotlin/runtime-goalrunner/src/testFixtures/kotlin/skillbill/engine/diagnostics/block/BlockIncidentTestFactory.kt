package skillbill.engine.diagnostics.block

import skillbill.application.testDecompositionManifestValidator
import skillbill.engine.featuretask.lifecycle.remediation.FeatureTaskRuntimeStandaloneIncidentRecovery
import skillbill.engine.goalrunner.manifest.GoalParentProjectionWriter
import skillbill.engine.goalrunner.manifest.GoalRunnerBlockRecoveryAdapter
import skillbill.engine.goalrunner.persist.WorkflowGoalRunnerBlockWrites
import skillbill.engine.work.incident.WorkIncidentProjectionBuilder
import skillbill.engine.work.recovery.BlockIncidentRecoveryActions
import skillbill.engine.work.recovery.BlockIncidentRecoveryService
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.ports.blockincident.BlockIncidentTelemetryPort
import skillbill.ports.blockincident.model.BlockIncidentTelemetryAvailability
import skillbill.ports.blockincident.model.BlockIncidentTelemetryState
import skillbill.ports.blockincident.model.ProductReportRecord
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import java.time.Clock

fun testWorkIncidentProjectionBuilder(
  database: DatabaseSessionFactory,
  clock: Clock,
): WorkIncidentProjectionBuilder =
  WorkIncidentProjectionBuilder(
    BlockIncidentRecoveryService(
      database,
      clock,
      FeatureTaskRuntimeExecutionPlanSchemaValidator(),
      NoopFeatureTaskRuntimeWorkerSupervisor,
      NoopRuntimeDiagnostics,
      IdentifierGeneratorPort { "test-recovery" },
      BlockIncidentRecoveryActions(
        GoalRunnerBlockRecoveryAdapter(
          GoalParentProjectionWriter(testDecompositionManifestValidator),
          WorkflowGoalRunnerBlockWrites(clock, NoopRuntimeDiagnostics),
        ),
        FeatureTaskRuntimeStandaloneIncidentRecovery(clock),
      ),
      GoalRunnerBlockRecoveryAdapter(
        GoalParentProjectionWriter(testDecompositionManifestValidator),
        WorkflowGoalRunnerBlockWrites(clock, NoopRuntimeDiagnostics),
      ),
      telemetry = null,
      reportDelivery = null,
    ),
    DisabledIncidentTelemetry,
    NoopRuntimeDiagnostics,
  )

private object DisabledIncidentTelemetry : BlockIncidentTelemetryPort {
  override fun diagnosed(
    incident: BlockIncidentOccurrence,
    status: BlockIncidentDiagnosis,
    diagnosis: BlockIncidentDiagnosisRecord?,
  ) = Unit

  override fun recovery(
    incident: BlockIncidentOccurrence,
    attempt: BlockIncidentRecoveryAttempt,
  ) = Unit

  override fun reported(
    incident: BlockIncidentOccurrence,
    report: ProductReportRecord,
  ) = Unit

  override fun availability(incidentToken: String) =
    BlockIncidentTelemetryAvailability(BlockIncidentTelemetryState.DISABLED, null, incidentToken, null, null)
}
