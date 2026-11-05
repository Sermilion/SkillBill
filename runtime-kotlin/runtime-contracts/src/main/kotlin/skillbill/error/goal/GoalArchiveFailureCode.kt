package skillbill.error.goal

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException

enum class GoalArchiveFailureCode : RuntimeFailureCode {
  INVALID_SCHEMA,
  MISSING_SCHEMA_RESOURCE,
  PATH_TRAVERSAL,
  UNREADABLE_ZIP,
}

fun invalidGoalArchiveSchemaError(
  sourceLabel: String,
  fieldPath: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    GoalArchiveFailureCode.INVALID_SCHEMA,
    "Goal archive '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation at " +
      "'${fieldPath.ifBlank { "<root>" }}': $reason",
    cause,
  )

fun missingGoalArchiveSchemaResourceError(
  resource: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    GoalArchiveFailureCode.MISSING_SCHEMA_RESOURCE,
    "Goal archive schema resource is missing from the classpath at '${resource.ifBlank { "<unknown>" }}'.",
    cause,
  )

fun goalArchivePathTraversalError(
  entryPath: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    GoalArchiveFailureCode.PATH_TRAVERSAL,
    "Goal archive entry '$entryPath' escapes the allowed archive layout.",
    cause,
  )

fun unreadableGoalArchiveZipError(
  path: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    GoalArchiveFailureCode.UNREADABLE_ZIP,
    "Goal archive zip at '${path.ifBlank { "<unknown>" }}' is unreadable: $reason",
    cause,
  )
