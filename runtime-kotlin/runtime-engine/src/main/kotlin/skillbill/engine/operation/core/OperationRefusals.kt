package skillbill.engine.operation.core

internal fun unknownToken(token: String): OperationOutcome.Blocked =
  OperationOutcome.Blocked("No operation proposal has token '$token'.")

internal fun consumedToken(token: String): OperationOutcome.Blocked =
  OperationOutcome.Blocked("Operation proposal '$token' was already confirmed.")

internal fun supersededToken(token: String): OperationOutcome.Blocked =
  OperationOutcome.Blocked(
    "Operation proposal '$token' was superseded by a newer proposal; confirm the newest token.",
  )

internal fun foreignToken(
  token: String,
  operationId: String,
  repoRoot: String,
): OperationOutcome.Blocked =
  OperationOutcome.Blocked("Operation proposal '$token' does not belong to operation '$operationId' in '$repoRoot'.")

internal fun anchorsMoved(
  token: String,
  movedAnchors: List<String>,
): OperationOutcome.Blocked =
  OperationOutcome.Blocked(
    "Operation proposal '$token' is stale; ${movedAnchors.joinToString(", ")} moved since it was proposed. " +
      "Invoke the operation again for a fresh proposal.",
  )

internal fun anchorUnreadable(
  anchor: String,
  detail: String,
): OperationOutcome.Blocked = OperationOutcome.Blocked("Could not read operation anchor '$anchor': $detail")

internal fun missingIntake(
  operationId: String,
  expected: String,
): OperationOutcome.Usage = OperationOutcome.Usage("Operation '$operationId' requires an intake: $expected.")

internal fun unresolvableScope(
  scope: String,
  detail: String,
): OperationOutcome.Usage = OperationOutcome.Usage("Could not resolve scope '$scope': $detail")

internal fun invalidArgument(
  key: String,
  value: String,
  expected: String,
): OperationOutcome.Usage = OperationOutcome.Usage("Unknown $key:$value; expected $expected.")

internal fun invalidSelection(
  selection: String,
  detail: String,
): OperationOutcome.Usage = OperationOutcome.Usage("Selection '$selection' is invalid: $detail")

internal fun pullRequestNotFound(reference: String?): OperationOutcome.Blocked =
  OperationOutcome.Blocked(
    reference?.let { "No pull request matches '$it'." }
      ?: "The current branch has no pull request; pass a PR number or URL.",
  )

internal fun releaseWorktreeDirty(repoRoot: String): OperationOutcome.Blocked =
  OperationOutcome.Blocked("Release requires a clean worktree; '$repoRoot' has uncommitted changes.")

internal fun releaseBranchBehind(branch: String): OperationOutcome.Blocked =
  OperationOutcome.Blocked("Release requires '$branch' to be up to date with origin/$branch; pull first.")

internal fun unresolvableVerifyTarget(
  target: String,
  detail: String,
): OperationOutcome.Usage =
  OperationOutcome.Usage("Could not resolve verify target '$target': $detail Pass target:<base>..<head> instead.")

internal fun unknownVerifyWorkflow(workflowId: String): OperationOutcome.Blocked =
  OperationOutcome.Blocked("No verify workflow has token '$workflowId'.")

internal fun closedVerifyWorkflow(
  workflowId: String,
  status: String,
  supersededBy: String?,
): OperationOutcome.Blocked =
  OperationOutcome.Blocked(
    supersededBy?.let { "Verify workflow '$workflowId' was superseded by '$it'; confirm the newest token." }
      ?: "Verify workflow '$workflowId' is $status; invoke operation verify again for a fresh run.",
  )
