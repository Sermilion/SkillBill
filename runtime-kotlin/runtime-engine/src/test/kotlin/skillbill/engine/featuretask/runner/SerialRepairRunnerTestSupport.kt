package skillbill.engine.featuretask.runner

import skillbill.application.testHarnessClock
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionRepairPersistence
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionRepairPhaseRecorder
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseStateRecorder
import skillbill.infrastructure.contracts.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.AcceptanceCriterionRepairProjection
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.AcceptanceCriterionRepairProjectionRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import java.nio.file.Path

internal fun harnessAcceptanceRecorder(
  database: DatabaseSessionFactory,
  gitOperations: WorkflowGitOperations,
  acceptanceCriteria: List<String>,
  diagnostics: RuntimeDiagnostics,
): AcceptanceCriterionRepairPhaseRecorder {
  val boundary = RuntimeOwnedPersistenceBoundary(database, diagnostics)
  val persistence =
    FeatureTaskRuntimeWorkflowPersistence(
      database,
      NoopWorkflowSnapshotValidator,
    )
  return AcceptanceCriterionRepairPhaseRecorder(
    AcceptanceCriterionRepairPersistence(
      boundary,
      persistence,
      FeatureTaskRuntimePhaseStateRecorder(
        database,
        persistence,
        boundary,
        FeatureTaskRuntimeWireArtifactValidator(),
        testHarnessClock,
      ),
      object : AcceptanceCriterionRepairProjection {
        override fun write(request: AcceptanceCriterionRepairProjectionRequest) = Unit
      },
      diagnostics,
      testHarnessClock,
      object : FeatureTaskRuntimeRunInvariantsSource {
        override fun read(specPath: Path) =
          FeatureTaskRuntimeRunInvariantsRead.Read(
            FeatureTaskRuntimeRunInvariants(
              specPath.toString(),
              acceptanceCriteria = acceptanceCriteria,
              mandatesAndOverrides = emptyList(),
            ),
            "a".repeat(64),
          )
      },
    ),
    gitOperations,
  )
}
