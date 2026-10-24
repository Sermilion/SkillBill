package skillbill.engine.goalrunner

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testProjectStore
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.preflight.GoalPreflightService
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics

fun goalPreflightServiceForTests(
  database: DatabaseSessionFactory,
  manifestState: GoalRunnerManifestState? = null,
): GoalPreflightService =
  GoalPreflightService(
    continuationLookup =
      FeatureTaskContinuationLookupService(
        database,
        testWorkflowSnapshotValidator,
        ExecutionPlanAdmissionFixture().compatibility,
        NoopRuntimeDiagnostics,
      ),
    manifestStore = TestManifestStore(manifestState),
    agentAddonSelectionPort = TestAgentAddonSelectionPort,
    externalAgentAddonSourceConfigPort = EmptyExternalAgentAddonSourceConfigPort,
    manifestFileStore = TestDecompositionManifestStore,
    manifestValidator = testDecompositionManifestValidator,
    repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
    projectStore = testProjectStore,
  )
