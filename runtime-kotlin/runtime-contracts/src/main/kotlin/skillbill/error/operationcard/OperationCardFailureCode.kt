package skillbill.error.operationcard

import skillbill.error.core.RuntimeFailureCode

enum class OperationCardFailureCode : RuntimeFailureCode {
  INVALID_OPERATION_CARD_CONTRACT,
  OPERATION_CARD_VERSION_MISMATCH,
  OPERATION_CARD_STORE_FAILURE,
}
