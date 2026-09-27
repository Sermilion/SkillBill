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

class ReleaseWorktreeDirtyError(
  val repoRoot: String,
) : OperationRefusalError("Release requires a clean worktree; '$repoRoot' has uncommitted changes.")

class ReleaseBranchBehindRemoteError(
  val branch: String,
) : OperationRefusalError("Release requires '$branch' to be up to date with origin/$branch; pull first.")
