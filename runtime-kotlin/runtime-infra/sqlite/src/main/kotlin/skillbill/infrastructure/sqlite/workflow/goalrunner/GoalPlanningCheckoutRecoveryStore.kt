package skillbill.infrastructure.sqlite.workflow.goalrunner

import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GoalPlanningCheckoutRecovery
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.workflow.goalrunner.runner.GoalRunnerControlStore
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import skillbill.workflow.model.WorkflowStatus
import java.sql.Connection
import java.sql.ResultSet

private val RECOVERY_GENERATION = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

internal fun Connection.checkoutRecovery(owner: GoalPlanningOwnership): GoalPlanningCheckoutRecovery? =
  prepareStatement(
    "SELECT generation, previous_git_identity, recovery_state, repository_identity, normalized_issue_key, " +
      "workflow_id, owned_path, recoverable_commit, ownership_state, parent_workflow_id " +
      "FROM goal_planning_checkout_recovery " +
      "WHERE reservation_id = ? AND recovery_state <> ?",
  ).use { statement ->
    statement.bindAll(owner.reservationId, GoalPlanningCheckoutRecovery.State.CONSUMED.wireValue)
    statement.executeQuery().use { rows ->
      if (!rows.next()) return null
      val generation = rows.getString("generation")
      val identity = rows.getString("previous_git_identity")
      val state = GoalPlanningCheckoutRecovery.State.fromWire(rows.getString("recovery_state").orEmpty())
      val validGeneration = generation != null && RECOVERY_GENERATION.matches(generation)
      if (!validGeneration || identity.isNullOrBlank() || state == null) {
        throw invalidWorkflowStateSchemaError(
          "Invalid checkout recovery for ${owner.ownedPath}. Preserve the recovery journal and repair the record.",
        )
      }
      if (!rows.matchesRecoveryPlan(owner) || !rows.matchesRecoveryCheckout(owner)) {
        throw invalidWorkflowStateSchemaError(
          "Checkout recovery identity changed for ${owner.ownedPath}. Preserve the journal and reconcile its owner.",
        )
      }
      GoalPlanningCheckoutRecovery(requireNotNull(generation), identity, state)
    }
  }

private fun ResultSet.matchesRecoveryPlan(owner: GoalPlanningOwnership): Boolean =
  getString("repository_identity") == owner.repositoryIdentity &&
    getString("normalized_issue_key") == owner.normalizedIssueKey &&
    getString("workflow_id") == owner.workflowId

private fun ResultSet.matchesRecoveryCheckout(owner: GoalPlanningOwnership): Boolean =
  getString("owned_path") == owner.ownedPath.toString() &&
    getString("recoverable_commit") == owner.initialCommit &&
    getString("ownership_state") == owner.state.wireValue &&
    getString("parent_workflow_id") == owner.parentWorkflowId

internal fun Connection.advanceCheckoutRecovery(
  owner: GoalPlanningOwnership,
  recovery: GoalPlanningCheckoutRecovery,
  nextState: GoalPlanningCheckoutRecovery.State,
  replacementGitIdentity: String?,
): Boolean {
  if (!RECOVERY_GENERATION.matches(recovery.generation)) return false
  val current = checkoutRecovery(owner)
  return when {
    current == null ->
      nextState == GoalPlanningCheckoutRecovery.State.FENCED &&
        recovery.state == GoalPlanningCheckoutRecovery.State.FENCED &&
        recovery.previousGitIdentity == owner.gitWorktreeIdentity && beginCheckoutRecovery(owner, recovery)
    current != recovery || recovery.previousGitIdentity != owner.gitWorktreeIdentity -> false
    !recovery.permits(nextState, replacementGitIdentity) || checkoutHasWorker(owner) -> false
    else -> commitCheckoutRecovery(owner, recovery, nextState, replacementGitIdentity)
  }
}

private fun Connection.commitCheckoutRecovery(
  owner: GoalPlanningOwnership,
  recovery: GoalPlanningCheckoutRecovery,
  nextState: GoalPlanningCheckoutRecovery.State,
  replacementGitIdentity: String?,
): Boolean {
  if (nextState == GoalPlanningCheckoutRecovery.State.CONSUMED) {
    prepareStatement(
      "UPDATE goal_planning_ownership SET git_worktree_identity = ?, updated_at = CURRENT_TIMESTAMP " +
        "WHERE reservation_id = ? AND git_worktree_identity = ?",
    ).use { statement ->
      statement.bindAll(replacementGitIdentity, owner.reservationId, recovery.previousGitIdentity)
      if (statement.executeUpdate() != 1) return false
    }
  }
  val updated =
    prepareStatement(
      "UPDATE goal_planning_checkout_recovery SET recovery_state = ?, replacement_git_identity = ? " +
        "WHERE generation = ? AND reservation_id = ? AND recovery_state = ?",
    ).use { statement ->
      statement.bindAll(
        nextState.wireValue,
        replacementGitIdentity,
        recovery.generation,
        owner.reservationId,
        recovery.state.wireValue,
      )
      statement.executeUpdate() == 1
    }
  if (!updated) {
    throw invalidWorkflowStateSchemaError(
      "Checkout recovery changed for ${owner.ownedPath}. Preserve its journal and reconcile the recorded generation.",
    )
  }
  return true
}

private fun Connection.beginCheckoutRecovery(
  owner: GoalPlanningOwnership,
  recovery: GoalPlanningCheckoutRecovery,
): Boolean {
  if (owner.state != GoalPlanningOwnership.State.INITIALIZED &&
    owner.state != GoalPlanningOwnership.State.TRANSFERRED
  ) {
    return false
  }
  if (checkoutHasWorker(owner)) return false
  val parent = owner.parentWorkflowId
  val settled =
    if (parent == null) {
      prepareStatement("SELECT workflow_status FROM feature_task_workflows WHERE workflow_id = ?").use { statement ->
        statement.bindAll(owner.workflowId)
        statement.executeQuery().use { rows -> rows.next() && rows.getString(1) == WorkflowStatus.COMPLETED.wireValue }
      }
    } else {
      val control = GoalRunnerControlStore(this).controlState(parent)
      control.goalCompletedAt != null && control.executionLease == null
    }
  if (!settled) return false
  return prepareStatement(
    "INSERT OR IGNORE INTO goal_planning_checkout_recovery " +
      "(generation, reservation_id, previous_git_identity, recovery_state, repository_identity, " +
      "normalized_issue_key, workflow_id, owned_path, recoverable_commit, ownership_state, parent_workflow_id) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
  ).use { statement ->
    statement.bindAll(
      recovery.generation,
      owner.reservationId,
      recovery.previousGitIdentity,
      recovery.state.wireValue,
      owner.repositoryIdentity,
      owner.normalizedIssueKey,
      owner.workflowId,
      owner.ownedPath.toString(),
      owner.initialCommit,
      owner.state.wireValue,
      owner.parentWorkflowId,
    )
    statement.executeUpdate() == 1
  }
}

private fun Connection.checkoutHasWorker(owner: GoalPlanningOwnership): Boolean =
  prepareStatement(
    """
    SELECT 1 FROM feature_task_runtime_worker_leases lease
    JOIN feature_task_workflows workflow ON workflow.workflow_id = lease.workflow_id
    WHERE lease.workflow_id = ? OR lease.workflow_id = ? OR workflow.parent_workflow_id = ? LIMIT 1
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(owner.workflowId, owner.parentWorkflowId, owner.parentWorkflowId)
    statement.executeQuery().use { it.next() }
  }
