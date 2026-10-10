package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.core.ops.inNestedWriteTransaction
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.StandalonePhaseStatusRepository
import skillbill.ports.idestatus.model.IdeStatusWorkflowExecution
import skillbill.ports.idestatus.model.IdeStatusWorkflowRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class StandalonePhaseStatusStore(
  private val connection: Connection,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val transactionActive: Boolean,
) : StandalonePhaseStatusRepository {
  override fun register(request: StandalonePhaseStatusRegistration): StandalonePhaseStatusRecord =
    connection.inNestedWriteTransaction(diagnostics, transactionActive) {
      registerInTransaction(request)
    }

  override fun registerWorkflow(request: IdeStatusWorkflowRegistration): IdeStatusWorkflowExecution =
    connection.inNestedWriteTransaction(diagnostics, transactionActive) {
      registerWorkflowInTransaction(request)
    }

  override fun latestWorkflowExecution(workflowId: String): IdeStatusWorkflowExecution? =
    readWorkflowExecution(workflowId)

  private fun registerInTransaction(request: StandalonePhaseStatusRegistration): StandalonePhaseStatusRecord {
    readByInvocation(request.invocationId)?.let { return it }
    if (request.phaseId == MONITOR_PHASE_ID) {
      supersedeActiveMonitors(
        repositoryIdentity = request.repositoryIdentity,
        branchCorrelation = request.branchCorrelation,
        now = request.startedAt,
      )
    }
    val storeId = ensureStore(request.repositoryIdentity)
    val sequence = allocateSequence(request.repositoryIdentity)
    connection.prepareStatement(
      """
      INSERT INTO standalone_phase_status(
        execution_id, repository_identity, branch_correlation, issue_key, workflow_id,
        invocation_id, phase_id, status_store_id, run_sequence, status_revision,
        lifecycle_state, current_step, started_at, updated_at, lease_owner,
        lease_generation, lease_expires_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '1', ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        listOf(
          request.executionId,
          request.repositoryIdentity,
          request.branchCorrelation,
          request.issueKey,
          request.workflowId,
          request.invocationId,
          request.phaseId,
          storeId,
          sequence,
          request.lifecycleState,
          request.currentStep,
          request.startedAt.toString(),
          request.startedAt.toString(),
          request.leaseOwner,
          request.leaseGeneration,
          request.storedLeaseExpiresAt().toString(),
        ),
      )
      statement.executeUpdate()
    }
    return readByExecution(request.executionId) ?: error("Standalone status registration was not persisted.")
  }

  override fun readEligible(
    repositoryIdentity: String,
    branchCorrelation: String,
    now: Instant,
  ): List<StandalonePhaseStatusRecord> {
    val records =
      connection.prepareStatement(
        "SELECT * FROM standalone_phase_status WHERE repository_identity = ? AND branch_correlation = ?",
      ).use { statement ->
        statement.bindAll(listOf(repositoryIdentity, branchCorrelation))
        statement.executeQuery().use { rows ->
          buildList { while (rows.next()) add(rows.toRecord()) }
        }
      }
    return records
      .filter {
        val age = Duration.between(it.updatedAt, now)
        (it.lifecycleState in LIVE_STATES && age <= LIVE_RETENTION) ||
          (it.finishedAt?.plus(SETTLED_RETENTION)?.isAfter(now) == true)
      }
      .sortedWith(
        compareByDescending<StandalonePhaseStatusRecord> { it.runSequence.length }
          .thenByDescending { it.runSequence },
      )
  }

  override fun update(request: StandalonePhaseStatusUpdate): StandalonePhaseStatusUpdateResult =
    connection.inNestedWriteTransaction(diagnostics, transactionActive) {
      updateInTransaction(request)
    }

  private fun updateInTransaction(request: StandalonePhaseStatusUpdate): StandalonePhaseStatusUpdateResult {
    val current = readByExecution(request.executionId) ?: return StandalonePhaseStatusUpdateResult.MISSING
    val leaseExpired =
      current.phaseId != MONITOR_PHASE_ID &&
        current.lifecycleState !in TERMINAL_STATES &&
        !current.leaseExpiresAt.isAfter(clock.instant())
    val rejected =
      when {
        !isAllowedTransition(current, request) -> StandalonePhaseStatusUpdateResult.TERMINAL_REGRESSION
        current.statusRevision != request.expectedRevision -> StandalonePhaseStatusUpdateResult.STALE_REVISION
        current.leaseOwner != request.leaseOwner ||
          current.leaseGeneration != request.leaseGeneration ||
          leaseExpired ->
          StandalonePhaseStatusUpdateResult.STALE_LEASE
        current.lifecycleState in TERMINAL_STATES -> StandalonePhaseStatusUpdateResult.IDEMPOTENT
        else -> null
      }
    if (rejected != null) return rejected
    val nextRevision = incrementDecimal(current.statusRevision)
    val updated =
      connection.prepareStatement(
        """
        UPDATE standalone_phase_status SET
          status_revision = ?, lifecycle_state = ?, current_step = ?, current_activity = ?,
          updated_at = ?, finished_at = ?, active_duration_ms = COALESCE(?, active_duration_ms),
          active_duration_as_of = COALESCE(?, active_duration_as_of),
          terminal_result = ?
        WHERE execution_id = ? AND status_revision = ? AND lease_owner = ? AND lease_generation = ?
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(
          listOf(
            nextRevision,
            request.lifecycleState,
            request.currentStep,
            request.currentActivity,
            request.updatedAt.toString(),
            request.finishedAt?.toString(),
            request.activeDurationMs,
            request.activeDurationAsOf?.toString(),
            request.terminalResult,
            request.executionId,
            request.expectedRevision,
            request.leaseOwner,
            request.leaseGeneration,
          ),
        )
        statement.executeUpdate()
      }
    return if (updated == 1) {
      StandalonePhaseStatusUpdateResult.ACCEPTED
    } else {
      StandalonePhaseStatusUpdateResult.STALE_LEASE
    }
  }

  private fun StandalonePhaseStatusRegistration.storedLeaseExpiresAt(): Instant =
    if (phaseId == MONITOR_PHASE_ID) MONITOR_LEASE_HORIZON else leaseExpiresAt

  private fun supersedeActiveMonitors(
    repositoryIdentity: String,
    branchCorrelation: String,
    now: Instant,
  ) {
    val candidates =
      connection.prepareStatement(
        """
        SELECT execution_id, status_revision FROM standalone_phase_status
        WHERE repository_identity = ? AND branch_correlation = ? AND phase_id = ?
          AND lifecycle_state IN ('active', 'paused')
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(listOf(repositoryIdentity, branchCorrelation, MONITOR_PHASE_ID))
        statement.executeQuery().use { rows ->
          buildList { while (rows.next()) add(rows.getString("execution_id") to rows.getString("status_revision")) }
        }
      }
    if (candidates.isEmpty()) return
    connection.prepareStatement(
      """
      UPDATE standalone_phase_status
      SET lifecycle_state = 'terminal', finished_at = ?, updated_at = ?,
          current_activity = ?, terminal_result = ?, status_revision = ?
      WHERE execution_id = ? AND status_revision = ?
      """.trimIndent(),
    ).use { statement ->
      candidates.forEach { (executionId, revision) ->
        statement.clearParameters()
        statement.bindAll(
          listOf(
            now.toString(),
            now.toString(),
            SUPERSEDED_MONITOR_ACTIVITY,
            SUPERSEDED_MONITOR_ACTIVITY,
            incrementDecimal(revision),
            executionId,
            revision,
          ),
        )
        statement.executeUpdate()
      }
    }
  }

  private fun ensureStore(repositoryIdentity: String): String {
    connection.prepareStatement(
      "SELECT status_store_id FROM ide_status_execution_registry WHERE repository_identity = ?",
    ).use { statement ->
      statement.bindAll(listOf(repositoryIdentity))
      statement.executeQuery().use { rows -> if (rows.next()) return rows.getString(1) }
    }
    val storeId = UUID.randomUUID().toString()
    val now = clock.instant().toString()
    connection.prepareStatement(
      "INSERT OR IGNORE INTO ide_status_execution_registry(" +
        "repository_identity, status_store_id, run_sequence_high_water, created_at, updated_at) " +
        "VALUES (?, ?, '0', ?, ?)",
    ).use { statement ->
      statement.bindAll(listOf(repositoryIdentity, storeId, now, now))
      statement.executeUpdate()
    }
    return connection.prepareStatement(
      "SELECT status_store_id FROM ide_status_execution_registry WHERE repository_identity = ?",
    ).use { statement ->
      statement.bindAll(listOf(repositoryIdentity))
      statement.executeQuery().use { rows ->
        if (rows.next()) rows.getString(1) else error("Status registry was not persisted.")
      }
    }
  }

  private fun registerWorkflowInTransaction(request: IdeStatusWorkflowRegistration): IdeStatusWorkflowExecution {
    readWorkflowByInvocation(request.invocationId)?.let { return it }
    val storeId = ensureStore(request.repositoryIdentity)
    val sequence = allocateSequence(request.repositoryIdentity)
    val updatedAt = request.startedAt.toString()
    connection.prepareStatement(
      """
      INSERT INTO ide_status_workflow_execution(
        execution_id, repository_identity, branch_correlation, issue_key, workflow_id,
        invocation_id, status_store_id, run_sequence, status_revision, lifecycle_state,
        started_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '1', ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        request.executionId,
        request.repositoryIdentity,
        request.branchCorrelation,
        request.issueKey,
        request.workflowId,
        request.invocationId,
        storeId,
        sequence,
        request.lifecycleState,
        updatedAt,
        updatedAt,
      )
      statement.executeUpdate()
    }
    return readWorkflowByExecution(request.executionId)
      ?: error("Workflow status registration was not persisted.")
  }

  private fun readWorkflowExecution(workflowId: String): IdeStatusWorkflowExecution? =
    connection.prepareStatement(
      "SELECT * FROM ide_status_workflow_execution WHERE workflow_id = ? " +
        "ORDER BY length(run_sequence) DESC, run_sequence DESC, started_at DESC, execution_id DESC LIMIT 1",
    ).use { statement ->
      statement.bindAll(workflowId)
      statement.executeQuery().use { rows -> if (rows.next()) rows.toWorkflowExecution() else null }
    }

  private fun readWorkflowByInvocation(invocationId: String): IdeStatusWorkflowExecution? =
    readWorkflowOne("invocation_id", invocationId)

  private fun readWorkflowByExecution(executionId: String): IdeStatusWorkflowExecution? =
    readWorkflowOne("execution_id", executionId)

  private fun readWorkflowOne(
    column: String,
    value: String,
  ): IdeStatusWorkflowExecution? =
    connection.prepareStatement("SELECT * FROM ide_status_workflow_execution WHERE $column = ?").use { statement ->
      statement.bindAll(value)
      statement.executeQuery().use { rows -> if (rows.next()) rows.toWorkflowExecution() else null }
    }

  private fun ResultSet.toWorkflowExecution(): IdeStatusWorkflowExecution =
    IdeStatusWorkflowExecution(
      repositoryIdentity = getString("repository_identity"),
      branchCorrelation = getString("branch_correlation"),
      issueKey = getString("issue_key"),
      workflowId = getString("workflow_id"),
      invocationId = getString("invocation_id"),
      executionId = getString("execution_id"),
      statusStoreId = getString("status_store_id"),
      runSequence = getString("run_sequence"),
      statusRevision = getString("status_revision"),
      lifecycleState = getString("lifecycle_state"),
      startedAt = Instant.parse(getString("started_at")),
      updatedAt = Instant.parse(getString("updated_at")),
    )

  private fun isAllowedTransition(
    current: StandalonePhaseStatusRecord,
    request: StandalonePhaseStatusUpdate,
  ): Boolean {
    if (current.lifecycleState == "paused" && current.currentStep == "runner_interrupted" &&
      request.lifecycleState !in setOf("paused", "failed")
    ) {
      return false
    }
    if (current.lifecycleState !in TERMINAL_STATES) {
      return !(current.lifecycleState == "paused" && request.lifecycleState == "terminal")
    }
    return current.lifecycleState == request.lifecycleState && current.terminalResult == request.terminalResult
  }

  private fun allocateSequence(repositoryIdentity: String): String {
    val current =
      connection.prepareStatement(
        "SELECT run_sequence_high_water FROM ide_status_execution_registry WHERE repository_identity = ?",
      ).use { statement ->
        statement.bindAll(listOf(repositoryIdentity))
        statement.executeQuery().use { rows ->
          if (rows.next()) rows.getString(1) else error("Missing status registry.")
        }
      }
    val next = incrementDecimal(current)
    connection.prepareStatement(UPDATE_STATUS_REGISTRY_SQL).use { statement ->
      statement.bindAll(listOf(next, clock.instant().toString(), repositoryIdentity))
      statement.executeUpdate()
    }
    return next
  }

  private fun readByInvocation(invocationId: String): StandalonePhaseStatusRecord? =
    readOne("invocation_id", invocationId)

  private fun readByExecution(executionId: String): StandalonePhaseStatusRecord? = readOne("execution_id", executionId)

  private fun readOne(
    column: String,
    value: String,
  ): StandalonePhaseStatusRecord? =
    connection.prepareStatement("SELECT * FROM standalone_phase_status WHERE $column = ?").use { statement ->
      statement.bindAll(listOf(value))
      statement.executeQuery().use { rows -> if (rows.next()) rows.toRecord() else null }
    }

  private fun ResultSet.toRecord(): StandalonePhaseStatusRecord =
    StandalonePhaseStatusRecord(
      repositoryIdentity = getString("repository_identity"),
      branchCorrelation = getString("branch_correlation"),
      issueKey = getString("issue_key"),
      workflowId = getString("workflow_id"),
      invocationId = getString("invocation_id"),
      phaseId = getString("phase_id"),
      executionId = getString("execution_id"),
      statusStoreId = getString("status_store_id"),
      runSequence = getString("run_sequence"),
      statusRevision = getString("status_revision"),
      lifecycleState = getString("lifecycle_state"),
      currentStep = getString("current_step"),
      currentActivity = getString("current_activity"),
      startedAt = Instant.parse(getString("started_at")),
      updatedAt = Instant.parse(getString("updated_at")),
      finishedAt = getString("finished_at")?.let(Instant::parse),
      activeDurationMs = getLong("active_duration_ms").takeIf { !wasNull() },
      activeDurationAsOf = getString("active_duration_as_of")?.let(Instant::parse),
      leaseOwner = getString("lease_owner"),
      leaseGeneration = getLong("lease_generation"),
      leaseExpiresAt = Instant.parse(getString("lease_expires_at")),
      terminalResult = getString("terminal_result"),
    )

  private fun incrementDecimal(value: String): String {
    val digits = value.toCharArray()
    var carry = 1
    for (index in digits.lastIndex downTo 0) {
      if (carry == 0) break
      if (digits[index] == '9') {
        digits[index] = '0'
      } else {
        digits[index] = (digits[index].code + 1).toChar()
        carry = 0
      }
    }
    return if (carry == 1) "1${digits.concatToString()}" else String(digits)
  }

  private companion object {
    const val MONITOR_PHASE_ID = "monitor"
    const val SUPERSEDED_MONITOR_ACTIVITY = "Superseded by a later monitor run."
    val MONITOR_LEASE_HORIZON: Instant = Instant.parse("9999-12-31T00:00:00Z")
    val LIVE_STATES = setOf("active", "paused", "blocked")
    val TERMINAL_STATES = setOf("terminal", "failed", "blocked")
    val LIVE_RETENTION: Duration = Duration.ofHours(24)
    val SETTLED_RETENTION: Duration = Duration.ofHours(6)
    val UPDATE_STATUS_REGISTRY_SQL =
      """
      UPDATE ide_status_execution_registry
      SET run_sequence_high_water = ?, updated_at = ?
      WHERE repository_identity = ?
      """.trimIndent()
  }
}
