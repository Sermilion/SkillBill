package skillbill.engine.goalrunner.manifest

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testProjectStore
import skillbill.contracts.workflow.goal.GoalQualityExecutionPayloadKeys
import skillbill.engine.featuretask.slot.artifactValue
import skillbill.engine.featuretask.slot.testExecutionPlan
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
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

  private fun importedReadiness(subtask: DecompositionSubtask): Any? {
    val home = Files.createTempDirectory("goal-import-quality-readiness")
    val clock = Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC)
    val database = sqliteSessionFactoryForTests(home, environment = emptyMap(), clock = clock)
    val manifest =
      DecompositionManifest(
        issueKey = "APP-123",
        featureName = "filters",
        parentSpecPath = "specs/APP-123-filters/spec.md",
        baseBranch = "main",
        featureBranch = "feat/APP-123-filters",
        currentSubtaskIntent = CurrentSubtaskIntent(1, "start"),
        subtasks = listOf(subtask),
      )
    try {
      database.transaction { unit -> seedCompletedPlan(unit, manifest.issueKey) }
      val loader =
        WorkflowGoalRunnerManifestLoader(
          database,
          testDecompositionManifestValidator,
          TestDecompositionManifestStore,
          clock,
          Random(1),
          testProjectStore,
        )
      val imported = assertNotNull(loader.importFromManifestProjection(manifest))
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
  ) {
    val plan =
      WorkflowEngine().openRecord(WorkflowFamily.TASK_RUNTIME.definition, "plan-workflow", "ftr", "plan")
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
  }
}
