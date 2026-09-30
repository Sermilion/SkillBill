package skillbill.engine.featuretask.slot.codereview

import skillbill.engine.directive.directiveResource

internal object CodeReviewDirectives {
  private const val REVIEW_DIRECTIVE_PATH = "/skillbill/engine/featuretask/slot/codereview/review-directive.md"
  private const val INLINE_REVIEW_DIRECTIVE_PATH =
    "/skillbill/engine/featuretask/slot/codereview/inline-review-directive.md"

  val review: String by lazy { directiveResource(REVIEW_DIRECTIVE_PATH) }

  val inlineReview: String by lazy { directiveResource(INLINE_REVIEW_DIRECTIVE_PATH) }
}
