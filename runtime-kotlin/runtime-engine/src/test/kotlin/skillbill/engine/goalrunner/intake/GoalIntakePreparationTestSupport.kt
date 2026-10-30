package skillbill.engine.goalrunner.intake

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testProjectStore
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.engine.featuretask.slot.intake.FeatureTaskRuntimePlanIntake
import skillbill.engine.featuretask.slot.intake.FeatureTaskRuntimePlanSeedRecorder
import skillbill.engine.featuretask.slot.intake.LegacySpecIntake
import skillbill.engine.goalrunner.manifest.TestNoopGoalPlanningManifestStore
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.workflow.WorkflowStateRepository
import kotlin.io.path.createTempDirectory

fun testPlanIntake(): FeatureTaskRuntimePlanIntake =
  FeatureTaskRuntimePlanIntake(
    testGoalIntakePreparation(),
    FeatureTaskRuntimePlanSeedRecorder(FakeDatabaseSessionFactory(InMemoryWorkflowStates())),
    TestDecompositionManifestStore,
    TestRepositoryEnclosingRoot,
    FixedProjectStorePort(createTempDirectory("goal-intake-project-store")),
  )

fun testGoalIntakePreparation(
  workflowStates: WorkflowStateRepository = InMemoryWorkflowStates(),
  diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
  projectStore: ProjectStorePort = testProjectStore,
): GoalIntakePreparation {
  val noMatchResolver =
    FeatureSpecPathResolverPort { input -> FeatureSpecPathResolveResult.NoMatch(input.issueKey, input.repoRoot) }
  return GoalIntakePreparation(
    TestNoopGoalPlanningManifestStore,
    FeatureSpecPreparationWriter(
      testDecompositionManifestValidator,
      TestDecompositionManifestStore,
      DecompositionManifestWriter(projectStore),
      projectStore,
    ),
    noMatchResolver,
    TestDecompositionManifestStore,
    projectStore,
    LegacySpecIntake(
      FakeDatabaseSessionFactory(workflowStates),
      TestRepositoryEnclosingRoot,
      projectStore,
      TestDecompositionManifestStore,
      diagnostics,
      noMatchResolver,
    ),
  )
}
