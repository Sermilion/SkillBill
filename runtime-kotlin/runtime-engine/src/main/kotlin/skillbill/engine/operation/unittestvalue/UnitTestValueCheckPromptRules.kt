package skillbill.engine.operation.unittestvalue

import skillbill.engine.directive.directiveResource

internal object UnitTestValueCheckPromptRules {
  private const val REVIEW_DIRECTIVE_RESOURCE: String = "/skillbill/engine/operation/unittestvalue/review-directive.md"

  val RULES: String by lazy { directiveResource(REVIEW_DIRECTIVE_RESOURCE).trimEnd() }
}
