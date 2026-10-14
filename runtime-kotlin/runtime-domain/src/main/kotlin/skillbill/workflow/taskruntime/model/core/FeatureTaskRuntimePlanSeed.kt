package skillbill.workflow.taskruntime.model.core

import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader

enum class FeatureTaskRuntimePlanSpecOrigin(val wireValue: String) {
  SEEDED("seeded"),
  OPERATOR("operator"),
}

data class FeatureTaskRuntimePlanSeed(
  val intakeSha256: String?,
  val specOrigin: FeatureTaskRuntimePlanSpecOrigin,
  val specSha256: String,
) {
  init {
    require(specSha256.isNotBlank()) { "FeatureTaskRuntimePlanSeed.specSha256 must be non-blank." }
    require(intakeSha256 == null || intakeSha256.isNotBlank()) {
      "FeatureTaskRuntimePlanSeed.intakeSha256 must be non-blank when present."
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      INTAKE_SHA256 to intakeSha256,
      SPEC_ORIGIN to specOrigin.wireValue,
      SPEC_SHA256 to specSha256,
    )

  companion object {
    private const val INTAKE_SHA256 = "intake_sha256"
    private const val SPEC_ORIGIN = "spec_origin"
    private const val SPEC_SHA256 = "spec_sha256"

    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimePlanSeed {
      val reader = durableArtifactMapReader(raw)
      val origin =
        FeatureTaskRuntimePlanSpecOrigin.entries.firstOrNull { it.wireValue == reader.requiredString(SPEC_ORIGIN) }
          ?: throw invalidWorkflowStateSchemaError("Feature-task-runtime plan seed has an unknown spec_origin.")
      return FeatureTaskRuntimePlanSeed(
        intakeSha256 = reader.optionalString(INTAKE_SHA256),
        specOrigin = origin,
        specSha256 = reader.requiredString(SPEC_SHA256),
      )
    }
  }
}
