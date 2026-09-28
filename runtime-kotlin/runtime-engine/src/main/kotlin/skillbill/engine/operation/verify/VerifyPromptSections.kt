package skillbill.engine.operation.verify

import skillbill.engine.directive.directiveResource
import skillbill.engine.featuretask.model.review.ReviewTarget

internal object VerifyPromptSections {
  const val EXTRACT_CRITERIA_STEP: String = "operation.verify.extract-criteria"
  const val FEATURE_FLAG_AUDIT_STEP: String = "operation.verify.feature-flag-audit"
  const val CODE_REVIEW_STEP: String = "operation.verify.code-review"
  const val COMPLETENESS_AUDIT_STEP: String = "operation.verify.completeness-audit"
  const val VERDICT_STEP: String = "operation.verify.verdict"

  const val SKIPPED_PREFIX: String = "SKIPPED:"

  private const val VERIFY_DIRECTIVE_RESOURCE: String = "/skillbill/engine/operation/verify/verify-directive.md"

  private val directive: List<String> by lazy { directiveResource(VERIFY_DIRECTIVE_RESOURCE).lines() }

  val CRITERIA_EXTRACTION: String by lazy {
    span(
      "After reading the spec, produce in one pass:",
      "Then ask: **Confirm or adjust the criteria before I review the PR.**",
    )
  }

  val FEATURE_FLAG_AUDIT: String by lazy { section("## Feature Flag Audit") }

  val COMPLETENESS_AUDIT: String by lazy { section("## Completeness Audit") }

  val CONSOLIDATED_VERDICT: String by lazy { span("## Consolidated Verdict", "After presenting the verdict, ask:") }

  val VERIFICATION_INPUT_BOUNDARY: String by lazy { section("## Verification Input Boundary") }

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
      appendLine(
        "Do not launch skill-bill phase review, delegated review subagents, or an isolated review process.",
      )
      appendLine(READ_ONLY)
      appendLine("Apply the review rubric in the prior values to the change, against the confirmed criteria.")
      appendLine("Do not run `./gradlew check`, the pack collect-all gate, or `skill-bill phase validation`.")
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

  private fun section(heading: String): String {
    val level = heading.takeWhile { it == '#' }.length
    val next = Regex("^#{1,$level} ")
    val lines = from(heading)
    return (listOf(lines.first()) + lines.drop(1).takeWhile { !next.containsMatchIn(it) }).joinToString("\n").trim()
  }

  private fun span(
    start: String,
    until: String,
  ): String = from(start).takeWhile { it != until }.joinToString("\n").trim()

  private fun from(start: String): List<String> =
    directive.dropWhile { it != start }.also { lines ->
      check(lines.isNotEmpty()) { "Verify directive $VERIFY_DIRECTIVE_RESOURCE has no line '$start'." }
    }
}
