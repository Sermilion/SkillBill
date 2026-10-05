package skillbill.workflow.taskruntime.model.handoff.task

import skillbill.contracts.SharedPayloadKeys
import skillbill.workflow.taskruntime.model.core.unrecognizedHandoffWireValue

internal val REPOSITORY_CHECKPOINT_FIELD: String
  get() = FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT.wireValue

enum class FeatureTaskRuntimeCompactReferenceKind(val wireValue: String, val runtimeResolvable: Boolean) {
  PRIVATE_EVIDENCE_ARTIFACT("private_evidence_artifact", true),
  REPOSITORY_PATH("repository_path", true),
  REPOSITORY_CHECKPOINT("repository_checkpoint", false),
  ACCEPTANCE_CRITERION_REF("acceptance_criterion_ref", false),
  REPAIR_ITEM_ID("repair_item_id", false),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeCompactReferenceKind =
      entries.firstOrNull { it.wireValue == value }
        ?: unrecognizedHandoffWireValue("compact reference kind", value)
  }
}

sealed interface FeatureTaskRuntimeHandoffProjectionValue {
  val utf8ByteSize: Int
  val itemCount: Int

  data class Text(val text: String) : FeatureTaskRuntimeHandoffProjectionValue {
    override val utf8ByteSize: Int get() = text.toByteArray(Charsets.UTF_8).size
    override val itemCount: Int get() = 1
  }

  data class TextList(val items: List<String>) : FeatureTaskRuntimeHandoffProjectionValue {
    override val utf8ByteSize: Int get() = items.sumOf { it.toByteArray(Charsets.UTF_8).size }
    override val itemCount: Int get() = items.size
  }

  data class CompactReference(
    val kind: FeatureTaskRuntimeCompactReferenceKind,
    val value: String,
  ) : FeatureTaskRuntimeHandoffProjectionValue {
    override val utf8ByteSize: Int get() = value.toByteArray(Charsets.UTF_8).size
    override val itemCount: Int get() = 1
  }
}

val FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES: Set<String> =
  setOf(
    "upstream_outputs_by_phase_id",
    "raw_payload",
    "payload",
    "raw_prompt",
    SharedPayloadKeys.PROMPT,
    "transcript",
    "tool_output",
    "log",
    "logs",
    "source_body",
    "diff_body",
    "telemetry",
  )

internal val PROJECTION_NAME_PATTERN = Regex("^[a-z][a-z0-9_]*$")

data class FeatureTaskRuntimeHandoffProjectionField(
  val name: String,
  val value: FeatureTaskRuntimeHandoffProjectionValue,
) {
  init {
    val reason = violation(name)
    require(reason == null) { reason.orEmpty() }
  }

  companion object {
    internal fun violation(name: String): String? =
      when {
        !PROJECTION_NAME_PATTERN.matches(name) ->
          "FeatureTaskRuntimeHandoffProjectionField.name must match ${PROJECTION_NAME_PATTERN.pattern}, was '$name'."
        name in FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES ->
          "FeatureTaskRuntimeHandoffProjectionField.name '$name' is a forbidden raw-context field."
        else -> null
      }
  }
}
