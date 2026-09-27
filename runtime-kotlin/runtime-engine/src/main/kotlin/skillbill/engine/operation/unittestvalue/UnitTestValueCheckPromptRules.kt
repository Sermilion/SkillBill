package skillbill.engine.operation.unittestvalue

/**
 * The unit-test value rubric, copied verbatim from `skills/bill-unit-test-value-check/content.md`.
 * `ChecklistOperationRulesParityTest` holds the copies equal to the skill while both exist.
 */
internal object UnitTestValueCheckPromptRules {
  const val REVIEW_STEP: String = "operation.unit-test-value-check.review"

  val STANCE: String =
    """
    Stance: tests are not free. Every test costs review attention, maintenance, and AI
    reasoning tokens on every future change to the code it touches. A test earns its place
    only by being able to catch a realistic regression in behavior someone depends on. The
    burden of proof is on the test, not the reviewer: a test that cannot pass the value test
    below defaults to deletion.
    """.trimIndent()

  /** Everything from Supported Scope through Output: workflow, weighting, patterns, review rules, and format. */
  val RUBRIC: String =
    """
    ## Supported Scope

    - A specific unit test file.
    - The current staged and unstaged change list.
    - A specific commit SHA or ref.

    If the caller does not specify a scope, review the current staged and unstaged changes. If the chosen scope contains no unit tests, say so clearly and stop.

    ## Workflow

    1. Determine the requested scope: file, current changes, or commit.
    2. Read the unit tests in scope and the production code they exercise.
    3. Apply the value test to each test or test block: name a realistic bug — a concrete
       wrong behavior a developer or agent could plausibly introduce — that would make this
       test fail while the rest of the suite passes. No nameable bug means `Useless`.
    4. Weigh recurring maintenance cost: a test coupled to implementation structure (mock
       interaction verification, private details, exact call ordering, duplicated
       implementation steps) fails under behavior-preserving refactors, which makes it a
       recurring cost even when it asserts something real.
    5. Detect redundancy: when several tests exercise the same branch or rule, keep the
       strongest and mark the rest for deletion.
    6. Classify each finding as `Valuable`, `Weak`, or `Useless`, each with a disposition:
       `Useless` is deleted; `Weak` is deleted unless it guards a critical path and a single
       cheap rewrite makes it `Valuable`. Never recommend polishing a low-value test.

    ## Criticality Weighting

    Coverage should concentrate where failure is expensive. Critical paths:

    - Money, billing, quantities, and rounding.
    - Data integrity, migrations, and persistence atomicity.
    - Authentication, authorization, permissions, and tenant isolation.
    - External contracts: API compatibility, serialization, wire and storage formats.
    - Concurrency, cancellation, retries, and failure recovery.
    - Irreversible side effects: sends, deletes, publishes, external writes.

    Trivial glue on a non-critical path needs no test; say so rather than proposing one.

    ## What Counts As Real Value

    - Business rules, branching, calculations, invariants, normalization, and validation.
    - Error handling, retries, permission checks, boundary conditions, and fallback behavior.
    - State transitions and externally visible side effects.
    - Contract behavior at module or API boundaries.
    - Regression coverage for real bugs or realistic failure cases.

    ## Low-Value Patterns To Flag

    - Creating a data object, assigning fields, and asserting the same fields without any logic in between.
    - Instantiating a DTO, entity, or plain model and asserting getters echo constructor values when no validation or normalization exists.
    - Stubbing a collaborator and only asserting the same stubbed value is returned without testing any decision-making.
    - Verifying a mock interaction with no assertion about user-visible or system-visible outcome.
    - Asserting framework or library behavior instead of project logic.
    - Reproducing the implementation step-for-step inside the test and comparing the duplicated result.
    - Tests that only assert `not null`, `true`, `false`, or collection size without tying that assertion to meaningful behavior.
    - Testing trivial mappers, property accessors, generated code, or boilerplate solely to raise coverage numbers.
    - Duplicating a sibling test's coverage of the same branch or rule with different literals.
    - Pinning implementation structure so the test fails on behavior-preserving refactors it was never meant to guard.

    ## Cases That May Look Trivial But Can Still Be Valuable

    - Constructors or factories that validate, normalize, trim, clamp, parse, or reject input.
    - Value objects whose equality, ordering, hashing, or parsing encode business rules.
    - Mapping or serialization code with compatibility or contract risk.
    - Wrapper types that enforce invariants or security-sensitive formatting.

    ## Review Rules

    - Do not reward quantity, coverage percentage, or test count. A shrinking suite is a good outcome when what remains guards real behavior.
    - Do not suggest more tests unless you can name a concrete missing behavior on a critical path.
    - Prefer deleting low-value tests over rewriting them; rewrite only when the behavior matters and the fix is cheap.
    - Never recommend deleting regression coverage tied to a real past bug.
    - Isolate specific low-value tests instead of dismissing an entire file unless the whole file is weak.
    - Use `file:line` evidence for every finding.
    - When confidence is not high, say why.

    ## Output

    Provide:

    - `Overall verdict`: `Strong | Mixed | Weak`
    - `Scope reviewed`
    - A 2-4 line summary focused on real value

    Then include a table:

    | Test or Area | Verdict | Confidence | Evidence | Why it adds or lacks value | Better test or action |
    |--------------|---------|------------|----------|----------------------------|-----------------------|

    Rules for output:

    - Include at most 10 rows, prioritized by recurring maintenance cost saved.
    - Only report findings that materially affect confidence in the tests.
    - End with:
      - `Keep:` strongest tests worth keeping
      - `Rewrite:` only tests guarding critical paths where one cheap fix creates real value
      - `Delete:` every deletion candidate with `file:line`, complete even when the table is capped
      - `Missing high-value cases:` only concrete critical-path behaviors that are not covered
    """.trimIndent()

  /** The read-only review directive over the scope and the unit test paths the runtime resolved. */
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
      STANCE,
      RUBRIC,
    ).joinToString("\n\n")
}
