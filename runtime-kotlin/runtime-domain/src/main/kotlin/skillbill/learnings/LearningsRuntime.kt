package skillbill.learnings

import skillbill.error.learning.InvalidLearningSourceReason
import skillbill.error.learning.invalidLearningSource
import skillbill.learnings.model.LearningScope
import skillbill.learnings.model.LearningSourceReference
import skillbill.learnings.model.LearningSourceValidation
import skillbill.learnings.model.RejectedLearningSourceOutcome

object LearningsRuntime {
  val learningStatuses: List<String> = listOf("active", "disabled")
  val rejectedFindingOutcomeTypes: List<String> =
    listOf(
      RejectedFindingOutcomeType.FIX_REJECTED,
      RejectedFindingOutcomeType.FALSE_POSITIVE,
    ).map { it.wireValue }

  fun validateLearningScope(
    scope: LearningScope,
    scopeKey: String,
  ): Pair<LearningScope, String> = scope to scope.normalizeScopeKey(scopeKey)

  fun validateLearningSourceReference(
    sourceReviewRunId: String?,
    sourceFindingId: String?,
  ): LearningSourceReference {
    require(!sourceReviewRunId.isNullOrBlank() && !sourceFindingId.isNullOrBlank()) {
      "Learnings must be derived from a rejected review finding. Provide both --from-run and --from-finding."
    }
    return LearningSourceReference(
      reviewRunId = sourceReviewRunId.trim(),
      findingId = sourceFindingId.trim(),
    )
  }

  fun validateLearningSource(
    sourceReviewRunId: String?,
    sourceFindingId: String?,
    sourceFindingExists: Boolean,
    latestRejectedOutcome: RejectedLearningSourceOutcome?,
  ): LearningSourceValidation =
    validateLearningSource(
      sourceReference = validateLearningSourceReference(sourceReviewRunId, sourceFindingId),
      sourceFindingExists = sourceFindingExists,
      latestRejectedOutcome = latestRejectedOutcome,
    )

  fun validateLearningSource(
    sourceReference: LearningSourceReference,
    sourceFindingExists: Boolean,
    latestRejectedOutcome: RejectedLearningSourceOutcome?,
  ): LearningSourceValidation {
    if (!sourceFindingExists) {
      throw invalidLearningSource(
        reason = InvalidLearningSourceReason.UNKNOWN_FINDING,
        reviewRunId = sourceReference.reviewRunId,
        findingId = sourceReference.findingId,
      )
    }
    if (latestRejectedOutcome == null) {
      throw invalidLearningSource(
        reason = InvalidLearningSourceReason.NOT_REJECTED,
        reviewRunId = sourceReference.reviewRunId,
        findingId = sourceReference.findingId,
      )
    }
    return LearningSourceValidation(
      reviewRunId = sourceReference.reviewRunId,
      findingId = sourceReference.findingId,
      rejectedOutcome = latestRejectedOutcome,
    )
  }

  fun normalizeOptionalLookupValue(
    rawValue: String?,
    argumentName: String,
  ): String? {
    if (rawValue == null) {
      return null
    }
    val normalized = rawValue.trim()
    require(normalized.isNotEmpty()) { "$argumentName must not be empty when provided." }
    return normalized
  }

  fun validateLearningStatus(status: String): String {
    require(status in learningStatuses) {
      "Learning status must be one of ${learningStatuses.joinToString(", ")}."
    }
    return status
  }

  fun validateLearningText(
    title: String,
    ruleText: String,
  ): Pair<String, String> {
    val normalizedTitle = title.trim()
    val normalizedRuleText = ruleText.trim()
    require(normalizedTitle.isNotEmpty()) { "Learning title must not be empty." }
    require(normalizedRuleText.isNotEmpty()) { "Learning rule text must not be empty." }
    return normalizedTitle to normalizedRuleText
  }

  fun effectiveRationale(
    rationale: String,
    rejectedOutcomeNote: String,
  ): String =
    if (rationale.trim().isEmpty() && rejectedOutcomeNote.isNotBlank()) {
      rejectedOutcomeNote.trim()
    } else {
      rationale.trim()
    }
}

private enum class RejectedFindingOutcomeType(val wireValue: String) {
  FIX_REJECTED("fix_rejected"),
  FALSE_POSITIVE("false_positive"),
}
