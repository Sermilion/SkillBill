package skillbill.engine.operation.unittestvalue

import skillbill.engine.directive.directiveResource

internal object UnitTestValueCheckPromptRules {
  const val REVIEW_STEP: String = "operation.unit-test-value-check.review"

  private const val REVIEW_DIRECTIVE_RESOURCE: String = "/skillbill/engine/operation/unittestvalue/review-directive.md"

  val RULES: String by lazy { directiveResource(REVIEW_DIRECTIVE_RESOURCE).trimEnd() }

  fun reviewDirective(
    scope: String,
    testPaths: List<String>,
  ): String =
    listOf(
      "# Operation: unit-test-value-check (review)\n\n" +
        "Review the unit tests below with the value test that follows and report only. This step is " +
        "read-only: do not edit, create, delete, stage, or commit any file. The runtime compares the " +
        "repository before and after this step. The runtime resolved the scope; do not widen it. Read the " +
        "production code these tests exercise as needed.\n\n" +
        "Scope reviewed: $scope\n\n" +
        "Unit tests in scope:\n" + testPaths.joinToString("\n") { path -> "- $path" },
      RULES,
    ).joinToString("\n\n")
}
