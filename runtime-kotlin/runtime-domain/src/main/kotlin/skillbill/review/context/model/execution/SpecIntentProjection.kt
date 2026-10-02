package skillbill.review.context.model.execution

import skillbill.model.FileLocation
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.hunk.SHA256_HEX

enum class SpecIntentAbsenceReason(val wireValue: String) {
  NO_SPEC_FOUND("no_spec_found"),
  AMBIGUOUS_MATCH("ambiguous_match"),
  NOT_APPLICABLE_SCOPE("not_applicable_scope"),
  ;

  companion object {
    fun fromWire(value: String): SpecIntentAbsenceReason =
      entries.firstOrNull { it.wireValue == value }
        ?: error("Unknown spec-intent absence reason '$value'.")
  }
}

enum class SpecIntentResolutionRung(val wireValue: String) {
  EXPLICIT("explicit"),
  MANIFEST("manifest"),
  GLOB("glob"),
  NONE("none"),
}

data class SpecIntentProvenance(
  val specPath: String,
  val contentDigest: String,
) {
  init {
    require(specPath.isNotBlank()) { "Spec intent provenance spec_path must not be blank." }
    require(contentDigest.matches(SHA256_HEX)) {
      "Spec intent provenance content_digest must be lowercase SHA-256."
    }
  }
}

data class SpecIntentSurroundingContext(
  val specPath: String,
  val contentDigest: String,
) {
  init {
    require(specPath.isNotBlank()) { "Spec intent surrounding context spec_path must not be blank." }
    require(contentDigest.matches(SHA256_HEX)) {
      "Spec intent surrounding context content_digest must be lowercase SHA-256."
    }
  }
}

data class SpecIntentProjection(
  val intendedOutcome: String,
  val acceptanceCriteria: List<String>,
  val constraints: List<String>,
  val nonGoals: List<String>,
  val deferredItems: List<String>,
  val provenance: SpecIntentProvenance,
  val declaredByteBudget: Int,
  val surroundingContext: SpecIntentSurroundingContext? = null,
) {
  init {
    require(intendedOutcome.isNotBlank()) { "Spec intent intended_outcome must not be blank." }
    require(declaredByteBudget > 0) { "Spec intent declared_byte_budget must be positive." }
  }
}

sealed class SpecIntentResolution {
  abstract val degradations: List<SpecIntentDegradationRecord>

  data class Resolved(
    val projection: SpecIntentProjection,
    override val degradations: List<SpecIntentDegradationRecord> = emptyList(),
  ) : SpecIntentResolution()

  data class None(
    val reason: SpecIntentAbsenceReason,
    override val degradations: List<SpecIntentDegradationRecord> = emptyList(),
  ) : SpecIntentResolution()
}

data class SpecIntentDegradationRecord(
  val seam: String,
  val reason: String,
  val rung: String,
  val resolvedPath: String? = null,
)

data class SpecIntentProjectionResolveRequest(
  val repoRoot: FileLocation,
  val explicitSpecPath: FileLocation? = null,
  val branchName: String = "",
  val changedPaths: List<String> = emptyList(),
  val budget: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy.DEFAULT,
)
