package skillbill.ports.workchanges.model

sealed interface WorkTreeComparisonResult {
  data class Resolved(val baseSha: String, val upstreamSha: String?) : WorkTreeComparisonResult

  data class Unavailable(val reason: String) : WorkTreeComparisonResult
}
