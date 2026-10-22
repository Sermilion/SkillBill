package skillbill.engine.featuretask.runner

import skillbill.engine.diagnostics.block.testBlockIncidentCapture
import skillbill.application.testHarnessClock
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionRepairLegacyAdoption
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionRepairPersistence
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionRepairPhaseRecorder
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseStateRecorder
import skillbill.engine.featuretask.testing.featureTaskRuntimeWireArtifactValidatorForTest
import skillbill.infrastructure.contracts.workflow.featuretask.ContractFeatureTaskRuntimePhaseOutputMigration
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
  val validator = featureTaskRuntimeWireArtifactValidatorForTest()
  val execution = ExecutionPlanAdmissionFixture()
  val source =
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
    }
  val persistence =
    FeatureTaskRuntimeWorkflowPersistence(
      database,
      NoopWorkflowSnapshotValidator,
      validator,
    )
  return AcceptanceCriterionRepairPhaseRecorder(
    AcceptanceCriterionRepairPersistence(
      boundary,
      persistence,
      FeatureTaskRuntimePhaseStateRecorder(
        database,
        persistence,
        boundary,
        validator,
        harnessPhaseSettlement(),
        testHarnessClock,
        testBlockIncidentCapture(database, testHarnessClock),
      ),
      validator,
      object : AcceptanceCriterionRepairProjection {
        override fun write(request: AcceptanceCriterionRepairProjectionRequest) = Unit
      },
      diagnostics,
      testHarnessClock,
      AcceptanceCriterionRepairLegacyAdoption(
        execution.codec,
        execution.compatibility,
        ContractFeatureTaskRuntimePhaseOutputMigration(),
        gitOperations,
        source,
      ),
    ),
    gitOperations,
    persistence,
  )
}
