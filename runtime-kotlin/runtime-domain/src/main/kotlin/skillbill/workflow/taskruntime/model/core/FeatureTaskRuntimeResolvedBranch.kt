package skillbill.workflow.taskruntime.model.core

import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader

data class FeatureTaskRuntimeResolvedBranch(
  val branch: String,
  val baseBranch: String? = null,
  val created: Boolean = false,
  val reviewBaseSha: String? = null,
  val baselineUntrackedPaths: List<String> = emptyList(),
  val baselineOwnedPaths: List<String> = emptyList(),
  val workflowOwnedPaths: List<String> = emptyList(),
  val boundaryHistoryPaths: List<String> = emptyList(),
  val boundaryHistoryRoots: List<String> = emptyList(),
) {
  init {
    require(branch.isNotBlank()) { "FeatureTaskRuntimeResolvedBranch.branch must be non-blank." }
    require(reviewBaseSha == null || REVIEW_BASE_SHA.matches(reviewBaseSha)) {
      "FeatureTaskRuntimeResolvedBranch.reviewBaseSha must be a 40- or 64-character lowercase commit SHA."
    }
    require(baselineUntrackedPaths.all(String::isNotBlank)) {
      "FeatureTaskRuntimeResolvedBranch.baselineUntrackedPaths must not contain blanks."
    }
    require(baselineOwnedPaths.all(String::isNotBlank)) {
      "FeatureTaskRuntimeResolvedBranch.baselineOwnedPaths must not contain blanks."
    }
    require(workflowOwnedPaths.all(String::isNotBlank)) {
      "FeatureTaskRuntimeResolvedBranch.workflowOwnedPaths must not contain blanks."
    }
    require(boundaryHistoryPaths.all(String::isNotBlank)) {
      "FeatureTaskRuntimeResolvedBranch.boundaryHistoryPaths must not contain blanks."
    }
    require(boundaryHistoryRoots.all(String::isNotBlank)) {
      "FeatureTaskRuntimeResolvedBranch.boundaryHistoryRoots must not contain blanks."
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      DecompositionPlanningPayloadKeys.BRANCH to branch,
      "created" to created,
    ).apply {
      baseBranch?.let { put(DecompositionPlanningPayloadKeys.BASE_BRANCH, it) }
      reviewBaseSha?.let { put("review_base_sha", it) }
      if (baselineUntrackedPaths.isNotEmpty()) put("baseline_untracked_paths", baselineUntrackedPaths)
      put("baseline_owned_paths", baselineOwnedPaths)
      put("workflow_owned_paths", workflowOwnedPaths)
      put("boundary_history_paths", boundaryHistoryPaths)
      put("boundary_history_roots", boundaryHistoryRoots)
    }

  companion object {
    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeResolvedBranch {
      val reader = durableArtifactMapReader(raw)
      return try {
        FeatureTaskRuntimeResolvedBranch(
          branch = reader.requiredString(DecompositionPlanningPayloadKeys.BRANCH),
          baseBranch = reader.optionalString(DecompositionPlanningPayloadKeys.BASE_BRANCH),
          created = reader.optionalBoolean("created") ?: false,
          reviewBaseSha = reader.optionalString("review_base_sha"),
          baselineUntrackedPaths = reader.optionalStringList("baseline_untracked_paths"),
          baselineOwnedPaths = reader.optionalStringList("baseline_owned_paths"),
          workflowOwnedPaths = reader.optionalStringList("workflow_owned_paths"),
          boundaryHistoryPaths = reader.optionalStringList("boundary_history_paths"),
          boundaryHistoryRoots = reader.optionalStringList("boundary_history_roots"),
        )
      } catch (error: IllegalArgumentException) {
        throw invalidWorkflowStateSchemaError(
          "Feature-task-runtime resolved-branch artifact is invalid.",
          error,
        )
      }
    }
  }
}

private val REVIEW_BASE_SHA = Regex("^[0-9a-f]{40}(?:[0-9a-f]{24})?$")
