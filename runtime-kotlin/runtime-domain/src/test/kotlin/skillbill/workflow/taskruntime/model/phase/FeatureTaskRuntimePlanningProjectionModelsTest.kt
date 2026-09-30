
package skillbill.workflow.taskruntime.model.phase

import skillbill.workflow.decomposition.model.SpecSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimePlanningProjectionModelsTest {
  @Test
  fun `producedProjectionKindFor is null for every phase including implement`() {
    listOf(
      "preplan",
      "plan",
      "implement",
      "audit",
      "implement_fix",
      "review",
      "validate",
      "write_history",
      "commit_push",
      "pr",
    ).forEach { phaseId ->
      assertNull(
        FeatureTaskRuntimePlanningProjectionContract.producedProjectionKindFor(phaseId),
        "phase '$phaseId' must not name a bounded planning projection producer kind",
      )
    }
  }

  @Test
  fun `SHARED_REVIEW_EVIDENCE_ID remains the shared review evidence contract id`() {
    assertEquals(
      "feature_task_runtime.shared_review_evidence",
      FeatureTaskRuntimePlanningProjectionContract.SHARED_REVIEW_EVIDENCE_ID,
    )
  }

  @Test
  fun `a decompose package requires produced_outputs decomposition_package with mode decompose`() {
    assertTrue(
      featureTaskRuntimeIsDecompositionPackage(
        mapOf(
          "produced_outputs" to
            mapOf(
              "decomposition_package" to mapOf("mode" to "decompose", "subtasks" to listOf<Any>()),
            ),
        ),
      ),
    )
    assertFalse(
      featureTaskRuntimeIsDecompositionPackage(
        mapOf("produced_outputs" to mapOf("mode" to "decompose", "subtasks" to listOf<Any>())),
      ),
    )
    assertFalse(
      featureTaskRuntimeIsDecompositionPackage(mapOf("produced_outputs" to mapOf("mode" to "direct"))),
    )
    assertFalse(featureTaskRuntimeIsDecompositionPackage(mapOf("produced_outputs" to mapOf("steps" to listOf("x")))))
  }

  @Test
  fun `decompose plan outcome decodes only nested decomposition_package`() {
    assertNull(
      featureTaskRuntimeDecomposePlanOutcomeOrNull(
        mapOf(
          "produced_outputs" to
            mapOf(
              "value" to "prose plan with leftover decompose mode",
              "mode" to "decompose",
            ),
        ),
        SpecSource.LOCAL,
      ),
    )
    assertNull(
      featureTaskRuntimeDecomposePlanOutcomeOrNull(
        mapOf("produced_outputs" to mapOf("mode" to "decompose", "subtasks" to listOf<Any>())),
        SpecSource.LOCAL,
      ),
    )
    val outcome =
      featureTaskRuntimeDecomposePlanOutcomeOrNull(
        mapOf(
          "summary" to "Plan needs ordered subtasks.",
          "produced_outputs" to mapOf("decomposition_package" to nestedDecompositionPackage()),
        ),
        SpecSource.LOCAL,
      )
    assertNotNull(outcome)
    assertEquals("runtime decomposition", outcome.featureName)
  }

  @Test
  fun `a decompose plan outcome accepts a single-subtask spec bundle`() {
    val singleSubtaskPackage =
      nestedDecompositionPackage().let { pkg -> pkg + ("subtasks" to (pkg["subtasks"] as List<*>).take(1)) }

    val outcome =
      featureTaskRuntimeDecomposePlanOutcomeOrNull(
        mapOf("produced_outputs" to mapOf("decomposition_package" to singleSubtaskPackage)),
        SpecSource.LOCAL,
      )

    assertNotNull(outcome)
    assertEquals(listOf("first"), outcome.subtasks.map { subtask -> subtask.name })
  }

  private fun nestedDecompositionPackage(): Map<String, Any?> =
    mapOf(
      "mode" to "decompose",
      "reason" to "Plan needs ordered subtasks.",
      "feature_name" to "runtime decomposition",
      "parent_spec_overview" to "Split work into subtasks.",
      "validation_strategy" to "bill-code-check",
      "base_branch" to "main",
      "feature_branch" to "feat/decompose",
      "subtasks" to
        listOf(
          mapOf(
            "id" to 1,
            "name" to "first",
            "scope" to "First subtask.",
            "acceptance_criteria" to listOf("First."),
            "non_goals" to emptyList<String>(),
            "dependency_notes" to "First.",
            "validation_strategy" to "unit tests",
            "next_path" to "Next.",
            "depends_on" to emptyList<Int>(),
          ),
          mapOf(
            "id" to 2,
            "name" to "second",
            "scope" to "Second subtask.",
            "acceptance_criteria" to listOf("Second."),
            "non_goals" to emptyList<String>(),
            "dependency_notes" to "Second.",
            "validation_strategy" to "unit tests",
            "next_path" to "Done.",
            "depends_on" to listOf(1),
          ),
        ),
    )
}
