package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.contracts.SharedPayloadKeys
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.core.ops.inNestedWriteTransaction
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeCrashReconciliationCandidate
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerGenerationObservation
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.featuretask.model.parseFeatureTaskRuntimeWorkerLeaseInstant
import skillbill.ports.workflow.FeatureTaskRuntimeWorkerRepository
import java.sql.Connection
import java.time.Instant

internal class FeatureTaskRuntimeWorkerStore(
  private val connection: Connection,
  private val diagnostics: RuntimeDiagnostics,
  private val transactionActive: Boolean = false,
) : FeatureTaskRuntimeWorkerRepository {
  override fun getFeatureTaskRuntimeWorkerOwnership(workflowId: String): FeatureTaskRuntimeWorkerOwnership? =
    connection.featureTaskRuntimeWorkerOwnership(workflowId, diagnostics)

  override fun getLatestFeatureTaskRuntimeWorkerGeneration(
    workflowId: String,
  ): FeatureTaskRuntimeWorkerGenerationObservation? =
    connection.prepareStatement(
      """
      SELECT generation, phase_id, phase_attempt, admitted_at
      FROM feature_task_runtime_worker_generations
      WHERE workflow_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(workflowId)
      statement.executeQuery().use { rows ->
        if (!rows.next()) {
          null
        } else {
          FeatureTaskRuntimeWorkerGenerationObservation(
            workflowId = workflowId,
            generation = rows.getLong("generation"),
            phaseId = rows.getString("phase_id"),
            phaseAttempt = rows.getInt("phase_attempt"),
            admittedAt = rows.getString("admitted_at"),
          )
        }
      }
    }

  override fun acquireFeatureTaskRuntimeWorker(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    expectedUpdatedAt: String?,
  ): Boolean =
    if (transactionActive) {
      acquireInTransaction(ownership, expectedUpdatedAt)
    } else {
      connection.inNestedWriteTransaction(diagnostics) { acquireInTransaction(ownership, expectedUpdatedAt) }
    }

  private fun acquireInTransaction(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    expectedUpdatedAt: String?,
  ): Boolean {
    val claimed =
      connection.prepareStatement(
        """
        UPDATE feature_task_workflows
        SET workflow_status = 'running', updated_at = CURRENT_TIMESTAMP
        WHERE workflow_id = ?
          AND mode = 'runtime'
          AND workflow_status NOT IN ('completed', 'failed', 'abandoned')
          AND NOT EXISTS (
            SELECT 1 FROM feature_task_runtime_worker_leases lease
            WHERE lease.workflow_id = feature_task_workflows.workflow_id
          )
          AND ((updated_at IS NULL AND ? IS NULL) OR updated_at = ?)
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(ownership.workflowId, expectedUpdatedAt, expectedUpdatedAt)
        statement.executeUpdate() == 1
      }
    if (claimed) {
      connection.insertWorkerOwnership(ownership)
      connection.recordWorkerGeneration(ownership)
    }
    return claimed
  }

  override fun reserveFeatureTaskRuntimeWorkerTakeover(
    workflowId: String,
    expectedOwnerToken: String,
    expectedGeneration: Long,
  ): Boolean =
    connection.prepareStatement(
      """
      UPDATE feature_task_runtime_worker_leases
      SET lease_state = 'takeover_reserved'
      WHERE workflow_id = ? AND owner_token = ? AND generation = ? AND lease_state = 'active'
        AND EXISTS (
          SELECT 1 FROM feature_task_workflows workflow
          WHERE workflow.workflow_id = feature_task_runtime_worker_leases.workflow_id
            AND workflow.mode = 'runtime'
            AND workflow.workflow_status NOT IN ('completed', 'failed', 'abandoned')
        )
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(workflowId, expectedOwnerToken, expectedGeneration)
      statement.executeUpdate() == 1
    }

  override fun transferFeatureTaskRuntimeWorker(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    expectedOwnerToken: String,
    expectedGeneration: Long,
  ): Boolean =
    connection.prepareStatement(
      """
      UPDATE feature_task_runtime_worker_leases SET
        contract_version = ?, generation = ?, owner_token = ?, host_identity = ?, boot_identity = ?,
        pid = ?, process_birth_token = ?, lease_state = ?, heartbeat_at = ?, expires_at = ?,
        phase_id = ?, phase_attempt = ?
      WHERE workflow_id = ? AND owner_token = ? AND generation = ? AND lease_state = 'takeover_reserved'
        AND EXISTS (
          SELECT 1 FROM feature_task_workflows workflow
          WHERE workflow.workflow_id = feature_task_runtime_worker_leases.workflow_id
            AND workflow.mode = 'runtime'
            AND workflow.workflow_status NOT IN ('completed', 'failed', 'abandoned')
        )
        AND EXISTS (
          SELECT 1 FROM feature_task_runtime_worker_generations observation
          WHERE observation.workflow_id = feature_task_runtime_worker_leases.workflow_id
            AND observation.generation = ?
        )
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        ownership.contractVersion,
        ownership.generation,
        ownership.ownerToken,
        ownership.hostIdentity,
        ownership.bootIdentity,
        ownership.pid,
        ownership.processBirthToken,
        ownership.leaseState.wireValue,
        ownership.heartbeatAt,
        ownership.expiresAt,
        ownership.phaseId,
        ownership.phaseAttempt,
        ownership.workflowId,
        expectedOwnerToken,
        expectedGeneration,
        expectedGeneration,
      )
      val transferred = statement.executeUpdate() == 1
      if (transferred) {
        if (ownership.generation == expectedGeneration) {
          connection.updateWorkerGenerationCoordinates(
            ownership.workflowId,
            ownership.generation,
            ownership.phaseId,
            ownership.phaseAttempt,
          )
        } else {
          connection.recordWorkerGeneration(ownership)
        }
      }
      transferred
    }

  override fun bindFeatureTaskRuntimeWorkerPhaseCoordinates(
    workflowId: String,
    ownerToken: String,
    generation: Long,
    phaseId: String,
    phaseAttempt: Int,
  ): Boolean =
    connection.prepareStatement(
      """
      UPDATE feature_task_runtime_worker_leases
      SET phase_id = ?, phase_attempt = ?
      WHERE workflow_id = ? AND owner_token = ? AND generation = ? AND lease_state = 'active'
        AND EXISTS (
          SELECT 1 FROM feature_task_workflows workflow
          WHERE workflow.workflow_id = feature_task_runtime_worker_leases.workflow_id
            AND workflow.mode = 'runtime'
            AND workflow.workflow_status NOT IN ('completed', 'failed', 'abandoned')
        )
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(phaseId, phaseAttempt, workflowId, ownerToken, generation)
      val bound = statement.executeUpdate() == 1
      if (bound) connection.updateWorkerGenerationCoordinates(workflowId, generation, phaseId, phaseAttempt)
      bound
    }

  override fun heartbeatFeatureTaskRuntimeWorker(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean =
    connection.featureTaskRuntimeWorkerOwnership(ownership.workflowId, diagnostics)?.let { current ->
      val proposedHeartbeat = ownership.heartbeatAtInstant
      if (ownership.heartbeatIsStale(current, proposedHeartbeat)) {
        return false
      }
      connection.prepareStatement(
        """
        UPDATE feature_task_runtime_worker_leases
        SET heartbeat_at = ?, expires_at = ?
        WHERE workflow_id = ? AND owner_token = ? AND generation = ? AND lease_state = 'active'
          AND heartbeat_at = ? AND expires_at = ?
          AND EXISTS (
            SELECT 1 FROM feature_task_workflows workflow
            WHERE workflow.workflow_id = feature_task_runtime_worker_leases.workflow_id
              AND workflow.mode = 'runtime'
              AND workflow.workflow_status NOT IN ('completed', 'failed', 'abandoned')
          )
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(
          ownership.heartbeatAt,
          ownership.expiresAt,
          ownership.workflowId,
          ownership.ownerToken,
          ownership.generation,
          current.heartbeatAt,
          current.expiresAt,
        )
        statement.executeUpdate() == 1
      }
    } ?: false

  override fun releaseFeatureTaskRuntimeWorker(
    workflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean =
    connection.prepareStatement(
      "DELETE FROM feature_task_runtime_worker_leases WHERE workflow_id = ? AND owner_token = ? AND generation = ?",
    ).use { statement ->
      statement.bindAll(workflowId, ownerToken, generation)
      statement.executeUpdate() == 1
    }

  override fun releaseFeatureTaskRuntimeWorkerIfExpired(
    workflowId: String,
    ownerToken: String,
    generation: Long,
    nowInstant: String,
  ): Boolean =
    connection.featureTaskRuntimeWorkerOwnership(workflowId, diagnostics)?.let { current ->
      val now = parseFeatureTaskRuntimeWorkerLeaseInstant(workflowId, "now", nowInstant)
      if (current.ownerToken != ownerToken || current.generation != generation || current.expiresAtInstant > now) {
        return false
      }
      connection.prepareStatement(
        """
        DELETE FROM feature_task_runtime_worker_leases
        WHERE workflow_id = ? AND owner_token = ? AND generation = ? AND lease_state = ?
          AND heartbeat_at = ? AND expires_at = ?
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(
          workflowId,
          ownerToken,
          generation,
          current.leaseState.wireValue,
          current.heartbeatAt,
          current.expiresAt,
        )
        statement.executeUpdate() == 1
      }
    } ?: false

  override fun findFeatureTaskRuntimeCrashReconciliationCandidates(
    nowInstant: String,
  ): List<FeatureTaskRuntimeCrashReconciliationCandidate> =
    connection.prepareStatement(
      """
      SELECT workflows.workflow_id, workflows.current_step_id, workflows.workflow_status
      FROM feature_task_workflows AS workflows
      JOIN feature_task_runtime_worker_leases AS lease
        ON lease.workflow_id = workflows.workflow_id
      WHERE workflows.mode = 'runtime'
        AND workflows.workflow_status IN ('running', 'blocked')
        AND lease.lease_state IN ('active', 'takeover_reserved')
      ORDER BY workflows.workflow_id
      """.trimIndent(),
    ).use { statement ->
      val now = parseFeatureTaskRuntimeWorkerLeaseInstant("crash-reconciliation", "now", nowInstant)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            val workflowId = rows.getString(SharedPayloadKeys.WORKFLOW_ID)
            val ownership = connection.featureTaskRuntimeWorkerOwnership(workflowId, diagnostics) ?: continue
            if (ownership.expiresAtInstant <= now) {
              add(
                FeatureTaskRuntimeCrashReconciliationCandidate(
                  ownership = ownership,
                  currentStepId = rows.getString("current_step_id"),
                  workflowStatus = rows.getString("workflow_status"),
                ),
              )
            }
          }
        }
      }
    }

  override fun reconcileFeatureTaskRuntimeCrashedWorker(
    workflowId: String,
    ownerToken: String,
    generation: Long,
    interruptionReason: String,
    nowInstant: String,
  ): Boolean =
    if (transactionActive) {
      reconcileFeatureTaskRuntimeCrashedWorkerInTransaction(
        workflowId,
        ownerToken,
        generation,
        interruptionReason,
        nowInstant,
      )
    } else {
      connection.inNestedWriteTransaction(diagnostics) {
        reconcileFeatureTaskRuntimeCrashedWorkerInTransaction(
          workflowId,
          ownerToken,
          generation,
          interruptionReason,
          nowInstant,
        )
      }
    }

  private fun reconcileFeatureTaskRuntimeCrashedWorkerInTransaction(
    workflowId: String,
    ownerToken: String,
    generation: Long,
    interruptionReason: String,
    nowInstant: String,
  ): Boolean {
    val leaseReleased =
      connection.prepareStatement(
        """
        DELETE FROM feature_task_runtime_worker_leases
        WHERE workflow_id = ? AND owner_token = ? AND generation = ?
          AND lease_state = ?
          AND heartbeat_at = ? AND expires_at = ?
          AND EXISTS (
            SELECT 1 FROM feature_task_workflows workflow
            WHERE workflow.workflow_id = feature_task_runtime_worker_leases.workflow_id
              AND workflow.mode = 'runtime' AND workflow.workflow_status IN ('running', 'blocked')
          )
        """.trimIndent(),
      ).use { statement ->
        val current = connection.featureTaskRuntimeWorkerOwnership(workflowId, diagnostics) ?: return false
        val now = parseFeatureTaskRuntimeWorkerLeaseInstant(workflowId, "now", nowInstant)
        if (!current.matchesExpiredActiveOwner(ownerToken, generation, now)) {
          return false
        }
        statement.bindAll(
          workflowId,
          ownerToken,
          generation,
          FeatureTaskRuntimeWorkerLeaseState.ACTIVE.wireValue,
          current.heartbeatAt,
          current.expiresAt,
        )
        statement.executeUpdate() == 1
      }
    if (!leaseReleased) return false

    val workflowUpdated =
      connection.prepareStatement(
        """
        UPDATE feature_task_workflows
        SET workflow_status = CASE WHEN workflow_status = 'running' THEN 'pending' ELSE workflow_status END,
          interruption_reason = ?, updated_at = CURRENT_TIMESTAMP
        WHERE workflow_id = ? AND mode = 'runtime' AND workflow_status IN ('running', 'blocked')
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(interruptionReason, workflowId)
        statement.executeUpdate() == 1
      }
    if (!workflowUpdated) {
      error("Crashed worker workflow '$workflowId' changed during lease reconciliation.")
    }
    return true
  }
}

private fun Connection.recordWorkerGeneration(ownership: FeatureTaskRuntimeWorkerOwnership) {
  prepareStatement(
    """
    INSERT INTO feature_task_runtime_worker_generations
      (workflow_id, generation, phase_id, phase_attempt, admitted_at)
    VALUES (?, ?, ?, ?, ?)
    ON CONFLICT(workflow_id) DO UPDATE SET generation = excluded.generation,
      phase_id = excluded.phase_id, phase_attempt = excluded.phase_attempt, admitted_at = excluded.admitted_at
    WHERE feature_task_runtime_worker_generations.generation < excluded.generation
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      ownership.workflowId,
      ownership.generation,
      ownership.phaseId,
      ownership.phaseAttempt,
      ownership.heartbeatAt,
    )
    check(statement.executeUpdate() == 1)
  }
}

private fun Connection.updateWorkerGenerationCoordinates(
  workflowId: String,
  generation: Long,
  phaseId: String,
  phaseAttempt: Int,
) {
  prepareStatement(
    """
    UPDATE feature_task_runtime_worker_generations
    SET phase_id = ?, phase_attempt = ?
    WHERE workflow_id = ? AND generation = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(phaseId, phaseAttempt, workflowId, generation)
    check(statement.executeUpdate() == 1)
  }
}

private fun FeatureTaskRuntimeWorkerOwnership.matchesExpiredActiveOwner(
  ownerToken: String,
  generation: Long,
  now: Instant,
): Boolean = matchesActiveOwner(ownerToken, generation) && !expiresAtInstant.isAfter(now)

private fun FeatureTaskRuntimeWorkerOwnership.matchesActiveOwner(
  ownerToken: String,
  generation: Long,
): Boolean =
  this.ownerToken == ownerToken &&
    this.generation == generation &&
    leaseState == FeatureTaskRuntimeWorkerLeaseState.ACTIVE

private fun FeatureTaskRuntimeWorkerOwnership.heartbeatIsStale(
  current: FeatureTaskRuntimeWorkerOwnership,
  proposedHeartbeat: Instant,
): Boolean {
  val identityMismatch = heartbeatIdentityMismatch(current)
  val expiryStale = expiresAtInstant <= proposedHeartbeat
  val orderingStale = heartbeatOrderingStale(current)
  return identityMismatch || expiryStale || orderingStale
}

private fun FeatureTaskRuntimeWorkerOwnership.heartbeatIdentityMismatch(
  current: FeatureTaskRuntimeWorkerOwnership,
): Boolean =
  current.ownerToken != ownerToken ||
    current.generation != generation ||
    current.leaseState != FeatureTaskRuntimeWorkerLeaseState.ACTIVE

private fun FeatureTaskRuntimeWorkerOwnership.heartbeatOrderingStale(
  current: FeatureTaskRuntimeWorkerOwnership,
): Boolean = heartbeatAtInstant < current.heartbeatAtInstant || expiresAtInstant <= current.expiresAtInstant
