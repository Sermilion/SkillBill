package skillbill.error.shellcontent

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException

enum class ReviewContextFailureCode : RuntimeFailureCode {
  INVALID_SKILL_CONTENT_IDENTITY,
  SKILL_CONTENT_IDENTITY_MISMATCH,
  REVIEW_CONTEXT_SCHEMA,
  LEARNING_RULE_TEXT_TOO_LONG,
  LEARNING_TITLE_TOO_LONG,
  HUNK_EVIDENCE_LOCATOR_MISSING,
  HUNK_EVIDENCE_LOCATOR_UNREADABLE,
  HUNK_EVIDENCE_INTEGRITY,
  UNREADABLE_SPEC_INTENT,
  REVIEW_AGGREGATION_INTEGRITY,
}

fun invalidSkillContentIdentityError(
  sourceLabel: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    ReviewContextFailureCode.INVALID_SKILL_CONTENT_IDENTITY,
    "Skill content identity '${sourceLabel.ifBlank { "<unknown>" }}' is invalid: $reason",
    cause,
  )

fun skillContentIdentityMismatchError(
  suppliedIdentity: String,
  installedIdentity: String,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    ReviewContextFailureCode.SKILL_CONTENT_IDENTITY_MISMATCH,
    "Skill content identity mismatch: supplied source '$suppliedIdentity'; " +
      "installed source '$installedIdentity'.",
  )

fun invalidReviewContextSchemaError(
  sourceLabel: String,
  reason: String,
  definitionName: String? = null,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    ReviewContextFailureCode.REVIEW_CONTEXT_SCHEMA,
    "Review context '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation" +
      definitionName?.takeIf { it.isNotBlank() }?.let { " for definition '$it'" }.orEmpty() +
      ": $reason",
    cause,
  )

const val REVIEW_HUNK_EVIDENCE_INTEGRITY: String = "review_hunk_evidence_integrity"

fun reviewLearningRuleTextTooLongError(
  learningId: String,
  ruleTextLength: Int,
  maxChars: Int,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    ReviewContextFailureCode.LEARNING_RULE_TEXT_TOO_LONG,
    "review_learning_rule_text_too_long: learning '$learningId' rule text is $ruleTextLength characters, " +
      "over the bounded projection limit of $maxChars; refusing to truncate.",
  )

fun reviewLearningTitleTooLongError(
  learningId: String,
  titleLength: Int,
  maxChars: Int,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    ReviewContextFailureCode.LEARNING_TITLE_TOO_LONG,
    "review_learning_title_too_long: learning '$learningId' title is $titleLength characters, " +
      "over the bounded projection limit of $maxChars; refusing to truncate.",
  )

fun reviewHunkEvidenceLocatorMissingError(storePath: String): SkillBillRuntimeException =
  SkillBillRuntimeException(
    ReviewContextFailureCode.HUNK_EVIDENCE_LOCATOR_MISSING,
    "review_hunk_evidence_locator_missing: store_path '$storePath' is missing; refusing to compose or launch.",
  )

fun reviewHunkEvidenceLocatorUnreadableError(
  storePath: String,
  reason: String,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    ReviewContextFailureCode.HUNK_EVIDENCE_LOCATOR_UNREADABLE,
    "review_hunk_evidence_locator_unreadable: store_path '$storePath' is unreadable ($reason); " +
      "refusing to compose or launch.",
  )

fun reviewHunkEvidenceIntegrityError(
  storePath: String,
  expectedDigest: String,
  observedDigest: String,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    ReviewContextFailureCode.HUNK_EVIDENCE_INTEGRITY,
    "$REVIEW_HUNK_EVIDENCE_INTEGRITY: store_path '$storePath' body digest '$observedDigest' does not match " +
      "locator digest '$expectedDigest'; refusing to compose or launch.",
  )

fun unreadableSpecIntentProjectionError(
  specPath: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    ReviewContextFailureCode.UNREADABLE_SPEC_INTENT,
    "Projection 'spec_intent_projection' could not be read from '${specPath.ifBlank { "<unknown>" }}': $reason",
    cause,
  )

fun reviewAggregationIntegrityError(
  reason: String,
  lanes: List<String> = emptyList(),
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    ReviewContextFailureCode.REVIEW_AGGREGATION_INTEGRITY,
    "Delegated review aggregation rejected the lane results: $reason" +
      lanes.takeIf { it.isNotEmpty() }?.let { " (${it.sorted().joinToString(", ")})" }.orEmpty(),
  )
