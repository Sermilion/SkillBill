package skillbill.infrastructure.sqlite

import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.goal.UnaddressedFindingsRuntime
import skillbill.infrastructure.sqlite.operation.SqliteOperationProposalStore
import skillbill.infrastructure.sqlite.review.accounting.loadReviewAccounting
import skillbill.infrastructure.sqlite.review.accounting.persistImportedReview
import skillbill.infrastructure.sqlite.review.accounting.upsertReviewAccounting
import skillbill.infrastructure.sqlite.review.stage.ReviewRuntime
import skillbill.infrastructure.sqlite.review.stage.TriageRuntime
import skillbill.infrastructure.sqlite.review.stats.ReviewFinishedTelemetryUpdateRequest
import skillbill.infrastructure.sqlite.review.stats.ReviewStatsRuntime
import skillbill.infrastructure.sqlite.review.stats.rejectedFindingOutcomeTypes
import skillbill.infrastructure.sqlite.telemetry.lifecycle.LifecycleTelemetryStore
import skillbill.infrastructure.sqlite.telemetry.outbox.TelemetryOutboxStore
import skillbill.infrastructure.sqlite.telemetry.reconcileStaleTelemetrySessions
import skillbill.infrastructure.sqlite.workflow.WorkflowStateStore
import skillbill.infrastructure.sqlite.workflow.WorktreeEditJournalStore
import skillbill.infrastructure.sqlite.workflow.featuretask.AgentActivityStampStore
import skillbill.infrastructure.sqlite.workflow.featuretask.StandalonePhaseStatusStore
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.GoalPlanningPreparationStore
import skillbill.infrastructure.sqlite.workflow.goalrunner.runner.GoalRunnerControlStore
import skillbill.infrastructure.sqlite.worklist.SQLiteWorkListRepository
import skillbill.learnings.LearningsRuntime
import skillbill.learnings.model.CreateLearningRequest
import skillbill.learnings.model.LearningRecord
import skillbill.learnings.model.LearningSourceValidation
import skillbill.learnings.model.RejectedLearningSourceOutcome
import skillbill.learnings.model.UpdateLearningRequest
import skillbill.ports.diagnostics.RejectedOutputDiagnosticPermissions
import skillbill.ports.diagnostics.RejectedOutputDiagnosticRepository
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.goalrunner.UnaddressedFindingsRepository
import skillbill.ports.idestatus.AgentActivityStampRepository
import skillbill.ports.idestatus.StandalonePhaseStatusRepository
import skillbill.ports.idestatus.WorktreeEditJournalRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.learning.model.LearningResolution
import skillbill.ports.operation.OperationProposalRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.model.GoalPurgeTableCounts
import skillbill.ports.persistence.model.GoalPurgeTarget
import skillbill.ports.review.model.ReviewAccountingRecord
import skillbill.ports.review.model.ReviewRepositoryStatsSnapshot
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.review.repository.ReviewRunCompletenessRepository
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.model.TelemetryReconciliationRequest
import skillbill.ports.telemetry.model.TelemetryReconciliationResult
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.work.WorkListRepository
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.WorkflowStatsRepository
import skillbill.review.model.FeatureTaskRuntimeWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.model.GoalWorkflowStats
import skillbill.review.model.ImportedReview
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFinishedTelemetry
import java.nio.file.Path
import java.sql.Connection
import java.time.Clock

internal class SQLiteUnitOfWork(
  private val connection: Connection,
  override val dbPath: Path,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val runtimeVersion: String,
  transactionActive: Boolean = false,
) : UnitOfWork {
  override val featureTaskPhaseSettlements: FeatureTaskPhaseSettlementRepository =
    SqliteFeatureTaskPhaseSettlementStore(connection)
  override val operationProposals: OperationProposalRepository = SqliteOperationProposalStore(connection)
  override val reviews: ReviewRepository = SQLiteReviewRepository(connection, clock, runtimeVersion, diagnostics)
  override val learnings: LearningRepository = SQLiteLearningRepository(connection)
  override val lifecycleTelemetry: LifecycleTelemetryRepository =
    LifecycleTelemetryStore(connection, runtimeVersion, diagnostics)
  override val telemetryReconciliation: TelemetryReconciliationRepository =
    SQLiteTelemetryReconciliationRepository(
      connection,
      runtimeVersion,
      diagnostics,
    )
  override val telemetryOutbox: TelemetryOutboxRepository = TelemetryOutboxStore(connection, runtimeVersion)
  override val workflowStates: WorkflowStateRepository =
    WorkflowStateStore(connection, clock, workflowSnapshotValidator, diagnostics, transactionActive)
  override val workList: WorkListRepository = SQLiteWorkListRepository(connection)
  override val goalPlanningPreparations: GoalPlanningPreparationRepository =
    GoalPlanningPreparationStore(connection, diagnostics, transactionActive)
  override val goalRunnerControls: GoalRunnerControlRepository =
    GoalRunnerControlStore(connection)
  override val unaddressedFindings: UnaddressedFindingsRepository = SQLiteUnaddressedFindingsRepository(connection)
  override val agentActivityStamps: AgentActivityStampRepository =
    AgentActivityStampStore(connection)
  override val worktreeEditJournal: WorktreeEditJournalRepository =
    WorktreeEditJournalStore(connection)
  override val standalonePhaseStatuses: StandalonePhaseStatusRepository =
    StandalonePhaseStatusStore(connection, clock, diagnostics, transactionActive)
  override val rejectedOutputDiagnostics: RejectedOutputDiagnosticRepository =
    SqliteRejectedOutputDiagnosticRepository(connection)
  override val rejectedOutputDiagnosticPermissions: RejectedOutputDiagnosticPermissions =
    FileRejectedOutputDiagnosticPermissions(dbPath, diagnostics)

  override fun purgeDecomposedGoal(target: GoalPurgeTarget): GoalPurgeTableCounts {
    val scope = purgeScope(target)
    val counts = linkedMapOf<String, Int>()
    PURGE_WORKFLOW_KEYED_TABLES.forEach { table ->
      counts[table] = deleteWhereIn(table, "workflow_id", scope.workflowIds)
    }
    counts[GOAL_RUN_SESSIONS_TABLE] = deleteMatching(GOAL_RUN_SESSIONS_TABLE, goalRunSessionsFilter(scope))
    counts[GOAL_PLANNING_STORE] =
      scope.parentIds.sumOf { parentId -> goalPlanningPreparations.deleteByGoal(parentId) }
    PURGE_PARENT_KEYED_TABLES.forEach { table ->
      counts[table] = deleteWhereIn(table, "parent_workflow_id", scope.parentIds)
    }
    counts[WORKFLOWS_TABLE] = deleteWhereIn(WORKFLOWS_TABLE, "workflow_id", scope.workflowIds)
    counts[RUNTIME_SESSIONS_TABLE] = deleteMatching(RUNTIME_SESSIONS_TABLE, runtimeSessionsFilter(scope))
    return GoalPurgeTableCounts(counts)
  }

  override fun countDecomposedGoalState(target: GoalPurgeTarget): GoalPurgeTableCounts {
    val scope = purgeScope(target)
    val counts = linkedMapOf<String, Int>()
    PURGE_WORKFLOW_KEYED_TABLES.forEach { table ->
      counts[table] = countWhereIn(table, "workflow_id", scope.workflowIds)
    }
    counts[GOAL_RUN_SESSIONS_TABLE] = countMatching(GOAL_RUN_SESSIONS_TABLE, goalRunSessionsFilter(scope))
    counts[GOAL_PLANNING_STORE] =
      GOAL_PLANNING_TABLES.sumOf { table -> countWhereIn(table, "parent_goal_workflow_id", scope.parentIds) }
    PURGE_PARENT_KEYED_TABLES.forEach { table ->
      counts[table] = countWhereIn(table, "parent_workflow_id", scope.parentIds)
    }
    counts[WORKFLOWS_TABLE] = countWhereIn(WORKFLOWS_TABLE, "workflow_id", scope.workflowIds)
    counts[RUNTIME_SESSIONS_TABLE] = countMatching(RUNTIME_SESSIONS_TABLE, runtimeSessionsFilter(scope))
    return GoalPurgeTableCounts(counts)
  }

  private fun purgeScope(target: GoalPurgeTarget): GoalPurgeScope {
    val workflowIds =
      (
        target.workflowIds + target.parentWorkflowIds +
          target.parentWorkflowIds.flatMap { parentId -> workflowStates.listGoalChildWorkflowIdsByParent(parentId) }
      ).toList()
    return GoalPurgeScope(target.parentWorkflowIds.toList(), workflowIds, sessionIdsOf(workflowIds))
  }

  private fun sessionIdsOf(workflowIds: List<String>): List<String> {
    if (workflowIds.isEmpty()) return emptyList()
    return connection.prepareStatement(
      "SELECT DISTINCT session_id FROM $WORKFLOWS_TABLE " +
        "WHERE session_id != '' AND workflow_id IN (${placeholders(workflowIds)})",
    ).use { statement ->
      statement.bindAll(workflowIds)
      statement.executeQuery().use { rows ->
        buildList { while (rows.next()) add(rows.getString("session_id")) }
      }
    }
  }

  private fun goalRunSessionsFilter(scope: GoalPurgeScope): SqlFilter? {
    val segmentClauses =
      scope.parentIds.map { parentId ->
        SqlFilter("substr(workflow_id, 1, length(?) + 5) = ? || ':seg:'", listOf(parentId, parentId))
      }
    val clauses =
      listOfNotNull(
        inFilter("workflow_id", scope.workflowIds),
        inFilter("parent_workflow_id", scope.parentIds),
      ) + segmentClauses
    return anyOf(clauses)
  }

  private fun runtimeSessionsFilter(scope: GoalPurgeScope): SqlFilter? {
    val owned =
      anyOf(
        listOfNotNull(
          inFilter("session_id", scope.sessionIds),
          inFilter("workflow_id", scope.workflowIds),
          inFilter("goal_parent_workflow_id", scope.parentIds),
        ),
      ) ?: return null
    val survivingReference =
      "NOT EXISTS (SELECT 1 FROM $WORKFLOWS_TABLE w " +
        "WHERE (w.session_id = $RUNTIME_SESSIONS_TABLE.session_id " +
        "OR w.workflow_id = $RUNTIME_SESSIONS_TABLE.workflow_id) " +
        "AND w.workflow_id NOT IN (${placeholders(scope.workflowIds)}))"
    return SqlFilter("(${owned.clause}) AND $survivingReference", owned.args + scope.workflowIds)
  }

  private fun inFilter(
    column: String,
    ids: List<String>,
  ): SqlFilter? = if (ids.isEmpty()) null else SqlFilter("$column IN (${placeholders(ids)})", ids)

  private fun anyOf(filters: List<SqlFilter>): SqlFilter? =
    if (filters.isEmpty()) {
      null
    } else {
      SqlFilter(filters.joinToString(" OR ") { "(${it.clause})" }, filters.flatMap(SqlFilter::args))
    }

  private fun deleteMatching(
    table: String,
    filter: SqlFilter?,
  ): Int {
    if (filter == null) return 0
    return connection.prepareStatement("DELETE FROM $table WHERE ${filter.clause}").use { statement ->
      statement.bindAll(filter.args)
      statement.executeUpdate()
    }
  }

  private fun countMatching(
    table: String,
    filter: SqlFilter?,
  ): Int {
    if (filter == null) return 0
    return connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE ${filter.clause}").use { statement ->
      statement.bindAll(filter.args)
      statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
    }
  }

  private fun deleteWhereIn(
    table: String,
    column: String,
    ids: List<String>,
  ): Int {
    if (ids.isEmpty()) return 0
    return connection.prepareStatement("DELETE FROM $table WHERE $column IN (${placeholders(ids)})").use { statement ->
      statement.bindAll(ids)
      statement.executeUpdate()
    }
  }

  private fun countWhereIn(
    table: String,
    column: String,
    ids: List<String>,
  ): Int {
    if (ids.isEmpty()) return 0
    return connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE $column IN (${placeholders(ids)})")
      .use { statement ->
        statement.bindAll(ids)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
      }
  }

  private fun placeholders(values: List<String>): String = values.joinToString(", ") { "?" }
}

private const val WORKFLOWS_TABLE = "feature_task_workflows"
private const val RUNTIME_SESSIONS_TABLE = "feature_task_runtime_sessions"
private const val GOAL_RUN_SESSIONS_TABLE = "goal_run_sessions"
private const val GOAL_PLANNING_STORE = "goal_planning"

private data class GoalPurgeScope(
  val parentIds: List<String>,
  val workflowIds: List<String>,
  val sessionIds: List<String>,
)

private data class SqlFilter(
  val clause: String,
  val args: List<String>,
)

private val GOAL_PLANNING_TABLES = listOf("goal_planning_preparations", "goal_shared_preplans", "goal_subtask_plans")

private val PURGE_WORKFLOW_KEYED_TABLES =
  listOf(
    "worktree_edit_journal",
    "producer_output_evidence",
    "rejected_output_diagnostics",
    "agent_activity_stamps",
    "feature_task_runtime_worker_leases",
    "feature_task_execution_identities",
    "goal_subtask_events",
    "feature_task_phase_settlements",
  )

private val PURGE_PARENT_KEYED_TABLES = listOf("goal_runner_controls", "goal_issue_progress")

internal class SQLiteUnaddressedFindingsRepository(connection: Connection) : UnaddressedFindingsRepository {
  private val runtime = UnaddressedFindingsRuntime(connection)

  override fun replaceLedgerForPass(
    workflowId: String,
    reviewPassNumber: Int,
    findings: List<UnaddressedFinding>,
  ) = runtime.replaceLedgerForPass(workflowId, reviewPassNumber, findings)

  override fun clearWorkflowLedger(workflowId: String) = runtime.clearWorkflowLedger(workflowId)

  override fun recordOutcomes(outcomes: List<ReviewFindingOutcomeRecord>) = runtime.recordOutcomes(outcomes)

  override fun fetchOutcomes(workflowId: String): List<ReviewFindingOutcomeRecord> = runtime.fetchOutcomes(workflowId)

  override fun fetchLedger(issueKey: String): List<UnaddressedFinding> = runtime.fetchLedger(issueKey)

  override fun fetchWorkflowLedger(workflowId: String): List<UnaddressedFinding> =
    runtime.fetchWorkflowLedger(workflowId)

  override fun workflowIdsForIssue(issueKey: String): List<String> = runtime.workflowIdsForIssue(issueKey)

  override fun issueExists(issueKey: String): Boolean = runtime.issueExists(issueKey)
}

internal class SQLiteTelemetryReconciliationRepository(
  private val connection: Connection,
  private val runtimeVersion: String,
  private val diagnostics: RuntimeDiagnostics,
) : TelemetryReconciliationRepository {
  override fun reconcileStaleSessions(request: TelemetryReconciliationRequest): TelemetryReconciliationResult =
    reconcileStaleTelemetrySessions(connection, request, diagnostics, runtimeVersion)
}

internal class SQLiteWorkflowStatsRepository(
  private val connection: Connection,
  private val diagnostics: RuntimeDiagnostics,
) : WorkflowStatsRepository {
  override fun featureVerifyStats(): FeatureVerifyWorkflowStats =
    ReviewStatsRuntime.featureVerifyStats(connection, diagnostics)

  override fun featureTaskRuntimeStats(): FeatureTaskRuntimeWorkflowStats =
    ReviewStatsRuntime.featureTaskRuntimeStats(connection, diagnostics)

  override fun goalStats(): GoalWorkflowStats = ReviewStatsRuntime.goalStats(connection)
}

internal class SQLiteReviewRepository(
  private val connection: Connection,
  clock: Clock,
  private val runtimeVersion: String,
  private val diagnostics: RuntimeDiagnostics,
) : ReviewRepository,
  WorkflowStatsRepository by SQLiteWorkflowStatsRepository(connection, diagnostics),
  ReviewRunCompletenessRepository by SQLiteReviewRunCompletenessRepository(connection, clock) {
  override fun saveAccounting(record: ReviewAccountingRecord) = upsertReviewAccounting(connection, record)

  override fun loadAccounting(reviewId: String): ReviewAccountingRecord? =
    loadReviewAccounting(connection, reviewId, runtimeVersion, diagnostics)

  override fun saveImportedReview(
    review: ImportedReview,
    sourcePath: String?,
  ) = persistImportedReview(connection, review, sourcePath)

  override fun markOrchestrated(runId: String) {
    connection.prepareStatement(
      "UPDATE review_runs SET orchestrated_run = 1 WHERE review_run_id = ?",
    ).use { statement ->
      statement.bindAll(runId)
      statement.executeUpdate()
    }
  }

  override fun updateReviewFinishedTelemetryState(
    runId: String,
    enabled: Boolean,
    level: String,
    routedSkillPlatformSlugs: Map<String, String>,
  ): ReviewFinishedTelemetry? =
    ReviewStatsRuntime.updateReviewFinishedTelemetryState(
      connection = connection,
      reviewRunId = runId,
      request =
        ReviewFinishedTelemetryUpdateRequest(
          enabled = enabled,
          level = level,
          routedSkillPlatformSlugs = routedSkillPlatformSlugs,
        ),
      runtimeVersion = runtimeVersion,
    )

  override fun recordFeedback(
    request: FeedbackRequest,
    telemetryOptions: FeedbackTelemetryOptions,
    routedSkillPlatformSlugs: Map<String, String>,
  ): ReviewFinishedTelemetry? =
    TriageRuntime.recordFeedbackWithoutTransaction(
      connection,
      request,
      telemetryOptions.copy(routedSkillPlatformSlugs = routedSkillPlatformSlugs),
      runtimeVersion,
    )

  override fun fetchNumberedFindings(runId: String): List<NumberedFinding> =
    ReviewRuntime.fetchNumberedFindings(connection, runId)

  override fun findingExists(
    runId: String,
    findingId: String,
  ): Boolean = ReviewRuntime.findingExists(connection, runId, findingId)

  override fun latestRejectedLearningSourceOutcome(
    runId: String,
    findingId: String,
  ): RejectedLearningSourceOutcome? {
    val placeholders = LearningsRuntime.rejectedFindingOutcomeTypes.joinToString(", ") { "?" }
    return connection.prepareStatement(
      """
      SELECT event_type, note
      FROM feedback_events
      WHERE review_run_id = ? AND finding_id = ? AND event_type IN ($placeholders)
      ORDER BY id DESC
      LIMIT 1
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        listOf(runId, findingId) + LearningsRuntime.rejectedFindingOutcomeTypes,
      )
      statement.executeQuery().use { resultSet ->
        if (resultSet.next()) {
          RejectedLearningSourceOutcome(
            eventType = resultSet.getString("event_type"),
            note = resultSet.getString("note").orEmpty(),
          )
        } else {
          null
        }
      }
    }
  }

  override fun reviewStats(runId: String?): ReviewRepositoryStatsSnapshot =
    ReviewStatsRuntime.statsSnapshot(connection, runId)
}

internal class SQLiteLearningRepository(
  private val connection: Connection,
) : LearningRepository {
  override fun list(status: String): List<LearningRecord> = SQLiteLearningStore.listLearnings(connection, status)

  override fun get(id: Int): LearningRecord = SQLiteLearningStore.getLearning(connection, id)

  override fun resolve(
    repoScopeKey: String?,
    skillName: String?,
  ): LearningResolution {
    val (resolvedRepoScopeKey, resolvedSkillName, rows) =
      SQLiteLearningStore.resolveLearnings(connection, repoScopeKey, skillName)
    return LearningResolution(
      repoScopeKey = resolvedRepoScopeKey,
      skillName = resolvedSkillName,
      records = rows,
    )
  }

  override fun saveSessionLearnings(
    reviewSessionId: String,
    learningsJson: String,
  ) {
    SQLiteLearningStore.saveSessionLearnings(connection, reviewSessionId, learningsJson)
  }

  override fun add(
    request: CreateLearningRequest,
    sourceValidation: LearningSourceValidation,
  ): Int = SQLiteLearningStore.addLearning(connection, request, sourceValidation)

  override fun edit(request: UpdateLearningRequest): LearningRecord =
    SQLiteLearningStore.editLearning(connection, request)

  override fun setStatus(
    id: Int,
    status: String,
  ): LearningRecord = SQLiteLearningStore.setLearningStatus(connection, id, status)

  override fun delete(id: Int) {
    SQLiteLearningStore.deleteLearning(connection, id)
  }
}
