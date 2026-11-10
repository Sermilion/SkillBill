package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.contracts.JsonCodec
import skillbill.error.core.MalformedJsonTextError
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.error.shellcontent.invalidIdeStatusSchemaError
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.MONITOR_CLEANUP_ASSIGNMENTS
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.MONITOR_PR_KEY_COLUMN
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.MONITOR_TARGET_COLUMN_LIST
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.MONITOR_TARGET_PLACEHOLDERS
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.PLAN_REPORT_ASSIGNMENT
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.QA_OUTCOME_ASSIGNMENT
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.QA_REPORT_TEXT_ASSIGNMENT
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.REVIEW_RUN_ID_ASSIGNMENT
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.columnValue
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.columnValues
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.qadetails.QA_DETAILS_ASSIGNMENT
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.qadetails.encodeQaDetails
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.qadetails.readQaDetailsOrWarn
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.readMonitorCleanup
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.readMonitorTarget
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.readPlanReport
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.readQaChecklist
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.readQaOutcome
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.readQaReportText
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.readReviewRunId
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.validationcheck.StandaloneValidationCheckStore
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.validationcheck.VALIDATION_OUTCOME_ASSIGNMENT
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.validationcheck.readLiveChecks
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.validationcheck.readValidationOutcome
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.writeStandaloneQaChecklist
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.core.ops.inNestedWriteTransaction
import skillbill.infrastructure.sqlite.core.schema.AGENT_ENVIRONMENT_COLUMN
import skillbill.infrastructure.sqlite.core.schema.LAUNCH_SCOPE_COLUMN
import skillbill.infrastructure.sqlite.core.schema.REPORTED_MODEL_COLUMN
import skillbill.infrastructure.sqlite.core.schema.agentEnvironmentJson
import skillbill.infrastructure.sqlite.core.schema.readAgentEnvironment
import skillbill.infrastructure.sqlite.core.schema.readLaunchScope
import skillbill.infrastructure.sqlite.core.schema.readStandaloneReportedModel
import skillbill.infrastructure.sqlite.idestatus.readPlanActivity
import skillbill.infrastructure.sqlite.idestatus.writePlanActivity
import skillbill.infrastructure.sqlite.workflow.featuretask.validation.StandaloneValidationBranchReads
import skillbill.infrastructure.sqlite.worklist.fractionalSecondsSql
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.StandalonePhaseStatusRepository
import skillbill.ports.idestatus.StandaloneValidationBranchRepository
import skillbill.ports.idestatus.StandaloneValidationCheckRepository
import skillbill.ports.idestatus.model.IdeStatusWorkflowExecution
import skillbill.ports.idestatus.model.IdeStatusWorkflowRegistration
import skillbill.ports.idestatus.model.StandaloneEligiblePage
import skillbill.ports.idestatus.model.StandaloneEligibleSlice
import skillbill.ports.idestatus.model.StandalonePhaseExecutionAnchor
import skillbill.ports.idestatus.model.StandalonePhaseExecutionQuery
import skillbill.ports.idestatus.model.StandalonePhaseOutputLine
import skillbill.ports.idestatus.model.StandalonePhaseOutputSnapshot
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.idestatus.model.StandaloneQaChecklist
import skillbill.ports.idestatus.model.StandaloneQaChecklistWriteResult
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.workflow.time.parsePersistedInstantOrNull
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

internal class StandalonePhaseStatusStore(
  private val connection: Connection,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val transactionActive: Boolean,
) : StandalonePhaseStatusRepository,
  StandaloneValidationCheckRepository by StandaloneValidationCheckStore(connection, diagnostics, transactionActive),
  StandaloneValidationBranchRepository by StandaloneValidationBranchReads(connection) {
  override fun register(request: StandalonePhaseStatusRegistration): StandalonePhaseStatusRecord =
    connection.inNestedWriteTransaction(diagnostics, transactionActive) {
      registerInTransaction(request)
    }

  override fun registerWorkflow(request: IdeStatusWorkflowRegistration): IdeStatusWorkflowExecution =
    connection.inNestedWriteTransaction(diagnostics, transactionActive) {
      registerWorkflowInTransaction(request)
    }

  override fun latestWorkflowExecution(workflowId: String): IdeStatusWorkflowExecution? =
    connection.prepareStatement(
      "SELECT * FROM ide_status_workflow_execution WHERE workflow_id = ? " +
        "ORDER BY length(run_sequence) DESC, run_sequence DESC, started_at DESC, execution_id DESC LIMIT 1",
    ).use { statement ->
      statement.bindAll(workflowId)
      statement.executeQuery().use { rows -> if (rows.next()) rows.toWorkflowExecution() else null }
    }

  private fun registerInTransaction(request: StandalonePhaseStatusRegistration): StandalonePhaseStatusRecord {
    readByInvocation(request.invocationId)?.let { return it }
    if (request.phaseId == MONITOR_PHASE_ID && request.monitorTarget == null) {
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
        launch_agent_id, launch_arguments_json, $AGENT_ENVIRONMENT_COLUMN, $LAUNCH_SCOPE_COLUMN,
        primary_repository_root,
        $MONITOR_TARGET_COLUMN_LIST
      ) VALUES (
        ?, ?, ?, ?, ?, ?, ?, ?, ?, '1', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
        $MONITOR_TARGET_PLACEHOLDERS
      )
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
          request.primaryRepositoryRoot,
        ) + request.monitorTarget.columnValues(),
      )
      statement.executeUpdate()
    }
    return readOne("execution_id", request.executionId) ?: error("Standalone status registration was not persisted.")
  }

  override fun readEligible(
    repositoryIdentity: String,
    branchCorrelation: String?,
    now: Instant,
    page: StandaloneEligiblePage,
    monitorRepositoryIdentity: String,
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
      WHERE ((repository_identity = ? AND ? IS NOT NULL AND branch_correlation = ?)
          OR (repository_identity = ? AND $MONITOR_PR_KEY_COLUMN IS NOT NULL))
        AND $eligible
        ${if (after == null) "" else "AND $AFTER_ANCHOR_SQL"}
      ORDER BY length(run_sequence) DESC, run_sequence DESC, invocation_id DESC
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        listOf(
          liveFrom.toString(),
          settledFrom.toString(),
          repositoryIdentity,
          branchCorrelation,
          branchCorrelation,
          monitorRepositoryIdentity,
        ) + after.anchorBindings(),
      )
      statement.executeQuery().use { rows -> rows.readAggregatePage(0, limit) { tryToRecordForAggregate(it) } }
    }
  }

  override fun readPhaseExecutions(query: StandalonePhaseExecutionQuery): List<StandalonePhaseStatusRecord> {
    require(query.offset >= 0 && query.limit > 0) { "Execution history page bounds must be non-negative and positive." }
    require(query.after == null || query.offset == 0) { "An anchored history page cannot also carry an offset." }
    return connection.prepareStatement(
      "WITH cutoff(value) AS (VALUES (?)) SELECT * FROM standalone_phase_status " +
        "WHERE repository_identity = ? AND phase_id = ? " +
        "AND COALESCE($MONITOR_PR_KEY_COLUMN, upper(issue_key), workflow_id, branch_correlation) = ? " +
        "AND ${instantAtOrBeforeSql("started_at")} " +
        (if (query.after == null) "" else "AND $AFTER_ANCHOR_SQL ") +
        "ORDER BY length(run_sequence) DESC, run_sequence DESC, invocation_id DESC",
    ).use { statement ->
      statement.bindAll(
        listOf(
          query.startedThrough.toString(),
          query.repositoryIdentity,
          query.phaseId,
          query.phaseGroup,
        ) + query.after.anchorBindings(),
      )
      statement.executeQuery().use { rows ->
        rows.readAggregatePage(query.offset, query.limit) { tryToRecordForAggregate(it) }
      }
    }
  }

  private fun <T> ResultSet.readAggregatePage(
    offset: Int,
    limit: Int,
    parse: ResultSet.(AggregateRowSkipDiagnostics) -> T?,
  ): List<T> {
    val skipDiagnostics = AggregateRowSkipDiagnostics()
    val items = mutableListOf<T>()
    var healthySkipped = 0
    while (next() && items.size < limit) {
      val parsed = parse(skipDiagnostics)
      if (parsed != null) {
        if (healthySkipped < offset) {
          healthySkipped++
        } else {
          items += parsed
        }
      }
    }
    return items
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
        "ORDER BY length(run_sequence) DESC, run_sequence DESC, invocation_id DESC",
    ).use { statement ->
      statement.bindAll(listOf(startedThrough.toString(), workflowId) + after.anchorBindings())
      statement.executeQuery().use { rows ->
        rows.readAggregatePage(offset, limit) { tryToWorkflowExecutionForAggregate(it) }
      }
    }
  }

  override fun readByInvocation(invocationId: String): StandalonePhaseStatusRecord? =
    readOne("invocation_id", invocationId)

  override fun latestStandaloneExecution(workflowId: String): StandalonePhaseStatusRecord? =
    connection.prepareStatement(
      """
      SELECT * FROM standalone_phase_status WHERE workflow_id = ?
      ORDER BY CASE WHEN lifecycle_state IN (?, ?) THEN 0 ELSE 1 END,
        length(run_sequence) DESC, run_sequence DESC
      LIMIT 1
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        listOf(
          workflowId,
          IdeStatusLifecycleState.ACTIVE.wireValue,
          IdeStatusLifecycleState.PAUSED.wireValue,
        ),
      )
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

  override fun writeQaChecklist(
    invocationId: String,
    checklist: StandaloneQaChecklist,
  ): StandaloneQaChecklistWriteResult =
    connection.inNestedWriteTransaction(diagnostics, transactionActive) {
      connection.writeStandaloneQaChecklist(invocationId, checklist)
    }

  override fun writeOutputSnapshot(
    executionId: String,
    snapshot: StandalonePhaseOutputSnapshot,
  ) {
    if (executionId.isBlank()) return
    connection.inNestedWriteTransaction(diagnostics, transactionActive) {
      connection.appendStandaloneOutputLines(executionId, snapshot.pendingLines)
      connection.writePlanActivity(executionId, snapshot.pendingActivity)
      val tail = snapshot.tail.takeLast(OUTPUT_TAIL_LINES).map { it.take(MAX_OUTPUT_LINE_CHARS) }
      val attributed =
        snapshot.latestAttributedLines.entries.toList().takeLast(MAX_ATTRIBUTED_SUBTASKS)
          .associateTo(linkedMapOf()) { it.key to it.value.take(MAX_OUTPUT_LINE_CHARS) }
      val attributedJson = JsonCodec.valueToJsonString(attributed.mapKeys { it.key.toString() })
      val attributedAt =
        snapshot.latestAttributedLineAt.filterKeys { it in attributed }.mapValues { it.value.toString() }
      val attributedAtJson = JsonCodec.valueToJsonString(attributedAt.mapKeys { it.key.toString() })
      val tailJson = JsonCodec.valueToJsonString(tail)
      check(tailJson.length <= MAX_OUTPUT_TAIL_JSON_CHARS) { "Standalone output tail exceeds its SQLite record bound." }
      check(attributedJson.length <= MAX_OUTPUT_ATTRIBUTED_JSON_CHARS) {
        "Standalone output attribution exceeds its SQLite record bound."
      }
      check(attributedAtJson.length <= MAX_OUTPUT_ATTRIBUTED_JSON_CHARS) {
        "Standalone output attribution times exceed their SQLite record bound."
      }
      connection.prepareStatement(
        """
        INSERT INTO standalone_phase_output(execution_id, tail_json, latest_line_at, attributed_json, attributed_at_json)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(execution_id) DO UPDATE SET
          tail_json = excluded.tail_json,
          latest_line_at = excluded.latest_line_at,
          attributed_json = excluded.attributed_json,
          attributed_at_json = excluded.attributed_at_json
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(executionId, tailJson, snapshot.latestLineAt?.toString(), attributedJson, attributedAtJson)
        check(statement.executeUpdate() == 1) { "Standalone output snapshot was not written." }
      }
    }
  }

  override fun readOutputLines(
    executionId: String,
    beforeSequence: Long?,
    limit: Int,
  ): List<StandalonePhaseOutputLine> = connection.readStandaloneOutputLines(executionId, beforeSequence, limit)

  override fun readOutput(executionId: String): StandalonePhaseOutputSnapshot? =
    connection.prepareStatement(
      "SELECT tail_json, latest_line_at, attributed_json, attributed_at_json " +
        "FROM standalone_phase_output WHERE execution_id = ?",
    ).use { statement ->
      statement.bindAll(executionId)
      statement.executeQuery().use { rows ->
        if (!rows.next()) return null
        val tail =
          (JsonCodec.parseValue(rows.getString("tail_json")) as? List<*>)
            ?.filterIsInstance<String>()?.takeLast(OUTPUT_TAIL_LINES)?.map { it.take(MAX_OUTPUT_LINE_CHARS) }.orEmpty()
        val attributed =
          (JsonCodec.parseValue(rows.getString("attributed_json")) as? Map<*, *>)
            ?.entries?.mapNotNull { (key, value) ->
              val id = (key as? String)?.toIntOrNull() ?: return@mapNotNull null
              val line = value as? String ?: return@mapNotNull null
              id.takeIf { it > 0 }?.let { it to line.take(MAX_OUTPUT_LINE_CHARS) }
            }?.takeLast(MAX_ATTRIBUTED_SUBTASKS)?.toMap(linkedMapOf()).orEmpty()
        val attributedAt =
          (rows.getString("attributed_at_json")?.let { JsonCodec.parseValue(it) } as? Map<*, *>)
            ?.entries?.mapNotNull { (key, value) ->
              val id = (key as? String)?.toIntOrNull() ?: return@mapNotNull null
              val at = (value as? String)?.let(::parsePersistedInstantOrNull) ?: return@mapNotNull null
              id.takeIf { it > 0 && it in attributed }?.let { it to at }
            }?.toMap(linkedMapOf()).orEmpty()
        StandalonePhaseOutputSnapshot(
          tail = tail,
          latestLineAt = parsePersistedInstantOrNull(rows.getString("latest_line_at").orEmpty()),
          latestAttributedLines = attributed,
          latestAttributedLineAt = attributedAt,
          activities = connection.readPlanActivity(executionId),
        )
      }
    }

  override fun releaseExpiredLease(
    record: StandalonePhaseStatusRecord,
    at: Instant,
  ): StandalonePhaseStatusUpdateResult =
    connection.inNestedWriteTransaction(diagnostics, transactionActive) {
      releaseExpiredLeaseInTransaction(record, at)
    }

  private fun releaseExpiredLeaseInTransaction(
    record: StandalonePhaseStatusRecord,
    at: Instant,
  ): StandalonePhaseStatusUpdateResult {
    val current = readOne("execution_id", record.executionId) ?: return StandalonePhaseStatusUpdateResult.MISSING
    val rejection =
      when {
        current.statusRevision != record.statusRevision -> StandalonePhaseStatusUpdateResult.STALE_REVISION
        current.leaseOwner != record.leaseOwner ||
          current.leaseGeneration != record.leaseGeneration ||
          current.leaseExpiresAt.isAfter(at) -> StandalonePhaseStatusUpdateResult.STALE_LEASE
        current.lifecycleState in TERMINAL_STATES -> StandalonePhaseStatusUpdateResult.IDEMPOTENT
        else -> null
      }
    if (rejection != null) return rejection
    val updated =
      connection.prepareStatement(
        """
        UPDATE standalone_phase_status SET
          status_revision = ?, lifecycle_state = 'blocked', current_activity = ?, updated_at = ?,
          finished_at = ?, active_duration_as_of = NULL, terminal_result = ?, accepted_result = ?
        WHERE execution_id = ? AND status_revision = ? AND lease_owner = ? AND lease_generation = ?
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(
          listOf(
            incrementDecimal(current.statusRevision),
            RELEASED_OWNER_ACTIVITY,
            at.toString(),
            at.toString(),
            RELEASED_OWNER_ACTIVITY,
            RELEASED_OWNER_ACTIVITY,
            current.executionId,
            current.statusRevision,
            current.leaseOwner,
            current.leaseGeneration,
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

  private fun updateInTransaction(
    request: StandalonePhaseStatusUpdate,
    operatorCancellation: Boolean = false,
  ): StandalonePhaseStatusUpdateResult {
    val current = readOne("execution_id", request.executionId) ?: return StandalonePhaseStatusUpdateResult.MISSING
    require(request.monitorCleanup == null || current.monitorTarget != null) {
      "Monitor cleanup can only be recorded on a target-bearing monitor execution."
    }
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
          terminal_result = ?, accepted_result = ?,
          $MONITOR_CLEANUP_ASSIGNMENTS, $QA_OUTCOME_ASSIGNMENT, $QA_REPORT_TEXT_ASSIGNMENT, $QA_DETAILS_ASSIGNMENT,
          $PLAN_REPORT_ASSIGNMENT, $REVIEW_RUN_ID_ASSIGNMENT, $VALIDATION_OUTCOME_ASSIGNMENT,
          $REPORTED_MODEL_COLUMN = CASE WHEN ? THEN ? ELSE $REPORTED_MODEL_COLUMN END
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
          ) + request.monitorCleanup.columnValues() +
            listOf(
              request.qaOutcome?.wireValue,
              request.qaReportText,
              request.qaDetails?.let(::encodeQaDetails),
              request.planReport.columnValue(),
              request.reviewRunId,
              request.validationOutcome?.wireValue,
              request.replaceReportedModel,
              request.reportedModel,
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
          AND lifecycle_state IN ('active', 'paused') AND $MONITOR_PR_KEY_COLUMN IS NULL
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
    readWorkflowOne("invocation_id", request.invocationId)?.let { return it }
    val storeId = ensureStore(request.repositoryIdentity)
    val sequence = allocateSequence(request.repositoryIdentity)
    val updatedAt = request.startedAt.toString()
    connection.prepareStatement(
      """
      INSERT INTO ide_status_workflow_execution(
        execution_id, repository_identity, branch_correlation, issue_key, workflow_id,
        invocation_id, status_store_id, run_sequence, status_revision, lifecycle_state,
        started_at, updated_at, $AGENT_ENVIRONMENT_COLUMN, primary_repository_root
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '1', ?, ?, ?, ?, ?)
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
        request.primaryRepositoryRoot,
      )
      statement.executeUpdate()
    }
    return readWorkflowOne("execution_id", request.executionId)
      ?: error("Workflow status registration was not persisted.")
  }

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
      primaryRepositoryRoot = getString("primary_repository_root"),
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
    if (operatorCancellation && current.lifecycleState !in OPERATOR_CANCEL_CLOSED_STATES) {
      return true
    }
    if (current.lifecycleState == IdeStatusLifecycleState.PAUSED.wireValue &&
      current.currentStep == "runner_interrupted" &&
      request.lifecycleState !in
      setOf(
        IdeStatusLifecycleState.PAUSED.wireValue,
        IdeStatusLifecycleState.FAILED.wireValue,
      )
    ) {
      return false
    }
    if (current.lifecycleState !in TERMINAL_STATES) {
      return !(
        current.lifecycleState == IdeStatusLifecycleState.PAUSED.wireValue &&
          request.lifecycleState == IdeStatusLifecycleState.TERMINAL.wireValue
      )
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

  private fun readOne(
    column: String,
    value: String,
  ): StandalonePhaseStatusRecord? =
    connection.prepareStatement("SELECT * FROM standalone_phase_status WHERE $column = ?").use { statement ->
      statement.bindAll(listOf(value))
      statement.executeQuery().use { rows -> if (rows.next()) rows.toRecord() else null }
    }

  private class AggregateRowSkipDiagnostics {
    var emitted: Int = 0
    var truncated: Boolean = false
  }

  private fun ResultSet.tryToRecordForAggregate(
    skipDiagnostics: AggregateRowSkipDiagnostics,
  ): StandalonePhaseStatusRecord? =
    try {
      toRecord()
    } catch (error: SkillBillRuntimeException) {
      if (error.code != InstallFailureCode.INVALID_IDE_STATUS_SCHEMA) throw error
      emitAggregateRowSkip(
        skipDiagnostics,
        seam = "standalone_phase_status",
        rowKind = "readable_standalone_row",
        identity =
          "invocation_id=${getString("invocation_id") ?: "<unknown>"}",
        error = error,
      )
      null
    }

  private fun ResultSet.tryToWorkflowExecutionForAggregate(
    skipDiagnostics: AggregateRowSkipDiagnostics,
  ): IdeStatusWorkflowExecution? =
    try {
      toWorkflowExecution()
    } catch (error: SkillBillRuntimeException) {
      if (error.code != InstallFailureCode.INVALID_IDE_STATUS_SCHEMA) throw error
      val workflowId = getString("workflow_id") ?: "<unknown>"
      val invocationId = getString("invocation_id") ?: "<unknown>"
      val executionId = getString("execution_id") ?: "<unknown>"
      emitAggregateRowSkip(
        skipDiagnostics,
        seam = "ide_status_workflow_execution",
        rowKind = "readable_workflow_execution_row",
        identity = "workflow_id=$workflowId invocation_id=$invocationId execution_id=$executionId",
        error = error,
      )
      null
    }

  private fun emitAggregateRowSkip(
    skipDiagnostics: AggregateRowSkipDiagnostics,
    seam: String,
    rowKind: String,
    identity: String,
    error: SkillBillRuntimeException,
  ) {
    if (skipDiagnostics.emitted < MAX_AGGREGATE_ROW_DIAGNOSTICS) {
      diagnostics.warning(
        "seam=$seam value_expected=$rowKind value_used=skipped $identity error=${error.message.orEmpty()}",
      )
      skipDiagnostics.emitted++
    } else if (!skipDiagnostics.truncated) {
      diagnostics.warning(
        "seam=$seam value_expected=$rowKind value_used=truncated " +
          "skipped_diagnostics_cap=$MAX_AGGREGATE_ROW_DIAGNOSTICS",
      )
      skipDiagnostics.truncated = true
    }
  }

  private fun ResultSet.toRecord(): StandalonePhaseStatusRecord {
    val monitorTarget = readMonitorTarget("standalone-status")
    return StandalonePhaseStatusRecord(
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
      startedAt = standaloneInstant("started_at"),
      updatedAt = standaloneInstant("updated_at"),
      finishedAt = standaloneInstantOrNull("finished_at"),
      activeDurationMs = getLong("active_duration_ms").takeIf { !wasNull() },
      activeDurationAsOf = standaloneInstantOrNull("active_duration_as_of"),
      leaseOwner = getString("lease_owner"),
      leaseGeneration = getLong("lease_generation"),
      leaseExpiresAt = standaloneInstant("lease_expires_at"),
      terminalResult = getString("terminal_result"),
      acceptedResult = getString("accepted_result"),
      processOwner = readProcessOwner(),
      launchAgentId = getString("launch_agent_id"),
      launchArguments = readLaunchArguments(),
      agentEnvironment = readAgentEnvironment("standalone-status"),
      launchScope = readLaunchScope("standalone-status"),
      monitorTarget = monitorTarget,
      monitorCleanup = readMonitorCleanup("standalone-status", monitorTarget != null),
      qaOutcome = readQaOutcome("standalone-status"),
      qaReportText = readQaReportText(),
      planReport = readPlanReport("standalone-status"),
      reviewRunId = readReviewRunId(),
      qaChecklist = readQaChecklist(getString("invocation_id"), diagnostics),
      qaDetails = readQaDetailsOrWarn(getString("invocation_id"), diagnostics),
      liveChecks = readLiveChecks("standalone-status"),
      validationOutcome = readValidationOutcome("standalone-status"),
      reportedModel = readStandaloneReportedModel(),
      primaryRepositoryRoot = getString("primary_repository_root"),
    )
  }

  private fun ResultSet.standaloneInstant(column: String): Instant {
    val raw = getString(column)
    return parsePersistedInstantOrNull(raw.orEmpty())
      ?: throw invalidIdeStatusSchemaError(
        sourceLabel = "standalone status '${getString("invocation_id")}'",
        fieldPath = column,
        reason = "Invalid persisted timestamp.",
      )
  }

  private fun ResultSet.standaloneInstantOrNull(column: String): Instant? {
    val raw = getString(column) ?: return null
    return parsePersistedInstantOrNull(raw)
      ?: throw invalidIdeStatusSchemaError(
        sourceLabel = "standalone status '${getString("invocation_id")}'",
        fieldPath = column,
        reason = "Invalid persisted timestamp.",
      )
  }

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
    if (values == null ||
      values.size > MAX_LAUNCH_ARGUMENTS ||
      values.any { !validLaunchArgument(it, MAX_LAUNCH_ARGUMENT_LENGTH) }
    ) {
      throw invalidIdeStatusSchemaError("standalone-status", "launch_arguments_json", "Invalid launch arguments.")
    }
    return values.filterIsInstance<String>()
  }

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

  private companion object {
    const val OUTPUT_TAIL_LINES = 4
    const val MAX_OUTPUT_LINE_CHARS = 160
    const val MAX_ATTRIBUTED_SUBTASKS = 32
    const val MAX_OUTPUT_TAIL_JSON_CHARS = 4096
    const val MAX_OUTPUT_ATTRIBUTED_JSON_CHARS = 32768
    const val MAX_AGGREGATE_ROW_DIAGNOSTICS = 32
    const val MAX_LAUNCH_ARGUMENTS = 128
    const val MAX_LAUNCH_ARGUMENT_LENGTH = 16384
    const val MONITOR_PHASE_ID = "monitor"
    const val SUPERSEDED_MONITOR_ACTIVITY = "Superseded by a later monitor run."
    const val RELEASED_OWNER_ACTIVITY = "Owner stopped; released for block incident recovery."
    val LIVE_STATES =
      setOf(
        IdeStatusLifecycleState.ACTIVE.wireValue,
        IdeStatusLifecycleState.PAUSED.wireValue,
        IdeStatusLifecycleState.BLOCKED.wireValue,
      )
    val TERMINAL_STATES =
      setOf(
        IdeStatusLifecycleState.TERMINAL.wireValue,
        IdeStatusLifecycleState.FAILED.wireValue,
        IdeStatusLifecycleState.BLOCKED.wireValue,
        IdeStatusLifecycleState.CANCELED.wireValue,
      )
    val OPERATOR_CANCEL_CLOSED_STATES =
      setOf(
        IdeStatusLifecycleState.TERMINAL.wireValue,
        "completed",
        "success",
        IdeStatusLifecycleState.CANCELED.wireValue,
      )
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

private fun validLaunchArgument(
  value: Any?,
  maxLength: Int,
): Boolean = value is String && value.length <= maxLength

private fun StandalonePhaseExecutionAnchor?.anchorBindings(): List<Any> =
  if (this == null) {
    emptyList()
  } else {
    listOf(runSequence.length, runSequence.length, runSequence, runSequence.length, runSequence, invocationId)
  }

private fun Connection.appendStandaloneOutputLines(
  executionId: String,
  lines: List<StandalonePhaseOutputLine>,
) {
  if (lines.isEmpty()) return
  prepareStatement(
    "INSERT INTO standalone_phase_output_lines(entry_id, execution_id, line_text, observed_at, subtask_id, is_raw) " +
      "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(entry_id) DO NOTHING",
  ).use { statement ->
    lines.forEach { line ->
      statement.bindAll(line.id, executionId, line.text, line.at.toString(), line.subtaskId, if (line.isRaw) 1 else 0)
      statement.addBatch()
    }
    statement.executeBatch()
  }
}

private fun Connection.readStandaloneOutputLines(
  executionId: String,
  beforeSequence: Long?,
  limit: Int,
): List<StandalonePhaseOutputLine> =
  prepareStatement(
    "SELECT sequence, entry_id, line_text, observed_at, subtask_id, is_raw FROM standalone_phase_output_lines " +
      "WHERE execution_id = ? AND sequence < ? ORDER BY sequence DESC LIMIT ?",
  ).use { statement ->
    statement.bindAll(executionId, beforeSequence ?: Long.MAX_VALUE, limit)
    statement.executeQuery().use { rows ->
      buildList {
        while (rows.next()) {
          add(
            StandalonePhaseOutputLine(
              id = rows.getString("entry_id"),
              text = rows.getString("line_text"),
              at =
                try {
                  Instant.parse(rows.getString("observed_at"))
                } catch (error: DateTimeParseException) {
                  throw invalidIdeStatusSchemaError(
                    executionId,
                    "observed_at",
                    "Invalid output timestamp: ${error.message}",
                  )
                },
              subtaskId = rows.getInt("subtask_id").takeUnless { rows.wasNull() },
              sequence = rows.getLong("sequence"),
              isRaw = rows.getInt("is_raw") != 0,
            ),
          )
        }
      }
    }
  }
