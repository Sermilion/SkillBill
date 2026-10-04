package skillbill.infrastructure.contracts.workflow.decomposition

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.invalidDecompositionManifestSchema
import skillbill.workflow.decomposition.model.DecompositionManifestValidationFailureCode
import java.math.BigDecimal
import java.math.BigInteger

internal object DecompositionManifestCoherenceValidator {
  fun validate(
    manifest: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val stackBranches =
      (manifest[DecompositionPlanningPayloadKeys.STACK_BRANCHES] as? List<*>)
        .orEmpty()
        .mapNotNull { it as? Map<*, *> }
    val subtasks =
      (manifest[DecompositionPlanningPayloadKeys.SUBTASKS] as? List<*>)
        .orEmpty()
        .mapNotNull { it as? Map<*, *> }
    val subtaskIds = validateSubtasks(subtasks, sourceLabel)
    validateExecutionModel(manifest, subtasks, subtaskIds, stackBranches, sourceLabel)
    validateCurrentIntent(manifest, subtaskIds, sourceLabel)
  }

  private fun validateSubtasks(
    subtasks: List<Map<*, *>>,
    sourceLabel: String,
  ): Set<Int> {
    val subtaskIds = mutableSetOf<Int>()
    val specPaths = mutableSetOf<String>()
    subtasks.forEachIndexed { index, subtask ->
      val id = subtask[DecompositionPlanningPayloadKeys.ID].asExactInt(sourceLabel, "subtasks[$index].id")
      if (!subtaskIds.add(id)) {
        coherenceFailure(sourceLabel, "subtasks[$index].id", "Duplicate subtask id '$id'.")
      }
      val specPath = subtask[DecompositionPlanningPayloadKeys.SPEC_PATH]?.toString().orEmpty()
      if (!specPaths.add(specPath)) {
        coherenceFailure(sourceLabel, "subtasks[$index].spec_path", "Duplicate subtask spec_path '$specPath'.")
      }
      validateDependencies(subtask, subtaskIds, id, index, sourceLabel)
    }
    return subtaskIds
  }

  private fun validateDependencies(
    subtask: Map<*, *>,
    subtaskIds: Set<Int>,
    id: Int,
    index: Int,
    sourceLabel: String,
  ) {
    val dependencies =
      (subtask[DecompositionPlanningPayloadKeys.DEPENDENCIES] as? List<*>)
        .orEmpty()
        .mapNotNull { it as? Map<*, *> }
    dependencies.forEachIndexed { depIndex, dependency ->
      val path = "subtasks[$index].dependencies[$depIndex].subtask_id"
      val dependencyId = dependency[SharedPayloadKeys.SUBTASK_ID].asExactInt(sourceLabel, path)
      if (dependencyId !in subtaskIds || dependencyId == id) {
        throw coherenceError(
          sourceLabel,
          path,
          "Dependency '$dependencyId' must reference an earlier declared subtask.",
        )
      }
    }
  }

  private fun validateExecutionModel(
    manifest: Map<String, Any?>,
    subtasks: List<Map<*, *>>,
    subtaskIds: Set<Int>,
    stackBranches: List<Map<*, *>>,
    sourceLabel: String,
  ) {
    when (manifest[DecompositionPlanningPayloadKeys.EXECUTION_MODEL]?.toString().orEmpty()) {
      "same_branch_commit_per_subtask" -> validateSameBranch(manifest, stackBranches, sourceLabel)
      "stacked_branches" -> validateStackedBranches(manifest, subtasks, subtaskIds, stackBranches, sourceLabel)
    }
  }

  private fun validateSameBranch(
    manifest: Map<String, Any?>,
    stackBranches: List<Map<*, *>>,
    sourceLabel: String,
  ) {
    if (manifest[DecompositionManifestPayloadKeys.FEATURE_BRANCH]?.toString().orEmpty().isBlank()) {
      throw coherenceError(
        sourceLabel,
        DecompositionManifestPayloadKeys.FEATURE_BRANCH,
        "same_branch_commit_per_subtask manifests must declare feature_branch.",
      )
    }
    if (stackBranches.isNotEmpty()) {
      throw coherenceError(
        sourceLabel,
        DecompositionPlanningPayloadKeys.STACK_BRANCHES,
        "same_branch_commit_per_subtask manifests must not declare stack branches.",
      )
    }
  }

  private fun validateStackedBranches(
    manifest: Map<String, Any?>,
    subtasks: List<Map<*, *>>,
    subtaskIds: Set<Int>,
    stackBranches: List<Map<*, *>>,
    sourceLabel: String,
  ) {
    if (manifest[DecompositionManifestPayloadKeys.FEATURE_BRANCH] != null) {
      throw coherenceError(
        sourceLabel,
        DecompositionManifestPayloadKeys.FEATURE_BRANCH,
        "stacked_branches manifests must set feature_branch to null.",
      )
    }
    val expectedStackIds =
      subtasks.mapIndexed { index, subtask ->
        subtask[DecompositionPlanningPayloadKeys.ID].asExactInt(sourceLabel, "subtasks[$index].id")
      }
    val actualStackIds =
      stackBranches.mapIndexed { index, branch ->
        branch[SharedPayloadKeys.SUBTASK_ID].asExactInt(sourceLabel, "stack_branches[$index].subtask_id")
      }
    if (actualStackIds != expectedStackIds || actualStackIds.toSet() != subtaskIds) {
      throw coherenceError(
        sourceLabel,
        DecompositionPlanningPayloadKeys.STACK_BRANCHES,
        "stacked_branches manifests must declare exactly one branch per subtask in subtask order.",
      )
    }
  }

  private fun validateCurrentIntent(
    manifest: Map<String, Any?>,
    subtaskIds: Set<Int>,
    sourceLabel: String,
  ) {
    val intent = manifest[DecompositionManifestPayloadKeys.CURRENT_SUBTASK_INTENT] as? Map<*, *> ?: return
    val intentId =
      intent[SharedPayloadKeys.SUBTASK_ID]
        .asExactInt(sourceLabel, "current_subtask_intent.subtask_id")
    val intentAction = intent[DecompositionManifestPayloadKeys.ACTION]?.toString().orEmpty()
    val isTerminalAction = intentAction == "none" || intentAction == "complete"
    if (isTerminalAction && intentId != 0) {
      throw coherenceError(
        sourceLabel,
        "current_subtask_intent.subtask_id",
        "Intent action $intentAction must use subtask_id 0.",
      )
    }
    if (!isTerminalAction && intentId !in subtaskIds) {
      throw coherenceError(
        sourceLabel,
        "current_subtask_intent.subtask_id",
        "Current subtask intent must reference a declared subtask.",
      )
    }
  }

  private fun coherenceError(
    sourceLabel: String,
    fieldPath: String,
    reason: String,
  ): SkillBillRuntimeException =
    invalidDecompositionManifestSchema(
      sourceLabel = sourceLabel,
      reason = "$fieldPath: $reason",
      code = DecompositionManifestValidationFailureCode.COHERENCE_INVALID,
    )

  private fun coherenceFailure(
    sourceLabel: String,
    fieldPath: String,
    reason: String,
  ): Nothing = throw coherenceError(sourceLabel, fieldPath, reason)

  private fun Any?.asExactInt(
    sourceLabel: String,
    fieldPath: String,
  ): Int =
    when (this) {
      is Byte -> toInt()
      is Short -> toInt()
      is Int -> this
      is Long ->
        try {
          Math.toIntExact(this)
        } catch (_: ArithmeticException) {
          null
        }
      is BigInteger ->
        try {
          intValueExact()
        } catch (_: ArithmeticException) {
          null
        }
      is BigDecimal ->
        try {
          toBigIntegerExact().intValueExact()
        } catch (_: ArithmeticException) {
          null
        }
      else -> null
    } ?: coherenceFailure(sourceLabel, fieldPath, "Value must be an exact Kotlin Int.")
}
