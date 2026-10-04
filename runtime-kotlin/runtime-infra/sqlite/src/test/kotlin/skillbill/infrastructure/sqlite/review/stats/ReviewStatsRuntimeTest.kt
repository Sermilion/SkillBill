package skillbill.infrastructure.sqlite.review.stats

import skillbill.contracts.JsonCodec
import skillbill.contracts.telemetry.TelemetryMeasurementAvailability
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.infrastructure.sqlite.SAMPLE_REVIEW
import skillbill.infrastructure.sqlite.SQLiteLearningStore
import skillbill.infrastructure.sqlite.SQLiteUnitOfWork
import skillbill.infrastructure.sqlite.SqliteTestDiagnostics
import skillbill.infrastructure.sqlite.SqliteTestWorkflowSnapshotValidator
import skillbill.infrastructure.sqlite.review.accounting.persistImportedReview
import skillbill.infrastructure.sqlite.review.stage.TriageRuntime
import skillbill.infrastructure.sqlite.review.stage.addLearning
import skillbill.infrastructure.sqlite.reviewSessionId
import skillbill.infrastructure.sqlite.telemetry.lifecycle.LifecycleTelemetryStore
import skillbill.infrastructure.sqlite.telemetry.lifecycle.listJson
import skillbill.infrastructure.sqlite.telemetry.outbox.TelemetryOutboxStore
import skillbill.infrastructure.sqlite.tempDbConnection
import skillbill.infrastructure.sqlite.testLearningAppliedSessionWire
import skillbill.infrastructure.sqlite.testLearningEntryDto
import skillbill.learnings.model.CreateLearningRequest
import skillbill.learnings.model.LearningScope
import skillbill.learnings.model.LearningSourceValidation
import skillbill.learnings.model.RejectedLearningSourceOutcome
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.model.toReviewFinishedTelemetryPayload
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.model.ImportedReview
import skillbill.review.parsing.ReviewParser
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import java.sql.Connection
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewStatsRuntimeTest {
  @Test
  fun `statsSnapshot summarizes latest outcomes`() {
    val (_, connection) = tempDbConnection("review-stats")
    connection.use {
      val review = importReviewedSample(connection)

      val stats = ReviewStatsRuntime.statsSnapshot(connection, review.reviewRunId).stats
      assertEquals(2, stats.totalFindings)
      assertEquals(1, stats.acceptedFindings)
      assertEquals(1, stats.rejectedFindings)
    }
  }

  @Test
  fun `statsSnapshot aggregates standalone and embedded review health`() {
    val (_, connection) = tempDbConnection("review-health-stats")
    connection.use {
      val review = importReviewedSample(connection)
      seedMixedReviewHealth(connection, review.reviewRunId)

      val health = ReviewStatsRuntime.statsSnapshot(connection, reviewRunId = null).health

      assertEquals(3, health.totalReviewPayloadRecords)
      assertEquals(2, health.includedReviewPayloadRecords)
      assertEquals(1, health.standaloneReviewPayloadRecords)
      assertEquals(1, health.embeddedReviewPayloadRecords)
      assertEquals(1, health.malformedReviewPayloadRecords)
      assertEquals(4, health.totalFindings)
      assertEquals(2.0, health.averageFindings)
      assertEquals(2.0, health.medianFindings)
      assertEquals(2.0, health.p90Findings)
      assertEquals(2, health.acceptedFindings)
      assertEquals(1, health.rejectedFindings)
      assertEquals(1, health.unresolvedFindings)
      assertEquals(0.5, health.acceptedRate)
      assertEquals(0.25, health.rejectedRate)
      assertEquals(0.25, health.unresolvedRate)
      assertEquals(2, health.severityCounts["Major"])
      assertEquals(2, health.confidenceCounts["High"])
      assertEquals(2, health.latestOutcomeCounts["finding_accepted"])
      assertEquals(1, health.latestOutcomeCounts["fix_rejected"])
      assertEquals(1, health.issueCategoryCounts["testing"])
      assertEquals(2, health.platformCounts["kotlin"])
      assertEquals(1, health.scopeCounts["branch_diff"])
      assertEquals(
        mapOf("standalone" to 1, "embedded" to 1, "malformed" to 1, "unknown" to 0),
        health.sourceCounts,
        "the unknown bucket is reported even at zero, so a reader can tell none from cannot-tell",
      )

      val runHealth = ReviewStatsRuntime.statsSnapshot(connection, review.reviewRunId).health
      assertEquals(1, runHealth.totalReviewPayloadRecords)
      assertEquals(1, runHealth.includedReviewPayloadRecords)
      assertEquals(0, runHealth.malformedReviewPayloadRecords)
      assertEquals(
        mapOf("standalone" to 1, "embedded" to 0, "malformed" to 0, "unknown" to 0),
        runHealth.sourceCounts,
      )
    }
  }

  @Test
  fun categorySeverityCrossTabReturnsMajorAndBlocker() {
    val (_, connection) = tempDbConnection("review-category-severity-cross-tab")
    connection.use {
      TelemetryOutboxStore(connection, version = "test-runtime-version").enqueue(
        TelemetryOutboxEvent.REVIEW_FINISHED,
        JsonCodec.mapToJsonString(
          mapOf(
            "review_run_id" to "rvw-cross-tab",
            "platform_slug" to "kotlin",
            "scope_type" to "branch_diff",
            "total_findings" to 4,
            "accepted_findings" to 3,
            "rejected_findings" to 1,
            "unresolved_findings" to 0,
            "accepted_finding_details" to
              listOf(
                mapOf(
                  "finding_id" to "F-001",
                  "issue_category" to "behavior_correctness",
                  "severity" to "Blocker",
                  "confidence" to "High",
                  "outcome_type" to "fix_applied",
                ),
                mapOf(
                  "finding_id" to "F-002",
                  "issue_category" to "behavior_correctness",
                  "severity" to "Major",
                  "confidence" to "High",
                  "outcome_type" to "fix_applied",
                ),
                mapOf(
                  "finding_id" to "F-003",
                  "issue_category" to "testing",
                  "severity" to "Major",
                  "confidence" to "Medium",
                  "outcome_type" to "finding_accepted",
                ),
              ),
            "rejected_finding_details" to
              listOf(
                mapOf(
                  "finding_id" to "F-004",
                  "issue_category" to "behavior_correctness",
                  "severity" to "Major",
                  "confidence" to "Low",
                  "outcome_type" to "false_positive",
                ),
              ),
          ),
        ),
      )

      val health = ReviewStatsRuntime.statsSnapshot(connection, reviewRunId = null).health

      assertEquals(3, health.issueCategoryCounts["behavior_correctness"])
      assertEquals(1, health.issueCategoryCounts["testing"])

      val behaviorCorrectnessSeverity = health.categorySeverityCounts["behavior_correctness"]
      assertEquals(1, behaviorCorrectnessSeverity?.get("Blocker"))
      assertEquals(2, behaviorCorrectnessSeverity?.get("Major"))

      val testingSeverity = health.categorySeverityCounts["testing"]
      assertEquals(1, testingSeverity?.get("Major"))
    }
  }

  @Test
  fun `statsSnapshot derives missing latest outcome counts from finding details`() {
    val (_, connection) = tempDbConnection("review-health-detail-outcomes")
    connection.use {
      TelemetryOutboxStore(connection, version = "test-runtime-version").enqueue(
        TelemetryOutboxEvent.REVIEW_FINISHED,
        JsonCodec.mapToJsonString(
          mapOf(
            "review_run_id" to "rvw-detail-outcomes",
            "platform_slug" to "kotlin",
            "scope_type" to "branch_diff",
            "total_findings" to 2,
            "accepted_findings" to 1,
            "rejected_findings" to 1,
            "unresolved_findings" to 0,
            "accepted_finding_details" to
              listOf(
                mapOf(
                  "finding_id" to "F-001",
                  "issue_category" to "behavior_correctness",
                  "severity" to "Major",
                  "confidence" to "Medium",
                  "outcome_type" to "fix_applied",
                ),
              ),
            "rejected_finding_details" to
              listOf(
                mapOf(
                  "finding_id" to "F-002",
                  "issue_category" to "testing_quality_gate",
                  "severity" to "Minor",
                  "confidence" to "Low",
                  "outcome_type" to "false_positive",
                ),
              ),
          ),
        ),
      )

      val health = ReviewStatsRuntime.statsSnapshot(connection, reviewRunId = null).health

      assertEquals(1, health.latestOutcomeCounts["fix_applied"])
      assertEquals(1, health.latestOutcomeCounts["false_positive"])
      assertEquals(0, health.latestOutcomeCounts["finding_accepted"])
      assertEquals(0, health.latestOutcomeCounts["fix_rejected"])
    }
  }

  @Test
  fun `statsSnapshot returns zero health defaults for empty store`() {
    val (_, connection) = tempDbConnection("review-health-empty")
    connection.use {
      val health = ReviewStatsRuntime.statsSnapshot(connection, reviewRunId = null).health

      assertEquals(0, health.totalReviewPayloadRecords)
      assertEquals(0, health.includedReviewPayloadRecords)
      assertEquals(0.0, health.acceptedRate)
      assertEquals(
        mapOf("standalone" to 0, "embedded" to 0, "malformed" to 0, "unknown" to 0),
        health.sourceCounts,
      )
      assertEquals(mapOf("Blocker" to 0, "Major" to 0, "Minor" to 0), health.severityCounts)
    }
  }

  @Test
  fun `review-finished payload includes cached learnings and full finding details`() {
    val (_, connection) = tempDbConnection("review-finished-payload")
    connection.use {
      val review = importReviewedSample(connection)
      cacheSkillLearning(connection, review.reviewRunId, review.reviewSessionId)

      val anonymousPayload =
        ReviewStatsRuntime.buildReviewFinishedPayload(
          ReviewFinishedPayloadBuildRequest(
            connection = connection,
            reviewRunId = review.reviewRunId,
            level = "anonymous",
          ),
        )
      val fullPayload =
        ReviewStatsRuntime.buildReviewFinishedPayload(
          ReviewFinishedPayloadBuildRequest(
            connection = connection,
            reviewRunId = review.reviewRunId,
            level = "full",
          ),
        )

      assertEquals(1, anonymousPayload.learnings.appliedCount)
      assertEquals("L-001", anonymousPayload.learnings.appliedSummary)
      assertEquals("bill-kotlin-code-review", anonymousPayload.routedSkill)
      assertEquals("unstaged changes", anonymousPayload.reviewScope)
      assertEquals(review.reviewRunId, anonymousPayload.reviewRunId)
      assertEquals(review.reviewRunId, fullPayload.reviewRunId)
      assertEquals("kotlin", anonymousPayload.platformSlug)
      assertEquals("unstaged_changes", anonymousPayload.scopeType)
      assertEquals(1, anonymousPayload.findingStats.rejectedFindings)
      assertEquals(0.5, anonymousPayload.findingStats.rejectedRate)
      assertTrue(anonymousPayload.findingStats.acceptedFindingDetails.isNotEmpty())
      val anonymousRejectedFinding = anonymousPayload.findingStats.rejectedFindingDetails.single()
      val fullRejectedFinding = fullPayload.findingStats.rejectedFindingDetails.single()
      assertEquals("behavior_correctness", anonymousRejectedFinding.issueCategory)
      assertEquals("", anonymousRejectedFinding.description)
      assertEquals("", anonymousRejectedFinding.note)
      assertEquals("Installer prompt wording is inconsistent with the new flow.", fullRejectedFinding.description)
      assertEquals("Intentional wording", fullRejectedFinding.note)
      val anonymousSerializedPayload = anonymousPayload.toReviewFinishedTelemetryPayload().toPayload()
      val fullSerializedPayload = fullPayload.toReviewFinishedTelemetryPayload().toPayload()
      assertSerializedReviewFinishedPayloads(anonymousSerializedPayload, fullSerializedPayload)
      val serializedLearnings = anonymousSerializedPayload["learnings"] as Map<*, *>
      assertEquals(1, serializedLearnings["applied_count"])
      assertEquals(listOf("L-001"), serializedLearnings["applied_references"])
      assertEquals("L-001", serializedLearnings["applied_summary"])
    }
  }

  @Test
  fun `review-finished payload keeps zero finding rejected rate at zero`() {
    val (_, connection) = tempDbConnection("review-zero-finding-payload")
    connection.use {
      val review = ReviewParser.parseReview(ZERO_FINDING_REVIEW.trimIndent())
      persistImportedReview(connection, review, sourcePath = null)

      val payload =
        ReviewStatsRuntime.buildReviewFinishedPayload(
          ReviewFinishedPayloadBuildRequest(
            connection = connection,
            reviewRunId = review.reviewRunId,
            level = "anonymous",
          ),
        ).toReviewFinishedTelemetryPayload().toPayload()

      assertEquals(0, payload["total_findings"])
      assertEquals(0, payload["rejected_findings"])
      assertEquals(0.0, payload["rejected_rate"])
      assertEquals(emptyList<Map<String, Any?>>(), payload["accepted_finding_details"])
      assertEquals(emptyList<Map<String, Any?>>(), payload["rejected_finding_details"])
      assertEquals("unknown", payload["platform_slug"])
      assertEquals("unknown", payload["review_platform"])
      assertEquals("unknown", payload["detected_stack"])
      assertEquals(false, payload["fallback"])
      assertEquals("branch_diff", payload["scope_type"])
    }
  }

  @Test
  fun `feature verify stats payload aggregates persisted session rows`() {
    val (_, connection) = tempDbConnection("workflow-verify-stats")
    connection.use {
      insertFeatureVerifySession(connection)

      val verifyStats = ReviewStatsRuntime.featureVerifyStats(connection, SqliteTestDiagnostics)

      assertEquals(1, verifyStats.totalRuns)
      assertEquals(1, verifyStats.runsWithGapsFound)
      assertEquals(1, verifyStats.historyRelevanceCounts["medium"])
    }
  }

  @Test
  fun `feature task runtime telemetry persists started then finished and enqueues each event once`() {
    val (_, connection) = tempDbConnection("feature-task-runtime-telemetry")
    connection.use {
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      val outbox = TelemetryOutboxStore(connection, version = "test-runtime-version")
      persistFeatureTaskRuntimeTelemetryPair(store)
      store.featureTaskRuntimeFinished(
        featureTaskRuntimeFinishedRecord(),
        level = "anonymous",
      )

      val pending = outbox.listPending(limit = null)
      assertEquals(
        listOf("skillbill_feature_task_runtime_started", "skillbill_feature_task_runtime_finished"),
        pending.map { it.eventName },
      )
      val finishedPayload =
        JsonCodec.parseObjectOrNull(
          pending.single { it.eventName == "skillbill_feature_task_runtime_finished" }.payloadJson,
        )
      assertFeatureTaskRuntimeFinishedPayload(finishedPayload)

      val stats = ReviewStatsRuntime.featureTaskRuntimeStats(connection, SqliteTestDiagnostics)
      assertEquals(1, stats.totalRuns)
      assertEquals(1, stats.finishedRuns)
      assertEquals(1, stats.completedRuns)
      assertEquals(1, stats.completionStatusCounts["completed"])
      assertEquals(3, stats.phaseOutcomeCounts["completed"])
      assertEquals(1, stats.featureSizeCounts["MEDIUM"])
    }
  }

  private fun persistFeatureTaskRuntimeTelemetryPair(store: LifecycleTelemetryStore) {
    store.featureTaskRuntimeStarted(
      FeatureTaskRuntimeStartedRecord(
        sessionId = "ftr-1",
        featureSize = "MEDIUM",
        issueKey = "SKILL-65.1",
        featureName = "lifecycle-telemetry",
      ),
      level = "anonymous",
    )
    store.featureTaskRuntimeFinished(
      featureTaskRuntimeFinishedRecord(),
      level = "anonymous",
    )
  }

  private fun featureTaskRuntimeFinishedRecord(): FeatureTaskRuntimeFinishedRecord =
    FeatureTaskRuntimeFinishedRecord(
      sessionId = "ftr-1",
      completionStatus = "completed",
      completedPhaseIds = listOf("preplan", "plan", "implement"),
      phaseOutcomes = mapOf("preplan" to "completed", "plan" to "completed", "implement" to "completed"),
      lastIncompletePhase = "completed",
      blockedReason = "",
      resolvedBranch = "feat/SKILL-65.1",
    )

  private fun assertFeatureTaskRuntimeFinishedPayload(finishedPayload: Map<String, Any?>?) {
    assertEquals("completed", finishedPayload?.get("completion_status")?.let { it.toString().trim('"') })
    assertEquals("completed", finishedPayload?.get("last_incomplete_phase")?.let { it.toString().trim('"') })
    assertEquals("", finishedPayload?.get("blocked_reason")?.let { it.toString().trim('"') })
    assertEquals(
      TelemetryMeasurementAvailability.UNAVAILABLE_NO_DURABLE_STATE.wireValue,
      finishedPayload?.get("audit_gap_availability")?.let { it.toString().trim('"') },
    )
    assertEquals("null", finishedPayload?.get("audit_gap_iteration_count").toString())
    assertEquals("null", finishedPayload?.get("audit_first_pass_convergence").toString())
    assertEquals(
      TelemetryMeasurementAvailability.UNAVAILABLE_UNSUPPORTED.wireValue,
      finishedPayload?.get("audit_repair_item_availability")?.let { it.toString().trim('"') },
    )
    assertTrue(finishedPayload?.containsKey("audit_recurring_gap_count") != true)
    assertTrue(finishedPayload?.containsKey("audit_new_gap_count") != true)
    assertTrue(finishedPayload?.containsKey("audit_attempted_repair_item_count") != true)
    assertTrue(finishedPayload?.containsKey("audit_resolved_repair_item_count") != true)
  }

  @Test
  fun `feature task runtime stats excludes null token rows from average and counts only valued rows`() {
    val (_, connection) = tempDbConnection("feature-task-runtime-token-aggregation")
    connection.use {
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_task_runtime_sessions (session_id, completion_status, finished_at, estimated_total_tokens)
          VALUES
            ('ftr-token-null', 'completed', '2026-04-23 10:05:00', NULL),
            ('ftr-token-100', 'completed', '2026-04-23 10:06:00', 100),
            ('ftr-token-200', 'completed', '2026-04-23 10:07:00', 200)
          """.trimIndent(),
        )
      }

      val stats = ReviewStatsRuntime.featureTaskRuntimeStats(connection, SqliteTestDiagnostics)

      assertEquals(2, stats.estimatedTokenRunsWithValue)
      assertEquals(150.0, stats.averageEstimatedTotalTokens)
    }
  }

  @Test
  fun `stats reads report malformed persisted values to the diagnostics they were given`() {
    val (dbPath, connection) = tempDbConnection("workflow-stats-diagnostics")
    connection.use {
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_task_runtime_sessions (session_id, completion_status, completed_phase_ids, finished_at)
          VALUES ('ftr-malformed', 'completed', 'not-json', '2026-04-23 10:05:00')
          """.trimIndent(),
        )
        statement.executeUpdate(
          """
          INSERT INTO feature_verify_sessions (session_id, completion_status, gaps_found, started_at, finished_at)
          VALUES ('fvr-malformed', 'completed', 'not-json', 'not-a-time', '2026-04-23 10:05:00')
          """.trimIndent(),
        )
      }
      SqliteTestDiagnostics.reset()
      val reviews =
        SQLiteUnitOfWork(
          connection = connection,
          dbPath = dbPath,
          clock = Clock.systemUTC(),
          diagnostics = SqliteTestDiagnostics,
          workflowSnapshotValidator = SqliteTestWorkflowSnapshotValidator,
          runtimeVersion = "test-runtime-version",
        ).reviews

      reviews.featureTaskRuntimeStats()
      reviews.featureVerifyStats()

      val warnings = SqliteTestDiagnostics.recordedWarnings()
      assertEquals(2, warnings.count { it.contains("degraded review_stats.json_array") })
      assertEquals(1, warnings.count { it.contains("degraded review_stats.duration_seconds") })
    }
  }

  @Test
  fun `feature task runtime stats counts blocked and decomposed completion statuses`() {
    val (_, connection) = tempDbConnection("feature-task-runtime-stats")
    connection.use {
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      store.featureTaskRuntimeStarted(
        FeatureTaskRuntimeStartedRecord("ftr-blocked", "SMALL", "SKILL-1", "blocked-run"),
        level = "anonymous",
      )
      store.featureTaskRuntimeFinished(
        FeatureTaskRuntimeFinishedRecord(
          sessionId = "ftr-blocked",
          completionStatus = "blocked",
          completedPhaseIds = listOf("preplan"),
          phaseOutcomes = mapOf("preplan" to "completed", "plan" to "blocked"),
          lastIncompletePhase = "plan",
          blockedReason = "schema: gate failed",
          resolvedBranch = "",
        ),
        level = "anonymous",
      )
      store.featureTaskRuntimeStarted(
        FeatureTaskRuntimeStartedRecord("ftr-decomposed", "LARGE", "SKILL-2", "decomposed-run"),
        level = "anonymous",
      )
      store.featureTaskRuntimeFinished(
        FeatureTaskRuntimeFinishedRecord(
          sessionId = "ftr-decomposed",
          completionStatus = "decomposed_at_planning",
          completedPhaseIds = listOf("preplan", "plan"),
          phaseOutcomes = mapOf("preplan" to "completed", "plan" to "completed"),
          lastIncompletePhase = "decomposed_at_planning",
          blockedReason = "",
          resolvedBranch = "",
        ),
        level = "anonymous",
      )

      val stats = ReviewStatsRuntime.featureTaskRuntimeStats(connection, SqliteTestDiagnostics)
      assertEquals(2, stats.totalRuns)
      assertEquals(1, stats.blockedRuns)
      assertEquals(1, stats.decomposedRuns)
      assertEquals(1, stats.completionStatusCounts["blocked"])
      assertEquals(1, stats.completionStatusCounts["decomposed_at_planning"])
      assertEquals(1, stats.phaseOutcomeCounts["blocked"])

      val payloads =
        telemetryPayloads(
          TelemetryOutboxStore(connection, version = "test-runtime-version").listPending(limit = null),
          "skillbill_feature_task_runtime_finished",
        )
      val blockedPayload = payloads.single { it["session_id"] == "ftr-blocked" }
      assertEquals("plan", blockedPayload["last_incomplete_phase"])
      assertEquals("schema: gate failed", blockedPayload["blocked_reason"])
      val decomposedPayload = payloads.single { it["session_id"] == "ftr-decomposed" }
      assertEquals("decomposed_at_planning", decomposedPayload["last_incomplete_phase"])
      assertEquals("", decomposedPayload["blocked_reason"])
    }
  }
}

private fun importReviewedSample(connection: Connection): ImportedReview {
  val review = ReviewParser.parseReview(SAMPLE_REVIEW.trimIndent())
  persistImportedReview(connection, review, sourcePath = null)
  recordFindingOutcome(connection, review.reviewRunId, "F-001", "finding_accepted", "")
  recordFindingOutcome(connection, review.reviewRunId, "F-002", "fix_rejected", "Intentional wording")
  return review
}

private fun recordFindingOutcome(
  connection: Connection,
  reviewRunId: String,
  findingId: String,
  eventType: String,
  note: String,
) {
  TriageRuntime.recordFeedbackWithoutTransaction(
    connection = connection,
    request =
      FeedbackRequest(
        reviewRunId = reviewRunId,
        findingIds = listOf(findingId),
        eventType = eventType,
        note = note,
      ),
    telemetryOptions = FeedbackTelemetryOptions(enabled = false, level = "anonymous"),
    runtimeVersion = "test-runtime-version",
  )
}

private fun telemetryPayloads(
  records: List<TelemetryOutboxRecord>,
  eventName: String,
): List<Map<String, Any?>> =
  records.filter { it.eventName == eventName }.map { record ->
    JsonCodec.parseObjectOrNull(record.payloadJson)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
      ?: emptyMap()
  }

private fun assertSerializedReviewFinishedPayloads(
  anonymousPayload: Map<String, Any?>,
  fullPayload: Map<String, Any?>,
) {
  val anonymousRejectedFinding = (anonymousPayload["rejected_finding_details"] as List<*>).single() as Map<*, *>
  val fullRejectedFinding = (fullPayload["rejected_finding_details"] as List<*>).single() as Map<*, *>
  assertEquals(false, "description" in anonymousRejectedFinding)
  assertEquals(false, "note" in anonymousRejectedFinding)
  assertEquals("behavior_correctness", anonymousRejectedFinding["issue_category"])
  assertEquals(1, anonymousPayload["rejected_findings"])
  assertEquals(0.5, anonymousPayload["rejected_rate"])
  assertEquals("kotlin", anonymousPayload["platform_slug"])
  assertEquals("kotlin", anonymousPayload["review_platform"])
  assertEquals("kotlin", anonymousPayload["detected_stack"])
  assertEquals(false, anonymousPayload["fallback"])
  assertEquals("unstaged_changes", anonymousPayload["scope_type"])
  assertEquals(
    "Installer prompt wording is inconsistent with the new flow.",
    fullRejectedFinding["description"],
  )
  assertEquals("Intentional wording", fullRejectedFinding["note"])
}

private fun cacheSkillLearning(
  connection: Connection,
  reviewRunId: String,
  reviewSessionId: String,
) {
  val learningId =
    SQLiteLearningStore.addLearning(
      connection = connection,
      request =
        CreateLearningRequest(
          scope = LearningScope.SKILL,
          scopeKey = "bill-kotlin-code-review",
          title = "Match wording",
          ruleText = "Keep wording aligned with routed skill output.",
          rationale = "",
          sourceReviewRunId = reviewRunId,
          sourceFindingId = "F-002",
        ),
      sourceValidation =
        LearningSourceValidation(
          reviewRunId = reviewRunId,
          findingId = "F-002",
          rejectedOutcome = RejectedLearningSourceOutcome("fix_rejected", "Intentional wording"),
        ),
    )
  val learningDto = testLearningEntryDto(SQLiteLearningStore.getLearning(connection, learningId))
  SQLiteLearningStore.saveSessionLearnings(
    connection = connection,
    reviewSessionId = reviewSessionId,
    learningsJson =
      JsonCodec.mapToJsonString(
        testLearningAppliedSessionWire(null, listOf(learningDto)).toPayload().toMutableMap().apply {
          put("scope_counts", mapOf("global" to 0, "repo" to 0, "skill" to 1))
        },
      ),
  )
}

private const val ZERO_FINDING_REVIEW: String =
  """
  Routed to: bill-code-review
  Review session ID: rvs-20260402-zero
  Review run ID: rvw-20260402-zero
  Detected review scope: branch diff
  Detected stack: unknown
  Execution mode: inline

  ### 2. Risk Register
  No findings.
  """

private fun seedMixedReviewHealth(
  connection: Connection,
  reviewRunId: String,
) {
  ReviewStatsRuntime.updateReviewFinishedTelemetryState(
    connection,
    reviewRunId,
    request =
      ReviewFinishedTelemetryUpdateRequest(
        enabled = true,
        level = "full",
      ),
    runtimeVersion = "test-runtime-version",
  )
  val emittedRows =
    TelemetryOutboxStore(connection, version = "test-runtime-version")
      .listPending()
      .filter { record -> record.eventName == "skillbill_review_finished" }
  assertTrue(emittedRows.isNotEmpty())
  assertTrue(emittedRows.all { record -> record.skillBillVersion == "test-runtime-version" })
  insertFeatureImplementSessionWithChildSteps(
    connection,
    FeatureImplementSessionFixture(
      sessionId = "fis-review-child",
      featureSize = "LARGE",
      completionStatus = "completed",
      childSteps = listOf(embeddedReviewChildStep()),
    ),
  )
  insertMalformedFeatureImplementChildSteps(connection, "fis-malformed-review-child")
}

private fun embeddedReviewChildStep(): Map<String, Any?> =
  mapOf(
    "skill" to "bill-kotlin-code-review",
    "review_run_id" to "rvw-embedded",
    "review_session_id" to "rvs-embedded",
    "platform_slug" to "kotlin",
    "scope_type" to "branch_diff",
    "total_findings" to 2,
    "accepted_findings" to 1,
    "rejected_findings" to 0,
    "unresolved_findings" to 1,
    "accepted_finding_details" to
      listOf(
        mapOf(
          "finding_id" to "F-EMBEDDED-1",
          "issue_category" to "testing",
          "severity" to "Major",
          "confidence" to "high",
          "outcome_type" to "finding_accepted",
        ),
      ),
    "rejected_finding_details" to emptyList<Map<String, Any?>>(),
    "latest_outcome_counts" to mapOf("finding_accepted" to 1),
  )

private data class FeatureImplementSessionFixture(
  val sessionId: String,
  val featureSize: String,
  val completionStatus: String,
  val childSteps: List<Map<String, Any?>>,
  val finishedAt: String? = "2026-04-23 10:05:00",
)

private fun insertFeatureImplementSessionWithChildSteps(
  connection: Connection,
  fixture: FeatureImplementSessionFixture,
) {
  connection.prepareStatement(
    """
    INSERT INTO feature_implement_sessions (
      session_id,
      source,
      feature_size,
      completion_status,
      child_steps_json,
      started_at,
      finished_at
    ) VALUES (?, 'production', ?, ?, ?, '2026-04-23 10:00:00', ?)
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, fixture.sessionId)
    statement.setString(2, fixture.featureSize)
    statement.setString(3, fixture.completionStatus)
    statement.setString(4, listJson(fixture.childSteps))
    statement.setString(5, fixture.finishedAt)
    statement.executeUpdate()
  }
}

private fun insertMalformedFeatureImplementChildSteps(
  connection: Connection,
  sessionId: String,
) {
  connection.createStatement().use { statement ->
    statement.executeUpdate(
      """
      INSERT INTO feature_implement_sessions (
        session_id, source, feature_size, completion_status, child_steps_json, started_at, finished_at
      ) VALUES (
        '$sessionId', 'production', 'SMALL', 'completed', '{not json',
        '2026-04-23 10:00:00', '2026-04-23 10:01:00'
      )
      """.trimIndent(),
    )
  }
}

private fun insertFeatureVerifySession(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.executeUpdate(
      """
      INSERT INTO feature_verify_sessions (
        session_id,
        acceptance_criteria_count,
        rollout_relevant,
        spec_summary,
        feature_flag_audit_performed,
        review_iterations,
        audit_result,
        completion_status,
        gaps_found,
        history_relevance,
        history_helpfulness,
        started_at,
        finished_at
      ) VALUES (
        'fvr-1',
        2,
        1,
        'Verify review domain.',
        1,
        2,
        'all_pass',
        'completed',
        '["gap"]',
        'medium',
        'high',
        '2026-04-23 10:00:00',
        '2026-04-23 10:05:00'
      )
      """.trimIndent(),
    )
  }

  @Test
  fun mapperEmitsSeverityCounts() {
    val (_, connection) = tempDbConnection("review-contract-mapper-cross-tab")
    connection.use {
      TelemetryOutboxStore(connection, version = "test-runtime-version").enqueue(
        TelemetryOutboxEvent.REVIEW_FINISHED,
        JsonCodec.mapToJsonString(
          mapOf(
            "review_run_id" to "rvw-contract-mapper",
            "platform_slug" to "kotlin",
            "scope_type" to "branch_diff",
            "total_findings" to 3,
            "accepted_findings" to 2,
            "rejected_findings" to 1,
            "unresolved_findings" to 0,
            "accepted_finding_details" to
              listOf(
                mapOf(
                  "finding_id" to "F-001",
                  "issue_category" to "behavior_correctness",
                  "severity" to "Blocker",
                  "confidence" to "High",
                  "outcome_type" to "fix_applied",
                ),
                mapOf(
                  "finding_id" to "F-002",
                  "issue_category" to "behavior_correctness",
                  "severity" to "Major",
                  "confidence" to "High",
                  "outcome_type" to "fix_applied",
                ),
              ),
            "rejected_finding_details" to
              listOf(
                mapOf(
                  "finding_id" to "F-003",
                  "issue_category" to "testing",
                  "severity" to "Major",
                  "confidence" to "Medium",
                  "outcome_type" to "false_positive",
                ),
              ),
          ),
        ),
      )

      val health = ReviewStatsRuntime.statsSnapshot(connection, reviewRunId = null).health

      val categorySeverityCounts = health.categorySeverityCounts
      assertTrue(categorySeverityCounts.isNotEmpty())

      val behaviorCorrectnessSeverity = categorySeverityCounts["behavior_correctness"]
      assertEquals(1, behaviorCorrectnessSeverity?.get("Blocker"))
      assertEquals(1, behaviorCorrectnessSeverity?.get("Major"))

      val testingSeverity = categorySeverityCounts["testing"]
      assertEquals(1, testingSeverity?.get("Major"))
    }
  }
}
