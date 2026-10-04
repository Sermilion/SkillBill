package skillbill.workflow.taskruntime.model.core

import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.error.shellcontent.invalidFeatureTaskRuntimePhaseHandoffSchema

const val MAX_REPOSITORY_FINGERPRINT_LENGTH: Int = 256

internal const val MAX_ACCEPTANCE_CRITERION_ORDINAL: Int = 999

const val FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE: String =
  "restart the active run or use the documented out-of-band migration procedure"

internal fun unrecognizedHandoffWireValue(
  field: String,
  value: String,
): Nothing =
  throw invalidFeatureTaskRuntimePhaseHandoffSchema(
    sourceLabel = "<wire>",
    reason = "Unrecognized feature-task-runtime handoff $field wire value '$value'.",
  )

enum class FeatureTaskRuntimeDiagnosticFailureClass(val wireValue: String) {
  CONFLICT("conflict"),
  PERMISSION("permission"),
  CORRUPT("corrupt"),
  SCHEMA("schema"),
  PERSISTENCE("persistence"),
  ;

  companion object {
    fun fromWire(raw: String): FeatureTaskRuntimeDiagnosticFailureClass =
      entries.firstOrNull { it.wireValue == raw }
        ?: throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime diagnostic failure class '$raw' is not a declared class.",
        )
  }
}
