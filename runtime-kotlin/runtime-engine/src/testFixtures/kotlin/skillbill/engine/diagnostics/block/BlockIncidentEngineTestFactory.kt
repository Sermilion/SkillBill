package skillbill.engine.diagnostics.block

import skillbill.engine.featuretask.incident.BlockIncidentCapture
import skillbill.engine.featuretask.incident.BlockIncidentEntryReconciler
import skillbill.engine.featuretask.incident.BlockIncidentFeatureTaskCorrelation
import skillbill.engine.featuretask.incident.recovery.BlockIncidentRecoveryInterruptedReconciliation
import skillbill.ports.blockincident.BlockIncidentRepository
import skillbill.ports.blockincident.InMemoryBlockIncidentSpool
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.DaemonThreadPort
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentClassifier
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import java.time.Clock

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

fun observeBlockIncidentForTest(
  incidents: BlockIncidentRepository,
  clock: Clock,
  observation: BlockIncidentObservation,
) = BlockIncidentService.observeBlock(incidents, clock, observation)

fun featureTaskPhaseAliasForTest(
  workflowId: String,
  phaseId: String,
  invocationId: String,
  attempt: Int,
) = BlockIncidentAliases.featureTaskPhase(workflowId, phaseId, invocationId, attempt)

fun persistAcceptedBlockIdentityForTest(
  states: WorkflowStateRepository,
  observation: BlockIncidentObservation,
) = BlockIncidentFeatureTaskCorrelation.persistAcceptedIdentity(states, observation)
