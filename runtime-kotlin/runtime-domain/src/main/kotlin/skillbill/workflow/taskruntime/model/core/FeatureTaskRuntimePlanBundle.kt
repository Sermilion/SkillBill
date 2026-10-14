package skillbill.workflow.taskruntime.model.core

import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader

enum class FeatureTaskRuntimePlanBundleOrigin(val wireValue: String) {
  AUTHORED("authored"),
  EXISTING("existing"),
}

data class FeatureTaskRuntimePlanBundleSubtask(
  val id: Int,
  val specPath: String,
  val scopeSha256: String,
  val detailsSha256: String? = null,
  val planText: String? = null,
) {
  init {
    require(id > 0) { "FeatureTaskRuntimePlanBundleSubtask.id must be positive." }
    require(specPath.isNotBlank()) { "FeatureTaskRuntimePlanBundleSubtask.specPath must be non-blank." }
    require(scopeSha256.isNotBlank()) { "FeatureTaskRuntimePlanBundleSubtask.scopeSha256 must be non-blank." }
    require(detailsSha256 == null || detailsSha256.isNotBlank()) {
      "FeatureTaskRuntimePlanBundleSubtask.detailsSha256 must be non-blank when present."
    }
  }

  val planned: Boolean get() = detailsSha256 != null

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      ID to id,
      SPEC_PATH to specPath,
      SCOPE_SHA256 to scopeSha256,
      DETAILS_SHA256 to detailsSha256,
      PLAN_TEXT to planText,
    )

  companion object {
    private const val ID = "id"
    private const val SPEC_PATH = "spec_path"
    private const val SCOPE_SHA256 = "scope_sha256"
    private const val DETAILS_SHA256 = "details_sha256"
    private const val PLAN_TEXT = "plan_text"

    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimePlanBundleSubtask {
      val reader = durableArtifactMapReader(raw)
      return FeatureTaskRuntimePlanBundleSubtask(
        id = reader.requiredInt(ID),
        specPath = reader.requiredString(SPEC_PATH),
        scopeSha256 = reader.requiredString(SCOPE_SHA256),
        detailsSha256 = reader.optionalString(DETAILS_SHA256),
        planText = reader.optionalString(PLAN_TEXT),
      )
    }
  }
}

data class FeatureTaskRuntimePlanBundle(
  val origin: FeatureTaskRuntimePlanBundleOrigin,
  val parentSpecSha256: String,
  val manifestSha256: String,
  val subtasks: List<FeatureTaskRuntimePlanBundleSubtask>,
) {
  init {
    require(parentSpecSha256.isNotBlank()) { "FeatureTaskRuntimePlanBundle.parentSpecSha256 must be non-blank." }
    require(manifestSha256.isNotBlank()) { "FeatureTaskRuntimePlanBundle.manifestSha256 must be non-blank." }
    require(subtasks.map(FeatureTaskRuntimePlanBundleSubtask::id).distinct().size == subtasks.size) {
      "FeatureTaskRuntimePlanBundle subtask ids must be unique."
    }
  }

  fun subtask(id: Int): FeatureTaskRuntimePlanBundleSubtask? = subtasks.firstOrNull { it.id == id }

  fun withSubtask(updated: FeatureTaskRuntimePlanBundleSubtask): FeatureTaskRuntimePlanBundle =
    copy(subtasks = subtasks.map { if (it.id == updated.id) updated else it })

  fun withoutPlannedUnits(ids: Set<Int>): FeatureTaskRuntimePlanBundle =
    copy(
      subtasks =
        subtasks.map { if (it.id in ids) it.copy(detailsSha256 = null, planText = null) else it },
    )

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      ORIGIN to origin.wireValue,
      PARENT_SPEC_SHA256 to parentSpecSha256,
      MANIFEST_SHA256 to manifestSha256,
      SUBTASKS to subtasks.map(FeatureTaskRuntimePlanBundleSubtask::toArtifactMap),
    )

  companion object {
    private const val ORIGIN = "origin"
    private const val PARENT_SPEC_SHA256 = "parent_spec_sha256"
    private const val MANIFEST_SHA256 = "manifest_sha256"
    private const val SUBTASKS = "subtasks"

    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimePlanBundle {
      val reader = durableArtifactMapReader(raw)
      val origin =
        FeatureTaskRuntimePlanBundleOrigin.entries.firstOrNull { it.wireValue == reader.requiredString(ORIGIN) }
          ?: throw invalidWorkflowStateSchemaError("Feature-task-runtime plan bundle has an unknown origin.")
      val subtasks =
        reader.requiredList(SUBTASKS).map { entry ->
          @Suppress("UNCHECKED_CAST")
          val map =
            (entry as? Map<String, Any?>)
              ?: throw invalidWorkflowStateSchemaError("Feature-task-runtime plan bundle subtask must be an object.")
          FeatureTaskRuntimePlanBundleSubtask.fromArtifactMap(map)
        }
      return FeatureTaskRuntimePlanBundle(
        origin = origin,
        parentSpecSha256 = reader.requiredString(PARENT_SPEC_SHA256),
        manifestSha256 = reader.requiredString(MANIFEST_SHA256),
        subtasks = subtasks,
      )
    }
  }
}
