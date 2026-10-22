package skillbill.engine.goalrunner.reset

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.testHarnessClock
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerManifestStore
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanOptions
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodePlanBundleFromArtifact
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundleOrigin
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundleSubtask
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class WorkflowGoalRunnerScopedReplanPersistenceTest {
  @Test
  fun `scoped replan discards one completed plan unit without allowing ordinary terminal rewrites`() {
    val home = Files.createTempDirectory("scoped-replan-completed-plan")
    try {
      val database = phaseRunDatabase(home, testHarnessClock)
      val execution = ExecutionPlanAdmissionFixture(SkeletonDefinition.PLAN, database = database)
      val planId = "wftr-completed-plan"
      val parentId = "wftr-parent"
      val identity = execution.identity(planId)
      val manifest = manifest(subtaskCount = 2).copy(issueKey = identity.normalizedIssueKey)
      val bundle =
        FeatureTaskRuntimePlanBundle(
          FeatureTaskRuntimePlanBundleOrigin.EXISTING,
          "parent-spec",
          "manifest",
          manifest.subtasks.map { plannedSubtask(it.id, it.specPath) },
        )
      database.transaction { unit ->
        execution.seed(unit.workflowStates, parentId)
        execution.seed(unit.workflowStates, planId)
        val plan = assertNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, planId))
        unit.workflowStates.save(
          WorkflowFamily.TASK_RUNTIME,
          WorkflowEngine().updateRecord(
            WorkflowFamily.TASK_RUNTIME.definition,
            plan,
            WorkflowUpdateInput(
              workflowStatus = WorkflowStatus.COMPLETED,
              currentStepId = "plan_subtasks",
              stepUpdates = null,
              artifactsPatch =
                WorkflowArtifactPatch.from(
                  mapOf(
                    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.entry(
                      bundle.asWorkflowArtifactEntry(),
                    ),
                  ),
                ),
              sessionId = plan.sessionId,
              terminalInstant = testHarnessClock.instant(),
            ),
          ),
        )
      }
      val before = database.read { assertNotNull(it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, planId)) }
      val store = testWorkflowGoalRunnerManifestStore(database, TestDecompositionManifestStore, testHarnessClock)

      val result =
        store.saveScopedReplan(
          GoalRunnerManifestState(parentId, "unused", manifest, repoRoot = home),
          1,
          GoalRunnerScopedReplanOptions(identity.repositoryIdentity),
        )

      assertEquals(listOf(1, 2), result.plannedSubtaskIdsBefore)
      assertEquals(listOf(2), result.plannedSubtaskIdsAfter)
      database.read { unit ->
        val after = assertNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, planId))
        val retained =
          decodePlanBundleFromArtifact(
            assertNotNull(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.value(after.artifacts)),
          )
        assertEquals(bundle.withoutPlannedUnits(setOf(1)), retained)
        assertEquals(before.copy(artifacts = after.artifacts), after)
      }
      assertFailsWith<SkillBillRuntimeException> {
        database.transaction { unit -> unit.workflowStates.save(WorkflowFamily.TASK_RUNTIME, before) }
      }
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  private fun plannedSubtask(
    id: Int,
    specPath: String,
  ): FeatureTaskRuntimePlanBundleSubtask =
    FeatureTaskRuntimePlanBundleSubtask(id, specPath, "scope-$id", "details-$id", "Plan $id")
}
