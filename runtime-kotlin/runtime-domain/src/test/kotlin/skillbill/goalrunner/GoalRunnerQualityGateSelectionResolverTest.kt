package skillbill.goalrunner

import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalRunnerQualityGateSelectionResolverTest {
  @Test
  fun `three-child goal resolves build build validate`() {
    val manifest = manifest(subtaskCount = 3)
    assertEquals(
      FeatureTaskRuntimeQualityGateSelection.BUILD,
      GoalRunnerQualityGateSelectionResolver.resolve(manifest, 1),
    )
    assertEquals(
      FeatureTaskRuntimeQualityGateSelection.BUILD,
      GoalRunnerQualityGateSelectionResolver.resolve(manifest, 2),
    )
    assertEquals(
      FeatureTaskRuntimeQualityGateSelection.VALIDATE,
      GoalRunnerQualityGateSelectionResolver.resolve(manifest, 3),
    )
  }

  @Test
  fun `single-subtask goal resolves validate`() {
    val manifest = manifest(subtaskCount = 1)
    assertEquals(
      FeatureTaskRuntimeQualityGateSelection.VALIDATE,
      GoalRunnerQualityGateSelectionResolver.resolve(manifest, 1),
    )
  }

  @Test
  fun `ordinal-last skipped promotes validate to previous last non-skipped`() {
    val manifest =
      manifest(subtaskCount = 3).copy(
        subtasks =
          manifest(subtaskCount = 3).subtasks.map { subtask ->
            if (subtask.id == 3) subtask.copy(status = "skipped") else subtask
          },
      )
    assertEquals(
      FeatureTaskRuntimeQualityGateSelection.BUILD,
      GoalRunnerQualityGateSelectionResolver.resolve(manifest, 1),
    )
    assertEquals(
      FeatureTaskRuntimeQualityGateSelection.VALIDATE,
      GoalRunnerQualityGateSelectionResolver.resolve(manifest, 2),
    )
  }

  private fun manifest(subtaskCount: Int): DecompositionManifest =
    DecompositionManifest(
      issueKey = "SKILL-204",
      featureName = "goal",
      parentSpecPath = ".feature-specs/SKILL-204-goal/spec.md",
      baseBranch = "main",
      featureBranch = "feat/SKILL-204-goal",
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
      subtasks =
        (1..subtaskCount).map { id ->
          DecompositionSubtask(
            id = id,
            name = "Subtask $id",
            specPath = ".feature-specs/SKILL-204-goal/spec_subtask_$id.md",
          )
        },
    )
}
