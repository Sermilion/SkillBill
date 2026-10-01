package skillbill.workflow.model.goalobservability

import skillbill.error.shellcontent.InvalidGoalObservabilityEventSchemaError

internal fun invalidGoalObservabilityEvent(
  sourceLabel: String,
  fieldPath: String,
  reason: String,
  cause: Throwable? = null,
): InvalidGoalObservabilityEventSchemaError =
  InvalidGoalObservabilityEventSchemaError(
    sourceLabel = sourceLabel,
    fieldPath = fieldPath,
    reason = reason,
    cause = cause,
  )
