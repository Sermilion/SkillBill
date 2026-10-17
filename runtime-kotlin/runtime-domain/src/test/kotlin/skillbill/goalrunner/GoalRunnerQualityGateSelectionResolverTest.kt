package skillbill.goalrunner

import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalRunnerQualityGateSelectionResolverTest {
  @Test
  fun `three-child goal resolves validate for every subtask`() {
    val manifest = manifest(subtaskCount = 3)
    assertEquals(
      FeatureTaskRuntimeQualityGateSelection.VALIDATE,
      GoalRunnerQualityGateSelectionResolver.resolve(manifest, 1),
    )
    assertEquals(
      FeatureTaskRuntimeQualityGateSelection.VALIDATE,
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
  fun `skipped last child still resolves validate for remaining subtasks`() {
    val manifest =
      manifest(subtaskCount = 3).copy(
        subtasks =
          manifest(subtaskCount = 3).subtasks.map { subtask ->
            if (subtask.id == 3) subtask.copy(status = "skipped") else subtask
          },
      )
    assertEquals(
      FeatureTaskRuntimeQualityGateSelection.VALIDATE,
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
