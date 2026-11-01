package skillbill.workflow.taskruntime.model.persistence

import skillbill.error.shellcontent.invalidWorkflowStateSchemaError

data class LegacyGoalContinuationMarker(
  val issueKey: String,
  val subtaskId: Int,
)

class UndecodedTypedArtifact(
  val jsonText: String,
  val failure: RuntimeException,
  val legacyMarker: LegacyGoalContinuationMarker? = null,
) {
  override fun equals(other: Any?): Boolean =
    other is UndecodedTypedArtifact && jsonText == other.jsonText && legacyMarker == other.legacyMarker

  override fun hashCode(): Int = 31 * jsonText.hashCode() + (legacyMarker?.hashCode() ?: 0)

  override fun toString(): String = "UndecodedTypedArtifact(jsonText=$jsonText)"
}

fun isTypedArtifactRecord(value: Any?): Boolean =
  value is UndecodedTypedArtifact ||
    value is FeatureTaskRuntimeGoalContinuationArtifact ||
    value is FeatureTaskRuntimeGoalContinuationFieldAdoption ||
    value is FeatureTaskRuntimeGoalPlanningImport

internal inline fun <reified T : Any> Map<String, Any?>.typedArtifactRecordOrNull(key: String): T? {
  if (!containsKey(key)) return null
  return when (val value = this[key]) {
    is T -> value
    is UndecodedTypedArtifact -> throw value.failure
    else ->
      throw invalidWorkflowStateSchemaError(
        "Workflow artifact '$key' must be a typed record, found ${value?.let { it::class.simpleName } ?: "null"}.",
      )
  }
}
