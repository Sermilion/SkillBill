package skillbill.error.approval

import skillbill.error.core.RuntimeFailureCode

enum class ApprovalFailureCode : RuntimeFailureCode {
  INVALID_APPROVAL_CONTRACT,
  APPROVAL_VERSION_MISMATCH,
  APPROVAL_STORE_FAILURE,
}
