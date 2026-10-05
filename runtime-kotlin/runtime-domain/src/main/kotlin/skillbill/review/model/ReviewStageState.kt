package skillbill.review.model

import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidReviewContextSchemaError

enum class ReviewStage(val wireValue: String) {
  REVIEW("review"),
  VERIFICATION("verification"),
  ADJUDICATION("adjudication"),
  ;

  companion object {
    fun fromWire(value: String): ReviewStage =
      entries.firstOrNull { it.wireValue == value }
        ?: throw invalidReviewContextSchemaError("review_stage", "Unknown review stage '$value'.")
  }
}

enum class ReviewClaimVerdict(val wireValue: String) {
  CONFIRMED("confirmed"),
  REFUTED("refuted"),
  UNRESOLVED("unresolved"),
  ;

  companion object {
    fun fromWire(value: String): ReviewClaimVerdict =
      entries.firstOrNull { it.wireValue == value }
        ?: throw invalidReviewContextSchemaError("claim_verdict", "Unknown claim verdict '$value'.")
  }
}

enum class ReviewScopeDisposition(val wireValue: String) {
  IN_SCOPE("in_scope"),
  OUT_OF_SCOPE_PREEXISTING("out_of_scope_preexisting"),
  SPEC_DEVIATION("spec_deviation"),
  SPEC_ACCEPTED_TRADEOFF("spec_accepted_tradeoff"),
  ;

  companion object {
    fun fromWire(value: String): ReviewScopeDisposition =
      entries.firstOrNull { it.wireValue == value }
        ?: throw invalidReviewContextSchemaError("scope_disposition", "Unknown scope disposition '$value'.")
  }
}

enum class ReviewSeverityAdjustmentDirection(val wireValue: String) {
  RAISE("raise"),
  LOWER("lower"),
  ;

  companion object {
    fun fromWire(value: String): ReviewSeverityAdjustmentDirection =
      entries.firstOrNull { it.wireValue == value }
        ?: throw invalidReviewContextSchemaError(
          "severity_adjustment_direction",
          "Unknown severity adjustment direction '$value'.",
        )
  }
}

enum class ReviewStageReached(val wireValue: String) {
  REACHED("reached"),
  NOT_REACHED("not_reached"),
  ;

  companion object {
    fun fromWire(value: String): ReviewStageReached =
      entries.firstOrNull { it.wireValue == value }
        ?: throw invalidReviewContextSchemaError("stage_reached", "Unknown stage reached state '$value'.")
  }
}

data class ReviewFindingCitation(
  val path: String,
  val line: Int,
) {
  init {
    val reason = violation(path, line)
    require(reason == null) { reason.orEmpty() }
  }

  fun encoded(): String = "$path\t$line"

  companion object {
    internal fun violation(path: String, line: Int): String? =
      when {
        path.isBlank() -> "Finding citation path must not be blank."
        line < 1 -> "Finding citation line must be a positive integer."
        else -> repositoryRelativePathViolation(path)
      }

    fun decodeList(raw: String?): List<ReviewFindingCitation> =
      raw.orEmpty().lineSequence().filter { it.isNotBlank() }.map { line ->
        val path = line.substringBefore('\t')
        val lineToken = line.substringAfter('\t', missingDelimiterValue = "")
        if (lineToken.isEmpty()) {
          throw invalidReviewContextSchemaError(
            "finding_citation",
            "Citation line must contain a tab-separated positive integer line number.",
          )
        }
        val lineNumber =
          lineToken.toIntOrNull()
            ?: throw invalidReviewContextSchemaError(
              "finding_citation",
              "Citation line number must decode to a positive integer.",
            )
        ReviewFindingCitation(path, normalizeLine(lineNumber))
      }.toList()

    fun encodeList(citations: List<ReviewFindingCitation>): String =
      citations.joinToString("\n", transform = ReviewFindingCitation::encoded)

    private fun normalizeLine(line: Int): Int = if (line == 0) 1 else line
  }
}

data class ReviewSeverityAdjustment(
  val direction: ReviewSeverityAdjustmentDirection,
  val justification: String,
) {
  init {
    require(justification.isNotBlank()) { "Severity adjustment justification must not be blank." }
  }
}

data class ReviewFindingVerdict(
  val stage: ReviewStage,
  val findingRef: String,
  val claimVerdict: ReviewClaimVerdict,
  val scopeDisposition: ReviewScopeDisposition? = null,
  val citations: List<ReviewFindingCitation> = emptyList(),
  val severityAdjustment: ReviewSeverityAdjustment? = null,
  val recordedAt: String,
  val contractVersion: String = REVIEW_CONTEXT_CONTRACT_VERSION,
  val rejectionReason: String? = null,
) {
  init {
    require(stage == ReviewStage.VERIFICATION || stage == ReviewStage.ADJUDICATION) {
      "Finding verdicts are recorded for verification or adjudication, not '${stage.wireValue}'."
    }
    require(findingRef.isNotBlank()) { "Finding verdict finding_ref must not be blank." }
    require(recordedAt.isNotBlank()) { "Finding verdict recorded_at must not be blank." }
    require(contractVersion.isNotBlank()) { "Finding verdict contract_version must not be blank." }
    require(scopeDisposition == null || stage == ReviewStage.ADJUDICATION) {
      "scope_disposition is absent unless stage is adjudication."
    }
    require(rejectionReason == null || rejectionReason.isNotBlank()) {
      "Finding verdict rejection_reason must not be blank when present."
    }
  }
}

data class ReviewStageBoundary(
  val stage: ReviewStage,
  val reached: ReviewStageReached,
  val recordedAt: String,
  val contractVersion: String = REVIEW_CONTEXT_CONTRACT_VERSION,
) {
  init {
    require(recordedAt.isNotBlank()) { "Stage boundary recorded_at must not be blank." }
    require(contractVersion.isNotBlank()) { "Stage boundary contract_version must not be blank." }
  }
}

data class ReviewPassClaimSnapshot(
  val findings: List<ParallelReviewMergedFinding>,
)

data class ReviewSpecProjectionReference(
  val specPath: String? = null,
  val contentDigest: String? = null,
  val absenceReason: String? = null,
) {
  init {
    val present = specPath != null && contentDigest != null
    val absent = absenceReason != null
    require(present != absent) {
      "Spec projection reference must carry a spec path and content digest, or an absence reason."
    }
    require(specPath == null || specPath.isNotBlank()) { "Spec projection path must not be blank." }
    require(contentDigest == null || contentDigest.isNotBlank()) {
      "Spec projection content digest must not be blank."
    }
    require(absenceReason == null || absenceReason.isNotBlank()) {
      "Spec projection absence reason must not be blank."
    }
  }
}

data class ReviewStageResumeDegradation(
  val seam: String,
  val used: String,
  val expected: String,
  val cause: String,
)

data class ReviewStageResumeReport(
  val durableByStage: Map<ReviewStage, Boolean>,
  val reentryStage: ReviewStage?,
  val degradations: List<ReviewStageResumeDegradation>,
) {
  fun holdsDurableResult(stage: ReviewStage): Boolean = durableByStage[stage] == true
}
