package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.contracts.JsonCodec
import skillbill.error.core.MalformedJsonTextError
import skillbill.error.shellcontent.invalidIdeStatusSchemaError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.core.ops.inNestedWriteTransaction
import skillbill.infrastructure.sqlite.core.schema.AGENT_ENVIRONMENT_COLUMN
import skillbill.infrastructure.sqlite.core.schema.LAUNCH_SCOPE_COLUMN
import skillbill.infrastructure.sqlite.core.schema.agentEnvironmentJson
import skillbill.infrastructure.sqlite.core.schema.readAgentEnvironment
import skillbill.infrastructure.sqlite.core.schema.readLaunchScope
import skillbill.infrastructure.sqlite.worklist.fractionalSecondsSql
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.StandalonePhaseStatusRepository
import skillbill.ports.idestatus.model.IdeStatusWorkflowExecution
import skillbill.ports.idestatus.model.IdeStatusWorkflowRegistration
import skillbill.ports.idestatus.model.StandaloneEligiblePage
import skillbill.ports.idestatus.model.StandaloneEligibleSlice
import skillbill.ports.idestatus.model.StandalonePhaseExecutionAnchor
import skillbill.ports.idestatus.model.StandalonePhaseExecutionQuery
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.workflow.time.parsePersistedInstantOrNull
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
        lease_generation, lease_expires_at, owner_host_identity, owner_boot_identity, owner_pid, owner_birth_token,
        launch_agent_id, launch_arguments_json, $AGENT_ENVIRONMENT_COLUMN, $LAUNCH_SCOPE_COLUMN
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '1', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
          request.leaseExpiresAt.toString(),
          request.processOwner?.hostIdentity,
          request.processOwner?.bootIdentity,
          request.processOwner?.pid,
          request.processOwner?.processBirthToken,
          request.launchAgentId,
          request.launchArguments?.let(JsonCodec::valueToJsonString),
          agentEnvironmentJson(request.agentEnvironment),
          request.launchScope.wireValue,
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
    page: StandaloneEligiblePage,
  ): List<StandalonePhaseStatusRecord> {
    val (limit, after, slice) = page
    require(limit > 0) { "Eligible status page limit must be positive." }
    val liveFrom = now.minus(LIVE_RETENTION)
    val settledFrom = now.minus(SETTLED_RETENTION)
    val liveStates = LIVE_STATES.joinToString(", ") { "'$it'" }
    val live =
      "(lifecycle_state IN ($liveStates) AND ${instantAtOrAfterBoundSql("updated_at", "live_from")})"
    val settled = "(finished_at IS NOT NULL AND ${instantAfterBoundSql("finished_at", "settled_from")})"
    val eligible =
      when (slice) {
        StandaloneEligibleSlice.LIVE -> live
        StandaloneEligibleSlice.SETTLED -> "($settled AND NOT $live)"
        null -> "($live OR $settled)"
      }
    return connection.prepareStatement(
      """
      WITH bounds(live_from, settled_from) AS (VALUES (?, ?))
      SELECT * FROM standalone_phase_status
      WHERE repository_identity = ? AND branch_correlation = ?
        AND $eligible
        ${if (after == null) "" else "AND $AFTER_ANCHOR_SQL"}
      ORDER BY length(run_sequence) DESC, run_sequence DESC, invocation_id DESC LIMIT ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        listOf(liveFrom.toString(), settledFrom.toString(), repositoryIdentity, branchCorrelation) +
          after.anchorBindings() + limit,
      )
      statement.executeQuery().use { rows ->
        buildList { while (rows.next()) add(rows.toRecord()) }
      }
    }
  }

  override fun readPhaseExecutions(query: StandalonePhaseExecutionQuery): List<StandalonePhaseStatusRecord> {
    require(query.offset >= 0 && query.limit > 0) { "Execution history page bounds must be non-negative and positive." }
    require(query.after == null || query.offset == 0) { "An anchored history page cannot also carry an offset." }
    return connection.prepareStatement(
      "WITH cutoff(value) AS (VALUES (?)) SELECT * FROM standalone_phase_status " +
        "WHERE repository_identity = ? AND phase_id = ? " +
        "AND COALESCE(upper(issue_key), workflow_id, branch_correlation) = ? " +
        "AND ${instantAtOrBeforeSql("started_at")} " +
        (if (query.after == null) "" else "AND $AFTER_ANCHOR_SQL ") +
        "ORDER BY length(run_sequence) DESC, run_sequence DESC, invocation_id DESC LIMIT ? OFFSET ?",
    ).use { statement ->
      statement.bindAll(
        listOf(
          query.startedThrough.toString(),
          query.repositoryIdentity,
          query.phaseId,
          query.phaseGroup,
        ) + query.after.anchorBindings() + listOf(query.limit, query.offset),
      )
      statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toRecord()) } }
    }
  }

  override fun readWorkflowExecutions(
    workflowId: String,
    startedThrough: Instant,
    offset: Int,
    limit: Int,
    after: StandalonePhaseExecutionAnchor?,
  ): List<IdeStatusWorkflowExecution> {
    require(offset >= 0 && limit > 0) { "Execution history page bounds must be non-negative and positive." }
    require(after == null || offset == 0) { "An anchored history page cannot also carry an offset." }
    return connection.prepareStatement(
      "WITH cutoff(value) AS (VALUES (?)) SELECT * FROM ide_status_workflow_execution " +
        "WHERE workflow_id = ? AND ${instantAtOrBeforeSql("started_at")} " +
        (if (after == null) "" else "AND $AFTER_ANCHOR_SQL ") +
        "ORDER BY length(run_sequence) DESC, run_sequence DESC, invocation_id DESC LIMIT ? OFFSET ?",
    ).use { statement ->
      statement.bindAll(listOf(startedThrough.toString(), workflowId) + after.anchorBindings() + listOf(limit, offset))
      statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toWorkflowExecution()) } }
    }
  }

  private fun StandalonePhaseExecutionAnchor?.anchorBindings(): List<Any> =
    if (this == null) {
      emptyList()
    } else {
      listOf(runSequence.length, runSequence.length, runSequence, runSequence.length, runSequence, invocationId)
    }

  private fun instantAtOrBeforeSql(columnName: String): String =
    "(unixepoch($columnName) < unixepoch((SELECT value FROM cutoff)) OR " +
      "(unixepoch($columnName) = unixepoch((SELECT value FROM cutoff)) AND " +
      "${fractionalSecondsSql(columnName)} <= ${fractionalSecondsSql("(SELECT value FROM cutoff)")}))"

  private fun instantAtOrAfterBoundSql(
    columnName: String,
    boundName: String,
  ): String =
    "(unixepoch($columnName) > unixepoch((SELECT $boundName FROM bounds)) OR " +
      "(unixepoch($columnName) = unixepoch((SELECT $boundName FROM bounds)) AND " +
      "${fractionalSecondsSql(columnName)} >= ${fractionalSecondsSql("(SELECT $boundName FROM bounds)")}))"

  private fun instantAfterBoundSql(
    columnName: String,
    boundName: String,
  ): String =
    "(unixepoch($columnName) > unixepoch((SELECT $boundName FROM bounds)) OR " +
      "(unixepoch($columnName) = unixepoch((SELECT $boundName FROM bounds)) AND " +
      "${fractionalSecondsSql(columnName)} > ${fractionalSecondsSql("(SELECT $boundName FROM bounds)")}))"

  override fun readByInvocation(invocationId: String): StandalonePhaseStatusRecord? =
    readOne("invocation_id", invocationId)

  override fun latestStandaloneExecution(workflowId: String): StandalonePhaseStatusRecord? =
    connection.prepareStatement(
      "SELECT * FROM standalone_phase_status WHERE workflow_id = ? " +
        "ORDER BY length(run_sequence) DESC, run_sequence DESC LIMIT 1",
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.executeQuery().use { rows -> if (rows.next()) rows.toRecord() else null }
    }

  override fun requestCancellation(
    record: StandalonePhaseStatusRecord,
    at: Instant,
  ): StandalonePhaseStatusUpdateResult = operatorCancellation(record, at, false)

  override fun cancel(
    record: StandalonePhaseStatusRecord,
    at: Instant,
  ): StandalonePhaseStatusUpdateResult = operatorCancellation(record, at, true)

  private fun operatorCancellation(
    record: StandalonePhaseStatusRecord,
    at: Instant,
    finished: Boolean,
  ): StandalonePhaseStatusUpdateResult =
    connection.inNestedWriteTransaction(diagnostics, transactionActive) {
      updateInTransaction(
        StandalonePhaseStatusUpdate(
          record.executionId, record.statusRevision, record.leaseOwner, record.leaseGeneration,
          if (finished) "canceled" else "paused", if (finished) "canceled" else "cancel_requested",
          if (finished) "Canceled by you." else "Cancellation requested.", at,
          finishedAt = at.takeIf { finished }, activeDurationMs = record.activeDurationMs,
          terminalResult = "Canceled by you.".takeIf { finished },
          acceptedResult = "Canceled by you.".takeIf { finished },
        ),
        operatorCancellation = true,
      )
    }

  override fun update(request: StandalonePhaseStatusUpdate): StandalonePhaseStatusUpdateResult =
    connection.inNestedWriteTransaction(diagnostics, transactionActive) {
      updateInTransaction(request)
    }

  private fun updateInTransaction(
    request: StandalonePhaseStatusUpdate,
    operatorCancellation: Boolean = false,
  ): StandalonePhaseStatusUpdateResult {
    val current = readByExecution(request.executionId) ?: return StandalonePhaseStatusUpdateResult.MISSING
    val rejected = updateRejection(current, request, operatorCancellation)
    if (rejected != null) return rejected
    val nextRevision = incrementDecimal(current.statusRevision)
    val updated =
      connection.prepareStatement(
        """
        UPDATE standalone_phase_status SET
          status_revision = ?, lifecycle_state = ?, current_step = ?, current_activity = ?,
          updated_at = ?, finished_at = ?, active_duration_ms = COALESCE(?, active_duration_ms),
          active_duration_as_of = CASE WHEN ? THEN NULL ELSE COALESCE(?, active_duration_as_of) END,
          terminal_result = ?, accepted_result = ?
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
            request.lifecycleState in TERMINAL_STATES,
            request.activeDurationAsOf?.toString(),
            request.terminalResult,
            request.acceptedResult,
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
        started_at, updated_at, $AGENT_ENVIRONMENT_COLUMN
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '1', ?, ?, ?, ?)
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
        agentEnvironmentJson(request.agentEnvironment),
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
      startedAt = workflowExecutionInstant("started_at"),
      updatedAt = workflowExecutionInstant("updated_at"),
      agentEnvironment = readAgentEnvironment("workflow-status"),
    )

  private fun ResultSet.workflowExecutionInstant(column: String): Instant {
    val raw = getString(column)
    val parsed =
      raw?.let(::parsePersistedInstantOrNull)
        ?: throw invalidIdeStatusSchemaError(
          sourceLabel = "workflow execution '${getString("workflow_id")}'",
          fieldPath = column,
          reason = "Invalid persisted timestamp.",
        )
    if (raw.contains(' ')) {
      diagnostics.info(
        "seam=workflow_execution_timestamp record_kind=migration column=$column " +
          "value_expected=iso_instant value_used=sqlite_utc_timestamp",
      )
    }
    return parsed
  }

  private fun updateRejection(
    current: StandalonePhaseStatusRecord,
    request: StandalonePhaseStatusUpdate,
    operatorCancellation: Boolean,
  ): StandalonePhaseStatusUpdateResult? {
    val leaseExpired =
      current.lifecycleState !in TERMINAL_STATES &&
        !current.leaseExpiresAt.isAfter(clock.instant())
    return when {
      !isAllowedTransition(current, request, operatorCancellation) ->
        StandalonePhaseStatusUpdateResult.TERMINAL_REGRESSION
      current.statusRevision != request.expectedRevision -> StandalonePhaseStatusUpdateResult.STALE_REVISION
      current.leaseOwner != request.leaseOwner ||
        current.leaseGeneration != request.leaseGeneration ||
        leaseExpired && !operatorCancellation ->
        StandalonePhaseStatusUpdateResult.STALE_LEASE
      current.lifecycleState in TERMINAL_STATES && !operatorCancellation ->
        StandalonePhaseStatusUpdateResult.IDEMPOTENT
      else -> null
    }
  }

  private fun isAllowedTransition(
    current: StandalonePhaseStatusRecord,
    request: StandalonePhaseStatusUpdate,
    operatorCancellation: Boolean,
  ): Boolean {
    if (operatorCancellation && current.lifecycleState !in setOf("terminal", "completed", "success", "canceled")) {
      return true
    }
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
      acceptedResult = getString("accepted_result"),
      processOwner = readProcessOwner(),
      launchAgentId = getString("launch_agent_id"),
      launchArguments = readLaunchArguments(),
      agentEnvironment = readAgentEnvironment("standalone-status"),
      launchScope = readLaunchScope("standalone-status"),
    )

  private fun ResultSet.readLaunchArguments(): List<String>? {
    val raw = getString("launch_arguments_json") ?: return null
    val values =
      try {
        JsonCodec.parseValue(raw) as? List<*>
      } catch (failure: MalformedJsonTextError) {
        throw invalidIdeStatusSchemaError(
          "standalone-status",
          "launch_arguments_json",
          "Invalid launch arguments: ${failure.message}",
          failure,
        )
      }
    if (values == null || values.size > MAX_LAUNCH_ARGUMENTS || values.any { !validLaunchArgument(it) }) {
      throw invalidIdeStatusSchemaError("standalone-status", "launch_arguments_json", "Invalid launch arguments.")
    }
    return values.filterIsInstance<String>()
  }

  private fun validLaunchArgument(value: Any?): Boolean = value is String && value.length <= MAX_LAUNCH_ARGUMENT_LENGTH

  private fun ResultSet.readProcessOwner(): FeatureTaskRuntimeProcessIdentity? {
    val host = getString("owner_host_identity")
    val boot = getString("owner_boot_identity")
    val pid = getLong("owner_pid").takeIf { !wasNull() }
    val birth = getString("owner_birth_token")
    val fields = listOf(host, boot, pid?.toString(), birth)
    if (fields.all { it == null }) return null
    if (fields.any { it.isNullOrBlank() } || pid == null || pid <= 0) {
      throw invalidIdeStatusSchemaError("standalone-status", "process_owner", "owner identity is incomplete")
    }
    return FeatureTaskRuntimeProcessIdentity(requireNotNull(host), requireNotNull(boot), pid, requireNotNull(birth))
  }

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
    const val MAX_LAUNCH_ARGUMENTS = 128
    const val MAX_LAUNCH_ARGUMENT_LENGTH = 16384
    const val MONITOR_PHASE_ID = "monitor"
    const val SUPERSEDED_MONITOR_ACTIVITY = "Superseded by a later monitor run."
    val LIVE_STATES = setOf("active", "paused", "blocked")
    val TERMINAL_STATES = setOf("terminal", "failed", "blocked", "canceled")
    const val AFTER_ANCHOR_SQL: String =
      "(length(run_sequence) < ? OR (length(run_sequence) = ? AND run_sequence < ?) " +
        "OR (length(run_sequence) = ? AND run_sequence = ? AND invocation_id < ?))"
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
