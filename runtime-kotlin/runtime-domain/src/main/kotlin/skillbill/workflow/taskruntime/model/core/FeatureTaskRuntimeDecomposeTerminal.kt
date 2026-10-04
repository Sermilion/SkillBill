package skillbill.workflow.taskruntime.model.core

import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader

private const val FEATURE_TASK_RUNTIME_DECOMPOSE_GUIDANCE: String =
  "Work the first subtask first, then continue through the ordered spec_subtask_*.md files."

data class FeatureTaskRuntimeDecomposeTerminal(
  val reason: String,
  val parentSpecPath: String,
  val decompositionManifestPath: String,
  val subtaskSpecPaths: List<String>,
) {
  init {
    require(reason.isNotBlank()) { "FeatureTaskRuntimeDecomposeTerminal.reason must be non-blank." }
    require(parentSpecPath.isNotBlank()) { "FeatureTaskRuntimeDecomposeTerminal.parentSpecPath must be non-blank." }
    require(decompositionManifestPath.isNotBlank()) {
      "FeatureTaskRuntimeDecomposeTerminal.decompositionManifestPath must be non-blank."
    }
    require(subtaskSpecPaths.isNotEmpty()) {
      "FeatureTaskRuntimeDecomposeTerminal.subtaskSpecPaths must not be empty."
    }
  }

  val subtaskCount: Int get() = subtaskSpecPaths.size

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      "reason" to reason,
      DecompositionPlanningPayloadKeys.PARENT_SPEC_PATH to parentSpecPath,
      "decomposition_manifest_path" to decompositionManifestPath,
      "subtask_spec_paths" to subtaskSpecPaths,
      "subtask_count" to subtaskCount,
      "guidance" to FEATURE_TASK_RUNTIME_DECOMPOSE_GUIDANCE,
    )

  companion object {
    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeDecomposeTerminal {
      val reader = durableArtifactMapReader(raw)
      return FeatureTaskRuntimeDecomposeTerminal(
        reason = reader.requiredString("reason"),
        parentSpecPath = reader.requiredString(DecompositionPlanningPayloadKeys.PARENT_SPEC_PATH),
        decompositionManifestPath = reader.requiredString("decomposition_manifest_path"),
        subtaskSpecPaths = reader.requiredStringList("subtask_spec_paths"),
      )
    }
  }
}
