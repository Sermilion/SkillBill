package skillbill.application

import skillbill.application.decomposition.decompositionPlanningResult
import skillbill.application.decomposition.decompositionPlanningSubtask
import skillbill.application.decomposition.model.DecompositionPlanningResultOptions
import skillbill.application.decomposition.model.DecompositionPlanningSubtaskOptions
import skillbill.application.decomposition.parentSpecPath
import skillbill.contracts.decomposition.DecompositionPlanningResult
import skillbill.contracts.decomposition.DecompositionPlanningStackBranchWire
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import java.nio.file.Path

fun decompositionPlanningPlan(parentSpecPath: Path): DecompositionPlanningResult =
  decompositionPlanningResult(
    parentSpecPath = parentSpecPath.toString(),
    subtasks =
      listOf(
        decompositionPlanningSubtask(
          id = 1,
          name = "Foundation",
          specPath = parentSpecPath.parent.resolve("spec_subtask_1_foundation.md").toString(),
          options = DecompositionPlanningSubtaskOptions(scope = "Create contract foundation"),
        ),
        decompositionPlanningSubtask(
          id = 2,
          name = "Runtime",
          specPath = parentSpecPath.parent.resolve("spec_subtask_2_runtime.md").toString(),
          options =
            DecompositionPlanningSubtaskOptions(
              dependsOn = listOf(1),
              scope = "Wire runtime writer",
            ),
        ),
      ),
    options = DecompositionPlanningResultOptions(recommendedFirstSubtaskId = 1),
  )

fun stackedDecompositionPlanningPlan(
  parentSpecPath: Path = Path.of("${LegacyProjectStatePaths.FEATURE_SPECS_PREFIX}SKILL-51-decomposition/spec.md"),
): DecompositionPlanningResult =
  decompositionPlanningResult(
    parentSpecPath = parentSpecPath.toString(),
    options =
      DecompositionPlanningResultOptions(
        recommendedFirstSubtaskId = 1,
        executionModelWire = "stacked_branches",
        stackBranches =
          listOf(
            DecompositionPlanningStackBranchWire(1, "feature/SKILL-51-01-foundation", "main"),
            DecompositionPlanningStackBranchWire(2, "feature/SKILL-51-02-runtime", "feature/SKILL-51-01-foundation"),
          ),
      ),
    subtasks =
      listOf(
        decompositionPlanningSubtask(
          id = 1,
          name = "Foundation",
          specPath = parentSpecPath.parent.resolve("spec_subtask_1_foundation.md").toString(),
        ),
        decompositionPlanningSubtask(
          id = 2,
          name = "Runtime",
          specPath = parentSpecPath.parent.resolve("spec_subtask_2_runtime.md").toString(),
          options = DecompositionPlanningSubtaskOptions(dependsOn = listOf(1)),
        ),
      ),
  )
