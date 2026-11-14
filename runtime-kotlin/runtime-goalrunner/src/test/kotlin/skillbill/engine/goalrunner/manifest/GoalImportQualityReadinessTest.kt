package skillbill.engine.goalrunner.manifest

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testProjectStore
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.contracts.workflow.goal.GoalQualityExecutionPayloadKeys
import skillbill.engine.featuretask.lifecycle.execution.workflowAgentAssignmentStoreForTests
import skillbill.engine.featuretask.slot.artifactValue
import skillbill.engine.featuretask.slot.testExecutionPlan
import skillbill.engine.goalrunner.persist.testGoalPlanningCheckoutOwnership
import skillbill.engine.goalrunner.persist.testWorkflowGoalRunnerQualityExecutionStore
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.goalworktree.InPlaceGoalWorktreeResources
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GoalImportQualityReadinessTest {
  @Test
  fun `importing a goal right after its completed plan leaves final quality admissible`() {
    assertEquals(
      GoalQualityReadiness.CLEAN_UNSTARTED.wireValue,
      importedReadiness(DecompositionSubtask(1, "Filters", "specs/APP-123-filters/subtask.md")),
    )
  }

  @Test
  fun `importing a planned goal whose manifest records subtask progress keeps quality history ambiguous`() {
    assertEquals(
      GoalQualityReadiness.HISTORICAL_AMBIGUITY.wireValue,
      importedReadiness(
        DecompositionSubtask(
          1,
          "Filters",
          "specs/APP-123-filters/subtask.md",
          status = "complete",
          commitSha = "abc123",
        ),
      ),
    )
  }

  @Test
  fun `importing a goal into another checkout of the planned project spec leaves final quality admissible`() {
    assertEquals(
      GoalQualityReadiness.CLEAN_UNSTARTED.wireValue,
      importedReadiness(
        DecompositionSubtask(1, "Filters", "specs/APP-123-filters/subtask.md"),
        plan = PlanIdentity(OLD_CHECKOUT, "specs/APP-123-filters/spec.md"),
        importIdentity = NEW_CHECKOUT,
      ),
    )
  }

  @Test
  fun `importing a goal whose only completed plan governs another spec keeps quality history ambiguous`() {
    assertEquals(
      GoalQualityReadiness.HISTORICAL_AMBIGUITY.wireValue,
      importedReadiness(
        DecompositionSubtask(1, "Filters", "specs/APP-123-filters/subtask.md"),
        plan = PlanIdentity(OLD_CHECKOUT, "specs/APP-123-other/spec.md"),
        importIdentity = NEW_CHECKOUT,
      ),
    )
  }

  @Test
  fun `a goal whose own plan completes after import leaves final quality admissible`() {
    assertEquals(
      GoalQualityReadiness.CLEAN_UNSTARTED,
      readinessAfterPlanCompletesPostImport(DecompositionSubtask(1, "Filters", "specs/APP-123-filters/subtask.md")),
    )
  }

  @Test
  fun `a plan completing after import does not clear ambiguity once a subtask has started`() {
    assertEquals(
      GoalQualityReadiness.HISTORICAL_AMBIGUITY,
      readinessAfterPlanCompletesPostImport(
        DecompositionSubtask(1, "Filters", "specs/APP-123-filters/subtask.md", status = "complete", commitSha = "abc"),
      ),
    )
  }

  @Test
  fun `a plan for another spec completing after import does not clear ambiguity`() {
    assertEquals(
      GoalQualityReadiness.HISTORICAL_AMBIGUITY,
      readinessAfterPlanCompletesPostImport(
        DecompositionSubtask(1, "Filters", "specs/APP-123-filters/subtask.md"),
        plan = PlanIdentity(NEW_CHECKOUT, "specs/APP-123-other/spec.md"),
      ),
    )
  }

  @Test
  fun `a checked-in manifest whose plan completes after import keeps quality history ambiguous`() {
    assertEquals(
      GoalQualityReadiness.HISTORICAL_AMBIGUITY,
      readinessAfterPlanCompletesPostImport(
        DecompositionSubtask(1, "Filters", ".feature-specs/APP-123-filters/subtask.md"),
        plan = PlanIdentity(NEW_CHECKOUT, ".feature-specs/APP-123-filters/spec.md"),
        parentSpecPath = ".feature-specs/APP-123-filters/spec.md",
      ),
    )
  }

  private fun readinessAfterPlanCompletesPostImport(
    subtask: DecompositionSubtask,
    plan: PlanIdentity = PlanIdentity(NEW_CHECKOUT, "specs/APP-123-filters/spec.md"),
    parentSpecPath: String = "specs/APP-123-filters/spec.md",
  ): GoalQualityReadiness? {
    val home = Files.createTempDirectory("goal-readiness-after-plan")
    val clock = Clock.fixed(Instant.parse("2026-11-02T15:31:41Z"), ZoneOffset.UTC)
    val database = sqliteSessionFactoryForTests(home, environment = emptyMap(), clock = clock)
    try {
      val imported =
        assertNotNull(
          manifestLoader(database, clock).importFromManifestProjection(
            manifest(subtask, parentSpecPath),
            listOf(NEW_CHECKOUT),
          ),
        )
      val store = testWorkflowGoalRunnerQualityExecutionStore(database, testWorkflowSnapshotValidator)
      assertEquals(
        GoalQualityReadiness.HISTORICAL_AMBIGUITY,
        store.goalQualityExecution(imported.parentWorkflowId)?.readiness,
      )
      database.transaction { unit -> seedCompletedPlan(unit, "APP-123", plan) }
      store.settleGoalQualityReadinessFromCompletedPlan(imported.parentWorkflowId)
      return store.goalQualityExecution(imported.parentWorkflowId)?.readiness
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  private fun manifest(
    subtask: DecompositionSubtask,
    parentSpecPath: String = "specs/APP-123-filters/spec.md",
  ) = DecompositionManifest(
    issueKey = "APP-123",
    featureName = "filters",
    parentSpecPath = parentSpecPath,
    baseBranch = "main",
    featureBranch = "feat/APP-123-filters",
    currentSubtaskIntent = CurrentSubtaskIntent(1, "start"),
    subtasks = listOf(subtask),
  )

  private fun manifestLoader(
    database: DatabaseSessionFactory,
    clock: Clock,
  ) = WorkflowGoalRunnerManifestLoader(
    database,
    testDecompositionManifestValidator,
    TestDecompositionManifestStore,
    clock,
    Random(1),
    testProjectStore,
    parentProjection = GoalParentProjectionWriter(testDecompositionManifestValidator),
    worktrees = InPlaceGoalWorktreeResources,
    ownership = testGoalPlanningCheckoutOwnership(database),
    workflowAgentAssignmentStore = workflowAgentAssignmentStoreForTests(database),
  )

  private data class PlanIdentity(
    val repositoryIdentity: String,
    val governedSpecPath: String,
  )

  private fun importedReadiness(
    subtask: DecompositionSubtask,
    plan: PlanIdentity? = null,
    importIdentity: String? = null,
  ): Any? {
    val home = Files.createTempDirectory("goal-import-quality-readiness")
    val clock = Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC)
    val database = sqliteSessionFactoryForTests(home, environment = emptyMap(), clock = clock)
    val manifest = manifest(subtask)
    try {
      database.transaction { unit -> seedCompletedPlan(unit, manifest.issueKey, plan) }
      val loader = manifestLoader(database, clock)
      val imported = assertNotNull(loader.importFromManifestProjection(manifest, listOf(importIdentity)))
      val parent =
        assertNotNull(
          database.read { it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, imported.parentWorkflowId) },
        )
      val quality = assertNotNull(DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.value(parent.artifacts))
      return (quality as Map<*, *>)[GoalQualityExecutionPayloadKeys.READINESS]
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  private fun seedCompletedPlan(
    unit: UnitOfWork,
    issueKey: String,
    identity: PlanIdentity?,
  ) {
    val plan =
      WorkflowEngine()
        .openRecord(WorkflowFamily.TASK_RUNTIME.definition, "plan-workflow", "ftr", "plan")
        .copy(
          workflowStatus = WorkflowStatus.COMPLETED,
          mode = FeatureTaskWorkflowMode.RUNTIME,
          artifacts =
            DurableWorkflowArtifacts.fromMap(
              mapOf(
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(
                  testExecutionPlan(SkeletonDefinition.PLAN).artifactValue,
                ),
              ),
            ),
        )
    unit.workflowStates.saveRecord(WorkflowFamily.TASK_RUNTIME, plan.toRecord().copy(issueKey = issueKey))
    identity?.let {
      unit.workflowStates.saveFeatureTaskExecutionIdentity(
        FeatureTaskExecutionIdentity(
          workflowId = plan.workflowId,
          normalizedIssueKey = issueKey,
          repositoryIdentity = it.repositoryIdentity,
          governedSpecPath = it.governedSpecPath,
          mode = FeatureTaskWorkflowMode.RUNTIME,
        ),
      )
    }
  }

  private companion object {
    const val OLD_CHECKOUT = "repo-root-realpath-v1:/home/me/projects/app"
    const val NEW_CHECKOUT = "repo-root-realpath-v1:/mnt/work/projects/app"
  }
}
