package skillbill.infrastructure.workflow.validation

import skillbill.error.core.RuntimeFailureCode

internal enum class ValidationGateProcessFailureCode : RuntimeFailureCode {
  TIMED_OUT,
  LAUNCH_FAILED,
}
