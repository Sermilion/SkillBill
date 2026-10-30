package skillbill.engine.diagnostics.block

import skillbill.application.testDecompositionManifestValidator
import skillbill.engine.featuretask.incident.BlockIncidentCapture
import skillbill.engine.featuretask.incident.BlockIncidentEntryReconciler
import skillbill.engine.featuretask.incident.recovery.BlockIncidentRecoveryActions
import skillbill.engine.featuretask.incident.recovery.BlockIncidentRecoveryInterruptedReconciliation
import skillbill.engine.featuretask.incident.recovery.BlockIncidentRecoveryService
import skillbill.engine.featuretask.lifecycle.remediation.FeatureTaskRuntimeStandaloneIncidentRecovery
import skillbill.engine.goalrunner.persist.WorkflowGoalRunnerBlockWrites
import skillbill.engine.work.WorkIncidentProjectionBuilder
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.ports.blockincident.BlockIncidentTelemetryPort
import skillbill.ports.blockincident.InMemoryBlockIncidentSpool
import skillbill.ports.blockincident.model.BlockIncidentTelemetryAvailability
import skillbill.ports.blockincident.model.BlockIncidentTelemetryState
import skillbill.ports.blockincident.model.ProductReportRecord
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.DaemonThreadPort
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentClassifier
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
      testDecompositionManifestValidator,
      BlockIncidentRecoveryActions(
        WorkflowGoalRunnerBlockWrites(clock),
        FeatureTaskRuntimeStandaloneIncidentRecovery(clock),
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

fun testBlockIncidentCapture(
  database: DatabaseSessionFactory,
  clock: Clock,
  diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
): BlockIncidentCapture = BlockIncidentCapture(database, clock, InMemoryBlockIncidentSpool(), diagnostics)

fun testBlockIncidentEntryReconciler(
  database: DatabaseSessionFactory,
  clock: Clock,
  diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
): BlockIncidentEntryReconciler {
  val spool = InMemoryBlockIncidentSpool()
  return BlockIncidentEntryReconciler(
    database,
    clock,
    diagnostics,
    spool,
    BlockIncidentInvestigationWorker(
      database,
      clock,
      BlockIncidentInvestigator { claimed, degradations ->
        BlockIncidentInvestigationResult(
          BlockIncidentDiagnosis.PARTIAL,
          BlockIncidentClassifier.classify(claimed.occurrence, emptyList(), degradations, clock.instant()),
        )
      },
      diagnostics,
      IdentifierGeneratorPort { "test-investigator" },
      NoopFeatureTaskRuntimeWorkerSupervisor,
      DaemonThreadPort { _, _ -> },
      reportScheduler = null,
      reportDelivery = null,
      telemetry = null,
    ),
    BlockIncidentCapture(database, clock, spool, diagnostics),
    BlockIncidentRecoveryInterruptedReconciliation(
      database,
      clock,
      NoopFeatureTaskRuntimeWorkerSupervisor,
      diagnostics,
      reportDelivery = null,
      telemetry = null,
    ),
    productReportDelivery = null,
  )
}
