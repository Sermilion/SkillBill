package skillbill.engine.goalrunner.manifest

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testProjectStore
import skillbill.engine.featuretask.slot.artifactValue
import skillbill.engine.featuretask.slot.testExecutionPlan
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
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

class GoalPlanningTimeImportTest {
  @Test
  fun `goal import retains planning attempts without idle gaps or counting them again on resume`() {
    val home = Files.createTempDirectory("goal-planning-time-import")
    val start = Instant.parse("2026-10-25T10:00:00Z")
    val clock = Clock.fixed(start.plusSeconds(300), ZoneOffset.UTC)
    val database = sqliteSessionFactoryForTests(home, environment = emptyMap(), clock = clock)
    val manifest =
      DecompositionManifest(
        issueKey = "APP-123",
        featureName = "filters",
        parentSpecPath = "specs/APP-123-filters/spec.md",
        baseBranch = "main",
        featureBranch = "feat/APP-123-filters",
        currentSubtaskIntent = CurrentSubtaskIntent(1, "start"),
        subtasks = listOf(DecompositionSubtask(1, "Filters", "specs/APP-123-filters/subtask.md")),
      )
    try {
      database.transaction { unit -> seedPlanning(unit, start, manifest.issueKey) }
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
      assertEquals(60_000, imported.controlState.activeDurationMs)
      assertEquals(
        start,
        database.read {
          it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, imported.parentWorkflowId)?.startedAt
        },
      )
      database.transaction { unit ->
        unit.goalRunnerControls.persistControlState(
          imported.parentWorkflowId,
          imported.controlState.copy(activeDurationMs = 70_000),
        )
      }
      val resumed = assertNotNull(loader.importFromManifestProjection(manifest))
      assertEquals(imported.parentWorkflowId, resumed.parentWorkflowId)
      assertEquals(70_000, resumed.controlState.activeDurationMs)
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  private fun seedPlanning(
    unit: UnitOfWork,
    start: Instant,
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
    listOf("first" to 20_000L, "second" to 40_000L).forEachIndexed { index, (id, duration) ->
      val at = start.plusSeconds(index * 100L)
      val record =
        unit.standalonePhaseStatuses.register(
          StandalonePhaseStatusRegistration(
            "repository", "main", issueKey, plan.workflowId, id, "plan", "execution-$id",
            "active", "preplan", at, id, 1, at.plusSeconds(3600), launchScope = IdeStatusLaunchScope.GOAL,
          ),
        )
      assertEquals(
        StandalonePhaseStatusUpdateResult.ACCEPTED,
        unit.standalonePhaseStatuses.update(
          StandalonePhaseStatusUpdate(
            record.executionId, record.statusRevision, record.leaseOwner, record.leaseGeneration,
            if (index == 0) "blocked" else "terminal", "plan", null, at.plusMillis(duration),
            activeDurationMs = duration,
          ),
        ),
      )
    }
  }
}
