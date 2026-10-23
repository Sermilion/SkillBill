package skillbill.engine.goalrunner.intake

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.testProjectStore
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.engine.goalrunner.manifest.TestNoopGoalPlanningManifestStore
import skillbill.engine.goalrunner.plan.FeatureTaskRuntimePlanIntake
import skillbill.engine.goalrunner.plan.FeatureTaskRuntimePlanSeedRecorder
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.ports.projectstore.FixedProjectStorePort
import kotlin.io.path.createTempDirectory

fun testPlanIntake(): FeatureTaskRuntimePlanIntake =
  FeatureTaskRuntimePlanIntake(
    testGoalIntakePreparation(),
    FeatureTaskRuntimePlanSeedRecorder(FakeDatabaseSessionFactory(InMemoryWorkflowStates())),
    TestDecompositionManifestStore,
    TestRepositoryEnclosingRoot,
    FixedProjectStorePort(createTempDirectory("goal-intake-project-store")),
  )

fun testGoalIntakePreparation(): GoalIntakePreparation =
  GoalIntakePreparation(
    TestNoopGoalPlanningManifestStore,
    FeatureSpecPreparationWriter(
      testDecompositionManifestValidator,
      TestDecompositionManifestStore,
      testDecompositionManifestWriter,
    ),
    FeatureSpecPathResolverPort { input -> FeatureSpecPathResolveResult.NoMatch(input.issueKey, input.repoRoot) },
    TestDecompositionManifestStore,
    testProjectStore,
  )
