package skillbill.cli.kernel.cli

import com.github.ajalt.clikt.core.UsageError
import skillbill.application.reviewevidence.model.ParallelReviewScope

internal data class StandaloneCodeReviewTarget(
  val scope: ParallelReviewScope,
  val commitRevision: String?,
)

internal const val DEFAULT_CODE_REVIEW_SCOPE = "branch"

internal fun resolveStandaloneCodeReviewTarget(
  positional: String?,
  scopeToken: String,
): StandaloneCodeReviewTarget {
  val positionalToken = positional?.trim()?.takeIf { it.isNotBlank() }
  val optionScope = parsedReviewScope(scopeToken)
  if (positionalToken == null) {
    return StandaloneCodeReviewTarget(optionScope, null)
  }
  val namedScope = namedStandaloneScope(positionalToken)
  if (namedScope != null) {
    if (scopeToken != DEFAULT_CODE_REVIEW_SCOPE && optionScope != namedScope) {
      throw UsageError(
        "A positional '$positionalToken' cannot be combined with --scope '$scopeToken'.",
      )
    }
    return StandaloneCodeReviewTarget(namedScope, null)
  }
  if (scopeToken != DEFAULT_CODE_REVIEW_SCOPE) {
    throw UsageError(
      "A commit target cannot be combined with --scope '$scopeToken'; use the default branch scope.",
    )
  }
  return StandaloneCodeReviewTarget(ParallelReviewScope.BRANCH, lastCommitRevision(positionalToken))
}

internal fun parsedReviewScope(scope: String): ParallelReviewScope =
  when (scope) {
    "staged" -> ParallelReviewScope.STAGED
    "unstaged" -> ParallelReviewScope.UNSTAGED
    "uncommitted" -> ParallelReviewScope.UNCOMMITTED
    DEFAULT_CODE_REVIEW_SCOPE -> ParallelReviewScope.BRANCH
    "pr" -> ParallelReviewScope.PR
    else -> throw UsageError("Invalid scope: $scope")
  }

internal fun namedStandaloneScope(token: String): ParallelReviewScope? =
  when (token.lowercase()) {
    "pr" -> ParallelReviewScope.PR
    "uncommitted" -> ParallelReviewScope.UNCOMMITTED
    "staged" -> ParallelReviewScope.STAGED
    "unstaged" -> ParallelReviewScope.UNSTAGED
    else -> null
  }

private fun lastCommitRevision(token: String): String =
  if (token.equals("last", ignoreCase = true) || token.equals("HEAD", ignoreCase = true)) {
    "HEAD"
  } else {
    token
  }
