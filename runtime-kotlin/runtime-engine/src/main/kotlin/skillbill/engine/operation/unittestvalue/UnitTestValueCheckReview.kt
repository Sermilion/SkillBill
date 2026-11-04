package skillbill.engine.operation.unittestvalue

import skillbill.engine.featuretask.persist.UnitTestPathClassifier

public object UnitTestValueCheckReview {
  public const val REVIEW_STEP: String = "operation.unit-test-value-check.review"

  public fun directive(
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
      UnitTestValueCheckPromptRules.RULES,
    ).joinToString("\n\n")

  public fun testPaths(paths: List<String>): List<String> =
    paths.filter(UnitTestPathClassifier::isUnitTest).distinct().sorted()

  public fun noTestsReport(scope: String): String = "No unit tests in scope ($scope); nothing to review.\n"
}
