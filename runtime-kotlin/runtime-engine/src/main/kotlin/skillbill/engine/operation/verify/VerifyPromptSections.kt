package skillbill.engine.operation.verify

import skillbill.engine.featuretask.model.review.ReviewTarget

internal object VerifyPromptSections {
  const val EXTRACT_CRITERIA_STEP: String = "operation.verify.extract-criteria"
  const val FEATURE_FLAG_AUDIT_STEP: String = "operation.verify.feature-flag-audit"
  const val CODE_REVIEW_STEP: String = "operation.verify.code-review"
  const val COMPLETENESS_AUDIT_STEP: String = "operation.verify.completeness-audit"
  const val VERDICT_STEP: String = "operation.verify.verdict"

  const val SKIPPED_PREFIX: String = "SKIPPED:"

  val CRITERIA_EXTRACTION: String =
    """
    After reading the spec, produce in one pass:

    1. **Acceptance criteria** — numbered list
    2. **Non-goals** — things explicitly out of scope
    3. **Rollout expectation** — does the spec require guarded rollout?
    4. **Key technical constraints** — specific patterns, APIs, or architectural requirements
    """.trimIndent()

  val FEATURE_FLAG_AUDIT: String =
    """
    ## Feature Flag Audit

    **Skip if:** the spec does not require feature-flagged rollout, no feature flag appears in the diff, and repo policy does not require one.

    **Run if:** the spec requires a feature flag, a feature flag appears in the diff, or the repo has explicit feature-flag policy for this change.

    Verify against the repo's rollout requirements. If the repo does not define its own rollout rubric, use `bill-feature-guard` as a narrow checklist rather than assuming every repo follows it by default:

    1. **Flag exists** — is the flag defined in the codebase?
    2. **Rollback safety** — when flag is OFF, behavior is identical to before the PR
    3. **Minimal checks** — feature flag checks are at the highest practical level (not scattered)
    4. **Legacy preserved** — if Legacy pattern used, legacy code is untouched
    5. **No hybrid states** — no mixing of old/new behavior paths
    6. **Default value** — if a new flag is introduced, it defaults to `false` (disabled)

    Output:

    ```
    FEATURE FLAG AUDIT
    Flag name: <name>
    Pattern: Legacy / DI Switch / Simple Conditional / N/A

    [ PASS | FAIL ] Flag defined in codebase
    [ PASS | FAIL ] Rollback safe (flag OFF = identical old behavior)
    [ PASS | FAIL ] Minimal flag checks (not scattered)
    [ PASS | FAIL ] Legacy code untouched (if applicable)
    [ PASS | FAIL ] No hybrid states
    [ PASS | FAIL ] Default value is false

    Issues: <list, or "None">
    ```
    """.trimIndent()

  val COMPLETENESS_AUDIT: String =
    """
    ## Completeness Audit

    For each numbered acceptance criterion, search the actual code changes to verify implementation:

    ```
    COMPLETENESS AUDIT

    Acceptance criteria: <total>
    Implemented:         <count>
    Missing:             <count>
    Partial:             <count>

    ---

    [PASS] #1: <criterion text>
      Evidence: FileA.kt:42, FileB.kt:88

    [FAIL] #6: <criterion text>
      Not found — <reason>

    [PARTIAL] #8: <criterion text>
      Missing — <what's missing>
    ```

    **Rules:**
    - Every criterion must have concrete file:line evidence or be marked FAIL
    - "Partial" means some but not all aspects of the criterion are covered
    - Check both positive (feature works) and negative (edge cases, error states) aspects
    - If the spec mentions tests, verify test coverage exists for the criterion
    """.trimIndent()

  val CONSOLIDATED_VERDICT: String =
    """
    ## Consolidated Verdict

    Merge all findings into a single report:

    ```
    FEATURE VERIFY: <feature name>

    --- ACCEPTANCE CRITERIA ---
    <completeness audit>

    --- FEATURE FLAG ---
    <audit, or "N/A — no flag required">

    --- CODE REVIEW ---
    <risk register and action items>

    --- UNIT TEST VALUE ---
    <unit test value result, or "N/A — no unit tests changed">

    --- VERDICT ---
    <one of:>
      APPROVE — all criteria met, no blockers
      APPROVE WITH FIXES — all criteria met, but code issues need fixing [list P0/P1]
      REQUEST CHANGES — missing criteria or blockers [list what's missing/blocking]
    ```
    """.trimIndent()

  val VERIFICATION_INPUT_BOUNDARY: String =
    """
    ## Verification Input Boundary

    Each verifier receives only its declared criteria and authoritative bounded
    repository projection. Private workflow evidence, unrelated evaluator outputs,
    telemetry, and complete upstream artifact maps are not prompt inputs. The
    consolidated verdict consumes compact typed evaluator receipts, while repository
    checkpoint state remains authoritative over receipt claims.

    Durable least-context records are versioned boundaries. A legacy workflow,
    briefing, handoff, private-evidence, or delivered-projection record must fail
    through the typed workflow-contract hierarchy; it is never defaulted or decoded
    as the current shape. The actionable operator guidance is to restart the active
    run or use the documented out-of-band migration procedure. Error and
    continuation surfaces identify the incompatible record and consumer projection
    without copying private content.
    """.trimIndent()

  val REVIEW_RUBRIC: String =
    """
    Report findings only; this review never edits, fixes, stages, or commits.
    Criterion-gap detection belongs to the completeness audit. Do not report unsatisfied acceptance criteria.
    Emit every finding in this register shape, one per line:
    - [F-001] Blocker | High | path/File.kt:12 | defect description
    End with exactly one line: `verdict: approved` or `verdict: changes_requested`.
    Use `changes_requested` when any Blocker or Major finding exists; otherwise `approved`.
    """.trimIndent()

  private const val READ_ONLY: String =
    "This step is read-only: do not edit, create, delete, stage, or commit any file. The runtime compares the " +
      "repository before and after this step."

  private const val CRITERIA_HEADINGS: String =
    "Write the four parts under exactly these headings, in this order: `## Acceptance criteria`, `## Non-goals`, " +
      "`## Rollout expectation`, `## Key technical constraints`. Leave a part empty rather than inventing one. " +
      "Do not ask for confirmation; the runtime shows the criteria to the operator."

  fun extractCriteriaDirective(
    specPath: String,
    targetLabel: String,
  ): String =
    listOf(
      "# Operation: verify (extract criteria)\n\n" +
        "Read the task spec at `$specPath` (a file, or a directory of spec files) and extract its criteria. " +
        "$READ_ONLY\n\nVerify target: $targetLabel",
      CRITERIA_EXTRACTION,
      CRITERIA_HEADINGS,
    ).joinToString("\n\n")

  fun featureFlagAuditDirective(comparisonScope: String): String =
    listOf(
      "# Operation: verify (feature flag audit)\n\n" +
        "Audit the change against the feature-flag policy in the prior values; the confirmed criteria, that " +
        "policy, and the diff projection are your only inputs. Inspect the change with " +
        "`git diff $comparisonScope`. $READ_ONLY\n\n" +
        "When the policy's skip rule holds, reply with the single line `$SKIPPED_PREFIX <reason>`. Otherwise emit " +
        "the policy's audit output block.",
      VERIFICATION_INPUT_BOUNDARY,
    ).joinToString("\n\n")

  fun codeReviewDirective(
    baseRevision: String,
    headRevision: String,
    target: ReviewTarget,
  ): String =
    buildString {
      target.openingLines(baseRevision, headRevision).forEach(::appendLine)
      appendLine("Do not launch bill-code-review, delegated review subagents, or an isolated review process.")
      appendLine(READ_ONLY)
      appendLine("Apply the review rubric in the prior values to the change, against the confirmed criteria.")
      appendLine("Do not run `./gradlew check`, the pack collect-all gate, or `bill-code-check`.")
    }.trimEnd()

  fun completenessAuditDirective(comparisonScope: String): String =
    listOf(
      "# Operation: verify (completeness audit)\n\n" +
        "Audit every acceptance criterion in the prior values against the change with the completeness rubric " +
        "there. Inspect the change with `git diff $comparisonScope`. The criteria, the rubric, and the diff " +
        "projection are your only inputs; no other evaluator's output reaches this step. $READ_ONLY\n\n" +
        "Emit the rubric's COMPLETENESS AUDIT block.",
      VERIFICATION_INPUT_BOUNDARY,
    ).joinToString("\n\n")

  fun verdictDirective(): String =
    listOf(
      "# Operation: verify (consolidated verdict)\n\n" +
        "Merge the four evaluator receipts in the prior values into one report with the rubric below. The " +
        "receipts and the diff projection are your only inputs; do not rerun any evaluator. $READ_ONLY\n\n" +
        "This operation is report-only: do not offer to fix issues or to post a PR comment, and run no " +
        "follow-up. The operator decides what to fix.",
      CONSOLIDATED_VERDICT,
      VERIFICATION_INPUT_BOUNDARY,
    ).joinToString("\n\n")
}
