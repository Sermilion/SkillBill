package skillbill.engine.operation.core

import skillbill.error.core.ShellContentContractException
import skillbill.error.operation.OperationUsageError

internal open class OperationRefusalError(
  message: String,
) : ShellContentContractException(message)

internal class DuplicateOperationIdError(
  val operationId: String,
) : ShellContentContractException("Operation '$operationId' is registered more than once.")

internal class UnknownOperationIdError(
  val operationId: String,
  val knownIds: List<String>,
) : OperationUsageError("Unknown operation '$operationId'; expected one of ${knownIds.joinToString(", ")}.")

internal class OperationConfirmationUnsupportedError(
  val operationId: String,
) : OperationUsageError("Operation '$operationId' takes no confirm: token.")

internal class UnknownOperationTokenError(
  val token: String,
) : OperationRefusalError("No operation proposal has token '$token'.")

internal class ConsumedOperationTokenError(
  val token: String,
) : OperationRefusalError("Operation proposal '$token' was already confirmed.")

internal class SupersededOperationTokenError(
  val token: String,
) : OperationRefusalError("Operation proposal '$token' was superseded by a newer proposal; confirm the newest token.")

internal class ForeignOperationTokenError(
  val token: String,
  val operationId: String,
  val repoRoot: String,
) : OperationRefusalError("Operation proposal '$token' does not belong to operation '$operationId' in '$repoRoot'.")

internal class MovedOperationAnchorsError(
  val token: String,
  val movedAnchors: List<String>,
) : OperationRefusalError(
    "Operation proposal '$token' is stale; ${movedAnchors.joinToString(", ")} moved since it was proposed. " +
      "Invoke the operation again for a fresh proposal.",
  )

internal class OperationAnchorUnreadableError(
  val anchor: String,
  val detail: String,
) : OperationRefusalError("Could not read operation anchor '$anchor': $detail")

internal class MissingReleaseBumpError(
  val bump: String?,
) : OperationUsageError(
    (bump?.let { "Unknown release bump '$it'" } ?: "Release requires a bump") +
      "; pass bump:patch, bump:minor, or bump:major.",
  )

internal class MissingOperationIntakeError(
  val operationId: String,
  val expected: String,
) : OperationUsageError("Operation '$operationId' requires an intake: $expected.")

internal class UnresolvableOperationScopeError(
  val scope: String,
  val detail: String,
) : OperationUsageError("Could not resolve scope '$scope': $detail")

internal class InvalidOperationArgumentError(
  val key: String,
  val value: String,
  val expected: String,
) : OperationUsageError("Unknown $key:$value; expected $expected.")

internal class MissingOperationSelectionError(
  val operationId: String,
  val expected: String,
) : OperationUsageError("Operation '$operationId' needs a selection with confirm:; pass $expected.")

internal class InvalidOperationSelectionError(
  val selection: String,
  val detail: String,
) : OperationUsageError("Selection '$selection' is invalid: $detail")

internal class PullRequestNotFoundError(
  val reference: String?,
) : OperationRefusalError(
    reference?.let { "No pull request matches '$it'." }
      ?: "The current branch has no pull request; pass a PR number or URL.",
  )

internal class PullRequestBranchNotCheckedOutError(
  val pullRequestBranch: String,
  val currentBranch: String,
) : OperationRefusalError(
    "The pull request's branch '$pullRequestBranch' is not checked out (current: '$currentBranch'); check it out " +
      "before confirming fixes.",
  )

internal class ProtectedBranchPushError(
  val branch: String,
) : OperationRefusalError("Refusing to push protected branch '$branch'; re-run with push:off.")

internal class PushWorktreeDirtyError(
  val repoRoot: String,
) : OperationRefusalError(
    "push:on commits every change in the worktree, and '$repoRoot' already has uncommitted changes; commit or " +
      "stash them, or re-run with push:off.",
  )

internal class ReleaseWorktreeDirtyError(
  val repoRoot: String,
) : OperationRefusalError("Release requires a clean worktree; '$repoRoot' has uncommitted changes.")

internal class UnresolvableVerifyTargetError(
  val target: String,
  val detail: String,
) : OperationUsageError("Could not resolve verify target '$target': $detail Pass target:<base>..<head> instead.")

internal class VerifySpecRehydrateNeededError(
  val specPath: String,
) : OperationRefusalError(
    "rehydrate-needed: neither spec '$specPath' nor its decomposition-manifest.yaml exists; rehydrate the spec " +
      "from Linear to that path, then invoke operation verify again.",
  )

internal class VerifyTargetNotCheckedOutError(
  val target: String,
  val targetHead: String,
  val currentHead: String,
) : OperationRefusalError(
    "Verify target '$target' is at $targetHead but HEAD is $currentHead; check the target out, or pass " +
      "target:<base>..HEAD.",
  )

internal class UnknownVerifyWorkflowError(
  val workflowId: String,
) : OperationRefusalError("No verify workflow has token '$workflowId'.")

internal class ForeignVerifyWorkflowError(
  val workflowId: String,
  val repoRoot: String,
) : OperationRefusalError("Verify workflow '$workflowId' does not belong to '$repoRoot'.")

internal class ClosedVerifyWorkflowError(
  val workflowId: String,
  val status: String,
  val supersededBy: String?,
) : OperationRefusalError(
    supersededBy?.let { "Verify workflow '$workflowId' was superseded by '$it'; confirm the newest token." }
      ?: "Verify workflow '$workflowId' is $status; invoke operation verify again for a fresh run.",
  )

internal class ReleaseBranchBehindRemoteError(
  val branch: String,
) : OperationRefusalError("Release requires '$branch' to be up to date with origin/$branch; pull first.")
