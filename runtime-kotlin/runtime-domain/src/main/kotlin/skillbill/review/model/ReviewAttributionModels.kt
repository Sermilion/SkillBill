package skillbill.review.model

const val UNRESOLVED_ATTRIBUTION: String = "unresolved"

internal enum class CanonicalScope(val wireValue: String) {
  WORKING_TREE("working_tree"),
  STAGED("staged"),
  COMMIT_RANGE("commit_range"),
  PULL_REQUEST("pull_request"),
  OTHER("other"),
}

data class CanonicalAttribution(
  val canonical: String,
  val raw: String?,
  val detail: String? = null,
) {
  val resolved: Boolean get() = canonical != UNRESOLVED_ATTRIBUTION
}
