package skillbill.engine.goalrunner.monitoring

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goal.goalRepositoryRootFromIdentity
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.error.core.catchingNonCancellation
import skillbill.goalrunner.model.GOAL_MAIN_CHECKOUT_CHECKED_OUT
import skillbill.goalrunner.model.GOAL_MAIN_CHECKOUT_FAILED
import skillbill.goalrunner.model.GOAL_MAIN_CHECKOUT_SKIPPED_UNCOMMITTED_CHANGES
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.goalworktree.GoalWorktreeResources
import skillbill.ports.workflow.goalworktree.model.GoalBranchCheckout
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeSettleRequest
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeSettlement
import java.nio.file.Path
import java.time.Clock

@Inject
public class GoalWorktreeCompletionSettlement(
  private val manifestStore: GoalRunnerManifestStore,
  private val worktrees: GoalWorktreeResources,
  private val repositories: RepositoryEnclosingRootPort,
  private val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
) {
  public fun settle(state: GoalRunnerManifestState) {
    val parentWorkflowId = state.parentWorkflowId
    catchingNonCancellation { settleUnchecked(parentWorkflowId, state.manifest.featureBranch) }.onFailure { failure ->
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "goal worktree cleanup failed: seam=goal_worktree.settle",
        failure,
      )
      catchingNonCancellation {
        recordRetained(parentWorkflowId, CLEANUP_FAILED, failure.message ?: "worktree cleanup failed")
      }
    }
  }

  private fun settleUnchecked(
    parentWorkflowId: String,
    featureBranch: String?,
  ) {
    val control = manifestStore.controlState(parentWorkflowId)
    val path = control.executionWorktreePath?.let(Path::of) ?: return
    val root =
      goalRepositoryRootFromIdentity(control.repositoryIdentity.orEmpty())
        ?.let(repositories::optionalRealPath)
        ?: repositories.optionalRealPath(path)?.let(repositories::primaryRepositoryRoot)
    if (root == null) {
      recordRetained(parentWorkflowId, CLEANUP_FAILED, "the primary repository root could not be resolved")
      return
    }
    when (val settlement = worktrees.settle(GoalWorktreeSettleRequest(root, path))) {
      GoalWorktreeSettlement.Removed, GoalWorktreeSettlement.Absent -> {
        val mainCheckout = control.goalCompletedAt?.let { mainCheckoutOutcome(root, featureBranch) }
        manifestStore.updateControlState(parentWorkflowId) { current ->
          if (current.goalCompletedAt == null) {
            current
          } else {
            current.copy(
              executionWorktreeRemovedAt = clock.instant().toString(),
              executionWorktreeRetentionReason = null,
              executionWorktreeRetentionDetail = null,
              executionMainCheckoutOutcome = mainCheckout?.outcome,
              executionMainCheckoutDetail = mainCheckout?.detail,
            )
          }
        }
      }
      is GoalWorktreeSettlement.Retained ->
        recordRetained(parentWorkflowId, settlement.reason.wireValue, settlement.detail)
    }
  }

  public fun retryIfPending(state: GoalRunnerManifestState) {
    val pending =
      catchingNonCancellation {
        if (state.controlState.goalCompletedAt == null || state.controlState.executionWorktreePath == null ||
          state.controlState.executionWorktreeRemovedAt != null
        ) {
          false
        } else {
          val lease = manifestStore.executionLease(state.parentWorkflowId)
          lease == null || !lease.expiresAt.isAfter(clock.instant())
        }
      }.getOrElse { failure ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "goal worktree retry skipped: seam=goal_worktree.settle",
          failure,
        )
        false
      }
    if (pending) settle(state)
  }

  private fun mainCheckoutOutcome(
    root: Path,
    featureBranch: String?,
  ): MainCheckoutRecord {
    val branch = featureBranch?.trim().orEmpty()
    val (outcome, detail) =
      if (branch.isEmpty()) {
        GOAL_MAIN_CHECKOUT_FAILED to "the goal has no feature branch"
      } else {
        catchingNonCancellation { worktrees.checkoutGoalBranch(root, branch) }.fold(
          onSuccess = { result ->
            when (result) {
              GoalBranchCheckout.CheckedOut -> GOAL_MAIN_CHECKOUT_CHECKED_OUT to "main checkout is on $branch"
              is GoalBranchCheckout.SkippedUncommittedChanges ->
                GOAL_MAIN_CHECKOUT_SKIPPED_UNCOMMITTED_CHANGES to result.detail
              is GoalBranchCheckout.Failed -> GOAL_MAIN_CHECKOUT_FAILED to result.detail
            }
          },
          onFailure = { failure ->
            RuntimeDiagnosticsBestEffortWarning.record(
              diagnostics,
              "goal main checkout switch failed: seam=goal_worktree.main_checkout",
              failure,
            )
            GOAL_MAIN_CHECKOUT_FAILED to (failure.message ?: "main checkout switch failed")
          },
        )
      }
    return MainCheckoutRecord(outcome, detail.ifBlank { "main checkout switch failed" }.take(MAX_DETAIL_LENGTH))
  }

  private data class MainCheckoutRecord(val outcome: String, val detail: String)

  private fun recordRetained(
    parentWorkflowId: String,
    reason: String,
    detail: String,
  ) {
    manifestStore.updateControlState(parentWorkflowId) { current ->
      if (current.goalCompletedAt == null) {
        current
      } else {
        current.copy(
          executionWorktreeRemovedAt = null,
          executionWorktreeRetentionReason = reason,
          executionWorktreeRetentionDetail = detail.ifBlank { "worktree cleanup did not complete" },
          executionMainCheckoutOutcome = null,
          executionMainCheckoutDetail = null,
        )
      }
    }
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "goal worktree retained: seam=goal_worktree.settle reason=$reason detail=${detail.take(MAX_DETAIL_LENGTH)}",
    )
  }

  private companion object {
    const val MAX_DETAIL_LENGTH = 300
    const val CLEANUP_FAILED = "cleanup_failed"
  }
}
