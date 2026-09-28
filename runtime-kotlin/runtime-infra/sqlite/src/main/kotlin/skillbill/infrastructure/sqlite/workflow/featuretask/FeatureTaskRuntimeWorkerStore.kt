package skillbill.infrastructure.sqlite.workflow.featuretask
import skillbill.contracts.SharedPayloadKeys
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.core.ops.inNestedWriteTransaction
import skillbill.ports.featuretask.model.FeatureTaskRuntimeCrashReconciliationCandidate
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.workflow.FeatureTaskRuntimeWorkerRepository
import java.sql.Connection

internal class FeatureTaskRuntimeWorkerStore(
  private val connection: Connection,
) : FeatureTaskRuntimeWorkerRepository {
  override fun getFeatureTaskRuntimeWorkerOwnership(workflowId: String): FeatureTaskRuntimeWorkerOwnership? =
    connection.featureTaskRuntimeWorkerOwnership(workflowId)

  override fun acquireFeatureTaskRuntimeWorker(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    expectedUpdatedAt: String?,
  ): Boolean =
    connection.inNestedWriteTransaction {
      val claimed =
        prepareStatement(
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
      if (claimed) insertWorkerOwnership(ownership)
      claimed
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
      )
      statement.executeUpdate() == 1
    }

  override fun heartbeatFeatureTaskRuntimeWorker(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean =
    connection.prepareStatement(
      """
      UPDATE feature_task_runtime_worker_leases
      SET heartbeat_at = ?, expires_at = ?, phase_id = ?, phase_attempt = ?
      WHERE workflow_id = ? AND owner_token = ? AND generation = ? AND lease_state = 'active'
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        ownership.heartbeatAt,
        ownership.expiresAt,
        ownership.phaseId,
        ownership.phaseAttempt,
        ownership.workflowId,
        ownership.ownerToken,
        ownership.generation,
      )
      statement.executeUpdate() == 1
    }

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
    connection.prepareStatement(
      """
      DELETE FROM feature_task_runtime_worker_leases
      WHERE workflow_id = ? AND owner_token = ? AND generation = ? AND expires_at <= ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(workflowId, ownerToken, generation, nowInstant)
      statement.executeUpdate() == 1
    }

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
        AND workflows.workflow_status = 'running'
        AND lease.lease_state = 'active'
        AND lease.expires_at < ?
      ORDER BY workflows.workflow_id
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(nowInstant)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            val workflowId = rows.getString(SharedPayloadKeys.WORKFLOW_ID)
            val ownership = connection.featureTaskRuntimeWorkerOwnership(workflowId) ?: continue
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

  override fun reconcileFeatureTaskRuntimeCrashedWorker(
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
        WHERE workflow_id = ? AND owner_token = ? AND generation = ? AND expires_at < ?
          AND lease_state = 'active'
          AND EXISTS (
            SELECT 1 FROM feature_task_workflows workflow
            WHERE workflow.workflow_id = feature_task_runtime_worker_leases.workflow_id
              AND workflow.mode = 'runtime' AND workflow.workflow_status = 'running'
          )
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(workflowId, ownerToken, generation, nowInstant)
        statement.executeUpdate() == 1
      }
    if (!leaseReleased) return false

    return connection.prepareStatement(
      """
      UPDATE feature_task_workflows
      SET workflow_status = 'pending', interruption_reason = ?, updated_at = CURRENT_TIMESTAMP
      WHERE workflow_id = ? AND mode = 'runtime' AND workflow_status = 'running'
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(interruptionReason, workflowId)
      statement.executeUpdate() == 1
    }
  }
}
