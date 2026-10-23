package skillbill.ports.blockincident

import skillbill.ports.blockincident.model.ProductIssueResult

/** Reads and writes issues in Skill Bill's fixed product repository. */
interface ProductIssueTrackerPort {
  /** Finds an open or closed issue carrying [fingerprint]. */
  fun findByFingerprint(
    fingerprint: String,
    token: String,
  ): ProductIssueResult

  /** Creates a product issue with the sanitized [body] and supplied [labels]. */
  fun create(
    title: String,
    body: String,
    labels: List<String>,
    token: String,
  ): ProductIssueResult

  /** Reconciles the single incident-marker comment for [issueNumber] to the exact sanitized [body]. */
  fun comment(
    issueNumber: Long,
    body: String,
    token: String,
  ): ProductIssueResult
}

/** Supplies an existing GitHub credential without retaining it. */
fun interface ProductIssueCredentialPort {
  fun token(): String?
}
