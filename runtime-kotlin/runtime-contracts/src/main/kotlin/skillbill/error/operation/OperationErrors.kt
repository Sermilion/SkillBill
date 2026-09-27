package skillbill.error.operation

import skillbill.error.core.ShellContentContractException

/** An operation invocation the caller must correct; the CLI reports it as a usage error. */
open class OperationUsageError(
  message: String,
) : ShellContentContractException(message)

/** A refusal that stops an operation before it changes anything; the executor reports it as blocked. */
open class OperationRefusalError(
  message: String,
) : ShellContentContractException(message)

class DuplicateOperationIdError(
  val operationId: String,
) : ShellContentContractException("Operation '$operationId' is registered more than once.")

class UnknownOperationIdError(
  val operationId: String,
  val knownIds: List<String>,
) : OperationUsageError("Unknown operation '$operationId'; expected one of ${knownIds.joinToString(", ")}.")

class OperationConfirmationUnsupportedError(
  val operationId: String,
) : OperationUsageError("Operation '$operationId' takes no confirm: token.")

class UnknownOperationTokenError(
  val token: String,
) : OperationRefusalError("No operation proposal has token '$token'.")

class ConsumedOperationTokenError(
  val token: String,
) : OperationRefusalError("Operation proposal '$token' was already confirmed.")

class SupersededOperationTokenError(
  val token: String,
) : OperationRefusalError("Operation proposal '$token' was superseded by a newer proposal; confirm the newest token.")

class ForeignOperationTokenError(
  val token: String,
  val operationId: String,
  val repoRoot: String,
) : OperationRefusalError("Operation proposal '$token' does not belong to operation '$operationId' in '$repoRoot'.")

class MovedOperationAnchorsError(
  val token: String,
  val movedAnchors: List<String>,
) : OperationRefusalError(
    "Operation proposal '$token' is stale; ${movedAnchors.joinToString(", ")} moved since it was proposed. " +
      "Invoke the operation again for a fresh proposal.",
  )

class OperationAnchorUnreadableError(
  val anchor: String,
  val detail: String,
) : OperationRefusalError("Could not read operation anchor '$anchor': $detail")

class MissingReleaseBumpError(
  val bump: String?,
) : OperationUsageError(
    (bump?.let { "Unknown release bump '$it'" } ?: "Release requires a bump") +
      "; pass bump:patch, bump:minor, or bump:major.",
  )

class MissingOperationIntakeError(
  val operationId: String,
  val expected: String,
) : OperationUsageError("Operation '$operationId' requires an intake: $expected.")

class UnresolvableOperationScopeError(
  val scope: String,
  val detail: String,
) : OperationUsageError("Could not resolve scope '$scope': $detail")

class InvalidOperationArgumentError(
  val key: String,
  val value: String,
  val expected: String,
) : OperationUsageError("Unknown $key:$value; expected $expected.")

class MissingOperationSelectionError(
  val operationId: String,
  val expected: String,
) : OperationUsageError("Operation '$operationId' needs a selection with confirm:; pass $expected.")

class InvalidOperationSelectionError(
  val selection: String,
  val detail: String,
) : OperationUsageError("Selection '$selection' is invalid: $detail")

class PullRequestNotFoundError(
  val reference: String?,
) : OperationRefusalError(
    reference?.let { "No pull request matches '$it'." }
      ?: "The current branch has no pull request; pass a PR number or URL.",
  )

class PullRequestBranchNotCheckedOutError(
  val pullRequestBranch: String,
  val currentBranch: String,
) : OperationRefusalError(
    "The pull request's branch '$pullRequestBranch' is not checked out (current: '$currentBranch'); check it out " +
      "before confirming fixes.",
  )

class ProtectedBranchPushError(
  val branch: String,
) : OperationRefusalError("Refusing to push protected branch '$branch'; re-run with push:off.")

class PushWorktreeDirtyError(
  val repoRoot: String,
) : OperationRefusalError(
    "push:on commits every change in the worktree, and '$repoRoot' already has uncommitted changes; commit or " +
      "stash them, or re-run with push:off.",
  )

class ReleaseWorktreeDirtyError(
  val repoRoot: String,
) : OperationRefusalError("Release requires a clean worktree; '$repoRoot' has uncommitted changes.")

class ReleaseBranchBehindRemoteError(
  val branch: String,
) : OperationRefusalError("Release requires '$branch' to be up to date with origin/$branch; pull first.")
