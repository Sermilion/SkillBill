package skillbill.error.workflow

import skillbill.error.core.RuntimeFailureCode

enum class WorkflowAgentAssignmentsFailureCode : RuntimeFailureCode {
  INVALID_RECORD,
  UNSUPPORTED_VERSION,
  UNSAFE_MIGRATION,
}
