package skillbill.review.model

import skillbill.error.core.RuntimeFailureCode

enum class ReviewAttributionFailureCode : RuntimeFailureCode {
  MALFORMED_VOCABULARY,
}
