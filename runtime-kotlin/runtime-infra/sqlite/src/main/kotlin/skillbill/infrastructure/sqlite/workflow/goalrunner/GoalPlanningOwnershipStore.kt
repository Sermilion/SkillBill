package skillbill.infrastructure.sqlite.workflow.goalrunner

import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GoalPlanningCheckoutRecovery
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.goalrunner.GOAL_PLANNING_OWNERSHIP_CONTRACT_VERSION
import skillbill.ports.goalrunner.GoalPlanningOwnershipRepository
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import skillbill.ports.goalrunner.model.GoalPlanningOwnershipWriteResult
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet

internal class GoalPlanningOwnershipStore(
  private val connection: Connection,
) : GoalPlanningOwnershipRepository {
  private companion object {
    const val SELECT_OWNERSHIP =
      "SELECT contract_version, reservation_id, repository_identity, normalized_issue_key, workflow_id, " +
        "owned_path, initial_commit, ownership_state, parent_workflow_id, git_worktree_identity, " +
        "legacy_bundle_pending " +
        "FROM goal_planning_ownership"
  }

  override fun checkoutRecovery(ownership: GoalPlanningOwnership): GoalPlanningCheckoutRecovery? =
    connection.checkoutRecovery(ownership)

  override fun isConsumedCheckoutRecovery(
    ownership: GoalPlanningOwnership,
    generation: String,
  ): Boolean =
    connection.prepareStatement(
      "SELECT 1 FROM goal_planning_checkout_recovery WHERE reservation_id = ? AND generation = ? " +
        "AND recovery_state = ? AND replacement_git_identity = ?",
    ).use { statement ->
      statement.bindAll(
        ownership.reservationId,
        generation,
        GoalPlanningCheckoutRecovery.State.CONSUMED.wireValue,
        ownership.gitWorktreeIdentity,
      )
      statement.executeQuery().use { it.next() }
    }

  override fun advanceCheckoutRecovery(
    ownership: GoalPlanningOwnership,
    recovery: GoalPlanningCheckoutRecovery,
    nextState: GoalPlanningCheckoutRecovery.State,
    replacementGitIdentity: String?,
  ): Boolean {
    if (findByWorkflow(ownership.workflowId) != ownership) return false
    return connection.advanceCheckoutRecovery(ownership, recovery, nextState, replacementGitIdentity)
  }

  override fun reserve(ownership: GoalPlanningOwnership): GoalPlanningOwnershipWriteResult {
    require(ownership.ownedPath.isAbsolute)
    require(ownership.state == GoalPlanningOwnership.State.RESERVED)
    val existing = findByReservation(ownership.reservationId)
    if (existing != null) {
      return if (existing == ownership) {
        GoalPlanningOwnershipWriteResult.Stored(existing)
      } else {
        GoalPlanningOwnershipWriteResult.Conflict(existing)
      }
    }
    val inserted =
      connection.prepareStatement(
        """
        INSERT OR IGNORE INTO goal_planning_ownership
          (reservation_id, contract_version, repository_identity, normalized_issue_key, workflow_id,
           owned_path, initial_commit, ownership_state, legacy_bundle_pending)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(
          ownership.reservationId,
          GOAL_PLANNING_OWNERSHIP_CONTRACT_VERSION,
          ownership.repositoryIdentity,
          ownership.normalizedIssueKey,
          ownership.workflowId,
          ownership.ownedPath.toString(),
          ownership.initialCommit,
          ownership.state.wireValue,
          ownership.legacyBundlePending?.let { if (it) 1 else 0 },
        )
        statement.executeUpdate()
      }
    return if (inserted == 1) {
      GoalPlanningOwnershipWriteResult.Stored(ownership)
    } else {
      GoalPlanningOwnershipWriteResult.Conflict(
        findByReservation(ownership.reservationId) ?: findActive(
          ownership.repositoryIdentity,
          ownership.normalizedIssueKey,
        ),
      )
    }
  }

  override fun markInitialized(
    reservationId: String,
    path: Path,
    gitWorktreeIdentity: String,
  ): GoalPlanningOwnershipWriteResult {
    require(Path.of(gitWorktreeIdentity).isAbsolute)
    val updated =
      connection.prepareStatement(
        """
        UPDATE goal_planning_ownership
        SET ownership_state = ?, git_worktree_identity = ?, updated_at = CURRENT_TIMESTAMP
        WHERE reservation_id = ? AND owned_path = ? AND ownership_state = ?
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(
          GoalPlanningOwnership.State.INITIALIZED.wireValue,
          gitWorktreeIdentity,
          reservationId,
          path.toString(),
          GoalPlanningOwnership.State.RESERVED.wireValue,
        )
        statement.executeUpdate()
      }
    val current = findByReservation(reservationId)
    return when {
      updated == 1 -> GoalPlanningOwnershipWriteResult.Stored(requireNotNull(current))
      current?.ownedPath == path && current.state == GoalPlanningOwnership.State.INITIALIZED &&
        current.gitWorktreeIdentity == gitWorktreeIdentity ->
        GoalPlanningOwnershipWriteResult.Stored(current)
      current != null -> GoalPlanningOwnershipWriteResult.Conflict(current)
      else -> GoalPlanningOwnershipWriteResult.Missing(path)
    }
  }

  override fun transfer(
    reservationId: String,
    parentWorkflowId: String,
    path: Path,
  ): GoalPlanningOwnershipWriteResult {
    val owner = findByReservation(reservationId)
    if (owner != null && checkoutRecovery(owner) != null) {
      return GoalPlanningOwnershipWriteResult.Conflict(owner)
    }
    val updated =
      connection.prepareStatement(
        """
        UPDATE goal_planning_ownership
        SET ownership_state = ?, parent_workflow_id = ?, updated_at = CURRENT_TIMESTAMP
        WHERE reservation_id = ? AND owned_path = ? AND ownership_state = ?
          AND NOT EXISTS (
            SELECT 1 FROM goal_planning_checkout_recovery recovery
            WHERE recovery.reservation_id = goal_planning_ownership.reservation_id
              AND recovery.recovery_state <> '${GoalPlanningCheckoutRecovery.State.CONSUMED.wireValue}'
          )
          AND NOT EXISTS (
            SELECT 1 FROM feature_task_runtime_worker_leases lease
            WHERE lease.workflow_id = goal_planning_ownership.workflow_id
          )
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(
          GoalPlanningOwnership.State.TRANSFERRED.wireValue,
          parentWorkflowId,
          reservationId,
          path.toString(),
          GoalPlanningOwnership.State.INITIALIZED.wireValue,
        )
        statement.executeUpdate()
      }
    val current = findByReservation(reservationId)
    return when {
      updated == 1 -> GoalPlanningOwnershipWriteResult.Stored(requireNotNull(current))
      current?.ownedPath == path && current.state == GoalPlanningOwnership.State.TRANSFERRED &&
        current.parentWorkflowId == parentWorkflowId -> GoalPlanningOwnershipWriteResult.Stored(current)
      current != null -> GoalPlanningOwnershipWriteResult.Conflict(current)
      else -> GoalPlanningOwnershipWriteResult.Missing(path)
    }
  }

  override fun supersede(
    reservationId: String,
    workflowId: String,
    path: Path,
  ): GoalPlanningOwnershipWriteResult {
    val owner = findByReservation(reservationId)
    if (owner != null && checkoutRecovery(owner) != null) {
      return GoalPlanningOwnershipWriteResult.Conflict(owner)
    }
    val updated =
      connection.prepareStatement(
        """
        UPDATE goal_planning_ownership
        SET ownership_state = ?, updated_at = CURRENT_TIMESTAMP
        WHERE reservation_id = ? AND workflow_id = ? AND owned_path = ?
          AND NOT EXISTS (
            SELECT 1 FROM goal_planning_checkout_recovery recovery
            WHERE recovery.reservation_id = goal_planning_ownership.reservation_id
              AND recovery.recovery_state <> '${GoalPlanningCheckoutRecovery.State.CONSUMED.wireValue}'
          )
          AND ownership_state IN (?, ?)
          AND NOT EXISTS (
            SELECT 1 FROM feature_task_runtime_worker_leases WHERE workflow_id = ?
          )
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(
          GoalPlanningOwnership.State.SUPERSEDED.wireValue,
          reservationId,
          workflowId,
          path.toString(),
          GoalPlanningOwnership.State.RESERVED.wireValue,
          GoalPlanningOwnership.State.INITIALIZED.wireValue,
          workflowId,
        )
        statement.executeUpdate()
      }
    val current = findByReservation(reservationId)
    return when {
      updated == 1 -> GoalPlanningOwnershipWriteResult.Stored(requireNotNull(current))
      current?.workflowId == workflowId && current.ownedPath == path &&
        current.state == GoalPlanningOwnership.State.SUPERSEDED -> GoalPlanningOwnershipWriteResult.Stored(current)
      current != null -> GoalPlanningOwnershipWriteResult.Conflict(current)
      else -> GoalPlanningOwnershipWriteResult.Missing(path)
    }
  }

  override fun reactivate(
    reservationId: String,
    workflowId: String,
    path: Path,
  ): GoalPlanningOwnershipWriteResult {
    val owner = findByReservation(reservationId)
    if (owner != null && checkoutRecovery(owner) != null) {
      return GoalPlanningOwnershipWriteResult.Conflict(owner)
    }
    val updated =
      connection.prepareStatement(
        """
        UPDATE goal_planning_ownership
        SET ownership_state = ?, updated_at = CURRENT_TIMESTAMP
        WHERE reservation_id = ? AND workflow_id = ? AND owned_path = ? AND ownership_state = ?
          AND NOT EXISTS (
            SELECT 1 FROM goal_planning_checkout_recovery recovery
            WHERE recovery.reservation_id = goal_planning_ownership.reservation_id
              AND recovery.recovery_state <> '${GoalPlanningCheckoutRecovery.State.CONSUMED.wireValue}'
          )
          AND NOT EXISTS (
            SELECT 1 FROM goal_planning_ownership active
            WHERE active.reservation_id <> goal_planning_ownership.reservation_id
              AND active.ownership_state NOT IN (?, ?)
              AND (active.repository_identity = goal_planning_ownership.repository_identity
                AND active.normalized_issue_key = goal_planning_ownership.normalized_issue_key
                OR active.owned_path = goal_planning_ownership.owned_path)
          )
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(
          GoalPlanningOwnership.State.INITIALIZED.wireValue,
          reservationId,
          workflowId,
          path.toString(),
          GoalPlanningOwnership.State.SUPERSEDED.wireValue,
          GoalPlanningOwnership.State.TRANSFERRED.wireValue,
          GoalPlanningOwnership.State.SUPERSEDED.wireValue,
        )
        statement.executeUpdate()
      }
    val current = findByReservation(reservationId)
    return when {
      updated == 1 -> GoalPlanningOwnershipWriteResult.Stored(requireNotNull(current))
      current?.workflowId == workflowId && current.ownedPath == path &&
        current.state == GoalPlanningOwnership.State.INITIALIZED -> GoalPlanningOwnershipWriteResult.Stored(current)
      current != null -> GoalPlanningOwnershipWriteResult.Conflict(current)
      else -> GoalPlanningOwnershipWriteResult.Missing(path)
    }
  }

  override fun findByWorkflow(workflowId: String): GoalPlanningOwnership? =
    connection.prepareStatement(
      "$SELECT_OWNERSHIP WHERE workflow_id = ?",
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.executeQuery().use { rows -> if (rows.next()) rows.ownership() else null }
    }

  override fun markLegacyBundlePreserved(
    workflowId: String,
    path: Path,
  ): Boolean {
    val updated =
      connection.prepareStatement(
        """
        UPDATE goal_planning_ownership
        SET legacy_bundle_pending = 0, updated_at = CURRENT_TIMESTAMP
        WHERE workflow_id = ? AND owned_path = ? AND
          (legacy_bundle_pending = 1 OR legacy_bundle_pending IS NULL)
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(workflowId, path.toString())
        statement.executeUpdate()
      }
    return updated == 1 ||
      findByWorkflow(workflowId)?.let { it.ownedPath == path && it.legacyBundlePending == false } == true
  }

  override fun findByIssue(
    repositoryIdentity: String,
    normalizedIssueKey: String,
  ): GoalPlanningOwnership? =
    connection.prepareStatement(
      "$SELECT_OWNERSHIP " +
        "WHERE repository_identity = ? AND normalized_issue_key = ? " +
        "ORDER BY CASE WHEN ownership_state IN (?, ?) THEN 1 ELSE 0 END, updated_at DESC, rowid DESC LIMIT 1",
    ).use { statement ->
      statement.bindAll(
        repositoryIdentity,
        normalizedIssueKey,
        GoalPlanningOwnership.State.TRANSFERRED.wireValue,
        GoalPlanningOwnership.State.SUPERSEDED.wireValue,
      )
      statement.executeQuery().use { rows -> if (rows.next()) rows.ownership() else null }
    }

  private fun findByReservation(reservationId: String): GoalPlanningOwnership? =
    connection.prepareStatement(
      "$SELECT_OWNERSHIP WHERE reservation_id = ?",
    ).use { statement ->
      statement.setString(1, reservationId)
      statement.executeQuery().use { rows -> if (rows.next()) rows.ownership() else null }
    }

  private fun findActive(
    repositoryIdentity: String,
    issueKey: String,
  ): GoalPlanningOwnership? =
    connection.prepareStatement(
      "$SELECT_OWNERSHIP " +
        "WHERE repository_identity = ? AND normalized_issue_key = ? AND ownership_state NOT IN (?, ?)",
    ).use { statement ->
      statement.bindAll(
        repositoryIdentity,
        issueKey,
        GoalPlanningOwnership.State.TRANSFERRED.wireValue,
        GoalPlanningOwnership.State.SUPERSEDED.wireValue,
      )
      statement.executeQuery().use { rows -> if (rows.next()) rows.ownership() else null }
    }

  private fun ResultSet.ownership(): GoalPlanningOwnership {
    val rawPath = getString("owned_path")
    if (rawPath.isNullOrBlank()) throw ownershipSchemaError(rawPath, "missing path")
    validateOwnershipVersion(getString("contract_version"), rawPath)
    val state =
      GoalPlanningOwnership.State.entries.firstOrNull { it.wireValue == getString("ownership_state") }
        ?: throw ownershipSchemaError(rawPath, "unsupported state")
    val path = absoluteOwnershipPath(rawPath, rawPath, "path")
    val repositoryIdentity = requiredOwnershipString("repository_identity", rawPath)
    val normalizedIssueKey = requiredOwnershipString("normalized_issue_key", rawPath)
    val workflowId = requiredOwnershipString("workflow_id", rawPath)
    val reservationId = requiredOwnershipString("reservation_id", rawPath)
    val initialCommit = requiredOwnershipString("initial_commit", rawPath)
    val parentWorkflowId = getString("parent_workflow_id")
    val gitWorktreeIdentity =
      getString("git_worktree_identity")?.let {
        absoluteOwnershipPath(it, rawPath, "Git worktree identity").normalize().toString()
      }
    validateTransferState(state, parentWorkflowId, rawPath)
    return GoalPlanningOwnership(
      repositoryIdentity = repositoryIdentity,
      normalizedIssueKey = normalizedIssueKey,
      workflowId = workflowId,
      reservationId = reservationId,
      ownedPath = path,
      initialCommit = initialCommit,
      state = state,
      parentWorkflowId = parentWorkflowId,
      gitWorktreeIdentity = gitWorktreeIdentity,
      legacyBundlePending = getInt("legacy_bundle_pending").let { if (wasNull()) null else it == 1 },
    )
  }

  private fun ResultSet.requiredOwnershipString(
    column: String,
    path: String,
  ): String = getString(column) ?: throw ownershipSchemaError(path, "missing $column")

  private fun validateOwnershipVersion(
    version: String?,
    path: String,
  ) {
    if (version != GOAL_PLANNING_OWNERSHIP_CONTRACT_VERSION) {
      throw ownershipSchemaError(path, "unsupported contract version")
    }
  }

  private fun validateTransferState(
    state: GoalPlanningOwnership.State,
    parentWorkflowId: String?,
    path: String,
  ) {
    if ((state == GoalPlanningOwnership.State.TRANSFERRED) != (parentWorkflowId != null)) {
      throw ownershipSchemaError(path, "inconsistent transfer state")
    }
  }

  private fun absoluteOwnershipPath(
    value: String,
    rawPath: String,
    label: String,
  ): Path {
    val path =
      try {
        Path.of(value)
      } catch (failure: InvalidPathException) {
        throw ownershipSchemaError(rawPath, "invalid $label", failure)
      }
    if (!path.isAbsolute) throw ownershipSchemaError(rawPath, "$label is not absolute")
    return path
  }

  private fun ownershipSchemaError(
    path: String?,
    reason: String,
    cause: Throwable? = null,
  ) = invalidWorkflowStateSchemaError(
    "Goal planning ownership record for path '${path ?: "<missing>"}' has $reason. Preserve the row and " +
      "inspect or repair the runtime database before retrying.",
    cause,
  )
}
