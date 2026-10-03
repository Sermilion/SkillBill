package skillbill.workflow.model.goalobservability

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.invalidGoalObservabilityEventSchemaError

internal fun invalidGoalObservabilityEvent(
  sourceLabel: String,
  fieldPath: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  invalidGoalObservabilityEventSchemaError(
    sourceLabel = sourceLabel,
    fieldPath = fieldPath,
    reason = reason,
    cause = cause,
  )
