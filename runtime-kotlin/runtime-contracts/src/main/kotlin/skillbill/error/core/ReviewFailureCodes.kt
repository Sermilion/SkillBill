package skillbill.error.core

enum class CursorReviewStreamFailureCode : RuntimeFailureCode {
  MALFORMED,
  FORBIDDEN_OPERATION,
  PROVIDER_FAILURE,
  TERMINATION,
  UNKNOWN,
}

enum class ReviewAttributionFailureCode : RuntimeFailureCode {
  MALFORMED_VOCABULARY,
}
