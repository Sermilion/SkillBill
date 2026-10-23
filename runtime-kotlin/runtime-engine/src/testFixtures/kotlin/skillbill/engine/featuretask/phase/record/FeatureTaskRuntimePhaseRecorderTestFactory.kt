package skillbill.engine.featuretask.phase.record

import skillbill.engine.diagnostics.block.testBlockIncidentCapture
import skillbill.engine.featuretask.phase.core.FeatureTaskPhaseSettlementService
import skillbill.engine.featuretask.phase.core.InMemoryFeatureTaskPhaseSettlementRepository
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.workflow.WorkflowSnapshotValidator
import java.time.Clock

fun featureTaskRuntimePhaseRecorder(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  handoffEnvelopeValidator: FeatureTaskRuntimeWireArtifactValidator,
  handoffFoundationValidator: FeatureTaskRuntimeWireArtifactValidator,
  clock: Clock,
  diagnostics: RuntimeDiagnostics,
): FeatureTaskRuntimePhaseRecorder =
  FeatureTaskRuntimePhaseRecorder(
    database = database,
    workflowSnapshotValidator = workflowSnapshotValidator,
    wireArtifactValidator = handoffEnvelopeValidator,
    rejectedOutputDiagnosticMetadataValidator = { },
    producerOutputEvidenceValidator = { },
    diagnostics = diagnostics,
    phaseSettlementService =
      FeatureTaskPhaseSettlementService(
        InMemoryFeatureTaskPhaseSettlementRepository(),
        clock,
        database,
        testBlockIncidentCapture(database, clock),
      ),
    clock = clock,
  )
