package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_RUNTIME_WORKER_OWNERSHIP_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.workflow.MINIMUM_OWNER_TOKEN_LENGTH
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

internal object FeatureTaskWorkflowStateStoreSql

internal fun Connection.insertWorkerOwnership(ownership: FeatureTaskRuntimeWorkerOwnership) {
  prepareStatement(
    """
    INSERT INTO feature_task_runtime_worker_leases (
      workflow_id, contract_version, generation, owner_token, host_identity, boot_identity, pid,
      process_birth_token, lease_state, heartbeat_at, expires_at, phase_id, phase_attempt
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.trimIndent(),
  ).use { statement ->
    statement.bindOwnership(ownership, includeWorkflowId = true)
    statement.executeUpdate()
  }
}

internal fun PreparedStatement.bindOwnership(
  ownership: FeatureTaskRuntimeWorkerOwnership,
  includeWorkflowId: Boolean,
): Int {
  val values =
    buildList<Any?> {
      if (includeWorkflowId) add(ownership.workflowId)
      add(ownership.contractVersion)
      add(ownership.generation)
      add(ownership.ownerToken)
      add(ownership.hostIdentity)
      add(ownership.bootIdentity)
      add(ownership.pid)
      add(ownership.processBirthToken)
      add(ownership.leaseState.wireValue)
      add(ownership.heartbeatAt)
      add(ownership.expiresAt)
      add(ownership.phaseId)
      add(ownership.phaseAttempt)
    }
  bindAll(values)
  return values.size + 1
}

internal fun Connection.featureTaskRuntimeWorkerOwnership(
  workflowId: String,
  diagnostics: RuntimeDiagnostics,
): FeatureTaskRuntimeWorkerOwnership? =
  prepareStatement("SELECT * FROM feature_task_runtime_worker_leases WHERE workflow_id = ?").use { statement ->
    statement.bindAll(workflowId)
    statement.executeQuery().use { row ->
      if (!row.next()) return null
      val heartbeatAt = row.requiredWorkerOwnershipString(workflowId, "heartbeat_at")
      val expiresAt = row.requiredWorkerOwnershipString(workflowId, "expires_at")
      parseWorkerLeaseInstant(workflowId, "heartbeat_at", heartbeatAt, diagnostics)
      parseWorkerLeaseInstant(workflowId, "expires_at", expiresAt, diagnostics)
      FeatureTaskRuntimeWorkerOwnership(
        workflowId = row.requiredWorkerOwnershipString(workflowId, "workflow_id"),
        contractVersion = row.requiredWorkerOwnershipString(workflowId, "contract_version"),
        generation = row.getLong("generation"),
        ownerToken = row.requiredWorkerOwnershipString(workflowId, "owner_token"),
        hostIdentity = row.requiredWorkerOwnershipString(workflowId, "host_identity"),
        bootIdentity = row.requiredWorkerOwnershipString(workflowId, "boot_identity"),
        pid = row.getLong("pid"),
        processBirthToken = row.requiredWorkerOwnershipString(workflowId, "process_birth_token"),
        leaseState =
          decodeWorkerLeaseState(
            workflowId,
            row.requiredWorkerOwnershipString(workflowId, "lease_state"),
          ),
        heartbeatAt = heartbeatAt,
        expiresAt = expiresAt,
        phaseId = row.requiredWorkerOwnershipString(workflowId, "phase_id"),
        phaseAttempt = row.getInt("phase_attempt"),
      ).also(::validateWorkerOwnership)
    }
  }

internal fun ResultSet.requiredWorkerOwnershipString(
  workflowId: String,
  column: String,
): String =
  getString(column) ?: throw InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError(
    workflowId,
    "$column is required",
  )

internal fun decodeWorkerLeaseState(
  workflowId: String,
  value: String,
): FeatureTaskRuntimeWorkerLeaseState =
  FeatureTaskRuntimeWorkerLeaseState.fromWire(value)
    ?: throw InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError(
      workflowId,
      "lease_state '$value' is not supported",
    )

internal fun validateWorkerOwnership(ownership: FeatureTaskRuntimeWorkerOwnership) {
  val heartbeatAt = ownership.heartbeatAtInstant
  val expiresAt = ownership.expiresAtInstant
  val failure =
    when {
      ownership.contractVersion != FEATURE_TASK_RUNTIME_WORKER_OWNERSHIP_CONTRACT_VERSION ->
        "unsupported contract_version '${ownership.contractVersion}'"
      ownership.generation < 1 -> "generation must be positive"
      ownership.ownerToken.length < MINIMUM_OWNER_TOKEN_LENGTH ->
        "owner_token must contain at least $MINIMUM_OWNER_TOKEN_LENGTH characters"
      ownership.hostIdentity.isBlank() || ownership.bootIdentity.isBlank() -> "host and boot identity are required"
      ownership.pid < 1 || ownership.processBirthToken.isBlank() -> "exact process identity is required"
      ownership.phaseId.isBlank() || ownership.phaseAttempt < 1 -> "phase coordinates are invalid"
      !expiresAt.isAfter(heartbeatAt) -> "expires_at must be later than heartbeat_at"
      else -> null
    }
  failure?.let { throw InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError(ownership.workflowId, it) }
}

internal fun Connection.featureTaskIdentity(workflowId: String): FeatureTaskExecutionIdentity? =
  prepareStatement(
    """
    SELECT contract_version, normalized_issue_key, repository_identity, governed_spec_path, mode, route_scope
    FROM feature_task_execution_identities WHERE workflow_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(workflowId)
    statement.executeQuery().use { row ->
      if (!row.next()) return null
      FeatureTaskExecutionIdentity(
        workflowId = workflowId,
        contractVersion = row.getString(SharedPayloadKeys.CONTRACT_VERSION),
        normalizedIssueKey = row.getString("normalized_issue_key"),
        repositoryIdentity = row.getString("repository_identity"),
        governedSpecPath = row.getString("governed_spec_path"),
        mode = decodeIdentityMode(workflowId, row.getString("mode")),
        routeScope = decodeIdentityRouteScope(workflowId, row.getString("route_scope")),
      )
    }
  }

internal fun decodeIdentityMode(
  workflowId: String,
  value: String,
): FeatureTaskWorkflowMode =
  FeatureTaskWorkflowMode.entries.singleOrNull { it.wireValue == value }
    ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "mode '$value' is not supported")

internal fun decodeIdentityRouteScope(
  workflowId: String,
  value: String,
): FeatureTaskRouteScope =
  FeatureTaskRouteScope.entries.singleOrNull { it.wireValue == value }
    ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "route_scope '$value' is not supported")
