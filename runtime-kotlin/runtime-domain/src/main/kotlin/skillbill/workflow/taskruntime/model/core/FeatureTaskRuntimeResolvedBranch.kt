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
    val reason = branchViolation(branch, reviewBaseSha) ?: pathsViolation(
      baselineUntrackedPaths, baselineOwnedPaths, workflowOwnedPaths,
      boundaryHistoryPaths, boundaryHistoryRoots,
    )
    require(reason == null) { reason.orEmpty() }
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
    private fun branchViolation(
      branch: String,
      reviewBaseSha: String?,
    ): String? =
      when {
        branch.isBlank() -> "FeatureTaskRuntimeResolvedBranch.branch must be non-blank."
        reviewBaseSha != null && !REVIEW_BASE_SHA.matches(reviewBaseSha) ->
          "FeatureTaskRuntimeResolvedBranch.reviewBaseSha must be a 40- or 64-character lowercase commit SHA."
        else -> null
      }

    private fun pathsViolation(
      baselineUntrackedPaths: List<String>,
      baselineOwnedPaths: List<String>,
      workflowOwnedPaths: List<String>,
      boundaryHistoryPaths: List<String>,
      boundaryHistoryRoots: List<String>,
    ): String? =
      when {
        baselineUntrackedPaths.any(String::isBlank) ->
          "FeatureTaskRuntimeResolvedBranch.baselineUntrackedPaths must not contain blanks."
        baselineOwnedPaths.any(String::isBlank) ->
          "FeatureTaskRuntimeResolvedBranch.baselineOwnedPaths must not contain blanks."
        workflowOwnedPaths.any(String::isBlank) ->
          "FeatureTaskRuntimeResolvedBranch.workflowOwnedPaths must not contain blanks."
        boundaryHistoryPaths.any(String::isBlank) ->
          "FeatureTaskRuntimeResolvedBranch.boundaryHistoryPaths must not contain blanks."
        boundaryHistoryRoots.any(String::isBlank) ->
          "FeatureTaskRuntimeResolvedBranch.boundaryHistoryRoots must not contain blanks."
        else -> null
      }

    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeResolvedBranch {
      val reader = durableArtifactMapReader(raw)
      val branch = reader.requiredString(DecompositionPlanningPayloadKeys.BRANCH)
      val baseBranch = reader.optionalString(DecompositionPlanningPayloadKeys.BASE_BRANCH)
      val created = reader.optionalBoolean("created") ?: false
      val reviewBaseSha = reader.optionalString("review_base_sha")
      val baselineUntrackedPaths = reader.optionalStringList("baseline_untracked_paths")
      val baselineOwnedPaths = reader.optionalStringList("baseline_owned_paths")
      val workflowOwnedPaths = reader.optionalStringList("workflow_owned_paths")
      val boundaryHistoryPaths = reader.optionalStringList("boundary_history_paths")
      val boundaryHistoryRoots = reader.optionalStringList("boundary_history_roots")
      val reason = branchViolation(branch, reviewBaseSha) ?: pathsViolation(
        baselineUntrackedPaths, baselineOwnedPaths, workflowOwnedPaths, boundaryHistoryPaths, boundaryHistoryRoots,
      )
      if (reason != null) {
        throw invalidWorkflowStateSchemaError("Feature-task-runtime resolved-branch artifact is invalid.")
      }
      return FeatureTaskRuntimeResolvedBranch(
        branch, baseBranch, created, reviewBaseSha, baselineUntrackedPaths, baselineOwnedPaths,
        workflowOwnedPaths, boundaryHistoryPaths, boundaryHistoryRoots,
      )
    }
  }
}

private val REVIEW_BASE_SHA = Regex("^[0-9a-f]{40}(?:[0-9a-f]{24})?$")
