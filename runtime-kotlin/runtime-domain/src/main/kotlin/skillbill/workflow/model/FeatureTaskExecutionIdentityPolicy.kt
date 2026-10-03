package skillbill.workflow.model

import skillbill.contracts.issuekey.MAX_ISSUE_KEY_LENGTH
import skillbill.contracts.issuekey.isWellFormedIssueKey
import skillbill.contracts.issuekey.normalizeIssueKey
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError

object FeatureTaskExecutionIdentityPolicy {
  const val REPOSITORY_IDENTITY_PREFIX: String = "repo-root-realpath-v1:"

  private const val MAX_ECHOED_VALUE_LENGTH = 120

  fun validate(
    identity: FeatureTaskExecutionIdentity,
    sourceLabel: String = identity.workflowId,
  ) {
    val failure =
      when {
        identity.contractVersion != FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION ->
          "contract_version must be $FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION"
        identity.workflowId.isBlank() -> "workflow_id is malformed: expected a non-blank id"
        !isWellFormedIssueKey(identity.normalizedIssueKey) ->
          issueKeyFailure("normalized_issue_key", identity.normalizedIssueKey)
        !validRepositoryIdentity(identity.repositoryIdentity) ->
          repositoryIdentityFailure(identity.repositoryIdentity)
        !validGovernedSpecPath(identity.governedSpecPath) ->
          governedSpecPathFailure(identity.governedSpecPath)
        else -> null
      }
    failure?.let { throw InvalidFeatureTaskExecutionIdentitySchemaError(sourceLabel, it) }
  }

  fun normalizeIssueKey(
    issueKey: String,
    sourceLabel: String,
  ): String {
    if (!isWellFormedIssueKey(issueKey)) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(
        sourceLabel,
        issueKeyFailure("issue_key", issueKey),
      )
    }
    return canonicalIssueKey(issueKey)
  }

  fun canonicalIssueKey(issueKey: String): String = issueKey.trim().uppercase()

  fun canonicalGovernedSpecPath(
    specPath: String,
    repositoryIdentity: String,
  ): String {
    val repositoryRoot = repositoryIdentity.removePrefix(REPOSITORY_IDENTITY_PREFIX).trimEnd('/')
    val normalized = specPath.replace('\\', '/')
    return if (normalized.startsWith("$repositoryRoot/")) {
      normalized.removePrefix("$repositoryRoot/")
    } else {
      normalized
    }
  }

  fun sameGovernedSpecPath(
    left: String,
    right: String,
    repositoryIdentity: String,
  ): Boolean =
    canonicalGovernedSpecPath(left, repositoryIdentity) == canonicalGovernedSpecPath(right, repositoryIdentity)

  fun validateLookupRequest(
    issueKey: String,
    repositoryIdentity: String,
  ): String {
    val normalizedIssueKey = normalizeIssueKey(issueKey, "lookup request")
    if (!validRepositoryIdentity(repositoryIdentity)) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(
        "lookup request",
        repositoryIdentityFailure(repositoryIdentity),
      )
    }
    return normalizedIssueKey
  }

  private fun issueKeyFailure(
    field: String,
    value: String,
  ): String =
    "$field is malformed: expected a non-blank issue key of at most $MAX_ISSUE_KEY_LENGTH characters " +
      "with no control characters, but received ${echo(value)}"

  private fun repositoryIdentityFailure(value: String): String =
    "repository_identity is malformed: expected the prefix '$REPOSITORY_IDENTITY_PREFIX' followed by the " +
      "absolute real path of the Git top-level directory " +
      "(for example $REPOSITORY_IDENTITY_PREFIX/home/me/projects/app), but received ${echo(value)}"

  private fun governedSpecPathFailure(value: String): String =
    "governed_spec_path is malformed: expected a repository-relative '.feature-specs/<...>.md' path with no " +
      "empty, '.', or '..' segments, but received ${echo(value)}"

  private fun echo(value: String): String {
    val sanitized = value.replace("\r", "\\r").replace("\n", "\\n")
    val clipped =
      if (sanitized.length > MAX_ECHOED_VALUE_LENGTH) {
        sanitized.take(MAX_ECHOED_VALUE_LENGTH) + "..."
      } else {
        sanitized
      }
    return "'$clipped'"
  }

  private fun validRepositoryIdentity(value: String): Boolean =
    value.startsWith(REPOSITORY_IDENTITY_PREFIX) &&
      value.length > REPOSITORY_IDENTITY_PREFIX.length &&
      value.substring(REPOSITORY_IDENTITY_PREFIX.length).startsWith('/') &&
      value.none { it == '\r' || it == '\n' }

  private fun validGovernedSpecPath(value: String): Boolean =
    value.startsWith(".feature-specs/") &&
      value.endsWith(".md") &&
      value.none { it == '\r' || it == '\n' } &&
      value.split('/').none { it.isBlank() || it == "." || it == ".." }
}
