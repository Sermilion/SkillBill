package skillbill.infrastructure.sqlite.review.stage

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.infrastructure.sqlite.SAMPLE_REVIEW
import skillbill.infrastructure.sqlite.SQLiteReviewRunCompletenessRepository
import skillbill.infrastructure.sqlite.SqliteTestDiagnostics
import skillbill.infrastructure.sqlite.apply
import skillbill.infrastructure.sqlite.core.migration.DatabaseMigrations
import skillbill.infrastructure.sqlite.review.accounting.persistImportedReview
import skillbill.infrastructure.sqlite.review.stats.ReviewFinishedPayloadBuildRequest
import skillbill.infrastructure.sqlite.review.stats.ReviewStatsRuntime
import skillbill.infrastructure.sqlite.telemetry.lifecycle.LifecycleTelemetryStore
import skillbill.infrastructure.sqlite.telemetry.outbox.TelemetryOutboxStore
import skillbill.infrastructure.sqlite.tempDbConnection
import skillbill.ports.telemetry.model.toReviewFinishedTelemetryPayload
import skillbill.review.context.model.claim.ReviewClaimVerdictAdmission
import skillbill.review.context.model.launch.ReviewSpecAdjudicationAdmission
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.model.ImportedReview
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.REVIEW_FINISHED_LEGACY_CONTRACT_VERSION
import skillbill.review.model.REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.model.ReviewSeverityAdjustmentDirection
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageDegradationReason
import skillbill.review.model.ReviewStageDegradationSelectionRequest
import skillbill.review.model.ReviewStageReached
import skillbill.review.parsing.ReviewParser
import skillbill.review.stage.ReviewStageDegradationSelection
import java.sql.Connection
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewStageTelemetryTest {
  @Test
  fun `review finished and statsSnapshot counts match durable rows and ignore worker self-report`() {
    val (_, connection) = tempDbConnection("stage-metrics-mixed")
    connection.use {
      val review = importReviewedSample(it)
      setExecutionMode(it, review.reviewRunId, "inline")
      seedMixedVerdicts(it, review.reviewRunId)
      val workerSelfReport =
        mapOf(
          "confirmed" to 99,
          "refuted" to 0,
          "refutation_rate" to 0.01,
        )
      val payload =
        ReviewStatsRuntime.buildReviewFinishedPayload(
          ReviewFinishedPayloadBuildRequest(connection = it, reviewRunId = review.reviewRunId),
        )
          .toReviewFinishedTelemetryPayload()
          .toPayload()
      val snapshot = ReviewStatsRuntime.statsSnapshot(it, review.reviewRunId)
      assertEquals(1, payload.nestedInt("verification", "claim_verdict", "confirmed"))
      assertEquals(1, payload.nestedInt("verification", "claim_verdict", "refuted"))
      assertEquals(1, payload.nestedInt("verification", "claim_verdict", "unresolved"))
      assertEquals(1, payload.nestedInt("rejected_verdict_counts", "uncited_refutations"))
      assertEquals(1, payload.nestedInt("rejected_verdict_counts", "uncited_downgrades"))
      assertEquals(1, payload.nestedInt("rejected_verdict_counts", "finding_mutations"))
      assertEquals(1, payload.nestedInt("severity_adjustment_counts", "raised"))
      assertEquals(1, payload.nestedInt("severity_adjustment_counts", "lowered"))
      assertEquals("inline", payload["resolved_tier"])
      assertEquals(0.5, payload.nestedDouble("refutation_rate_by_stage", "verification"))
      assertEquals(
        snapshot.stageMetrics?.verification?.confirmed,
        payload.nestedInt("verification", "claim_verdict", "confirmed"),
      )
      assertEquals(snapshot.stageMetrics?.rejectedVerdictCounts?.uncitedRefutations, 1)
      assertTrue(workerSelfReport["confirmed"] != payload.nestedInt("verification", "claim_verdict", "confirmed"))
    }
  }

  @Test
  fun `inline and delegated refutation rates stay split by resolved tier`() {
    val (_, connection) = tempDbConnection("stage-metrics-tiers")
    connection.use {
      seedTierRun(it, "rvw-inline", "inline", refuted = 1, total = 2)
      seedTierRun(it, "rvw-delegated", "delegated", refuted = 2, total = 2)
      val snapshot = ReviewStatsRuntime.statsSnapshot(it, reviewRunId = null)
      val inlineRate = snapshot.stageMetricsByTier.getValue("inline").verificationRefutationRate
      val delegatedRate = snapshot.stageMetricsByTier.getValue("delegated").verificationRefutationRate
      assertEquals(0.5, inlineRate)
      assertEquals(1.0, delegatedRate)
      assertTrue(inlineRate != delegatedRate)
    }
  }

  @Test
  fun `spec_context none writes a degradation outbox row`() {
    val (_, connection) = tempDbConnection("stage-degrade-spec-none")
    connection.use {
      val repository = SQLiteReviewRunCompletenessRepository(it, Clock.systemUTC())
      repository.recordSpecProjectionReference(
        "rvw-spec-none",
        ReviewSpecProjectionReference(absenceReason = "no_spec_found"),
      )
      emitDegradations(it, "rvw-spec-none")
      val reasons = degradationReasons(it)
      assertTrue(ReviewStageDegradationReason.SPEC_CONTEXT_NONE.wireValue in reasons)
    }
  }

  @Test
  fun `skipped adjudication writes a degradation outbox row`() {
    val (_, connection) = tempDbConnection("stage-degrade-adj-skip")
    connection.use {
      val repository = SQLiteReviewRunCompletenessRepository(it, Clock.systemUTC())
      repository.recordSpecProjectionReference(
        "rvw-adj-skip",
        ReviewSpecProjectionReference(specPath = "spec.md", contentDigest = "abc"),
      )
      repository.recordStageBoundary(
        "rvw-adj-skip",
        ReviewStageBoundary(ReviewStage.VERIFICATION, ReviewStageReached.NOT_REACHED, "2026-08-14T08:00:00Z"),
      )
      emitDegradations(it, "rvw-adj-skip")
      val reasons = degradationReasons(it)
      assertTrue(ReviewStageDegradationReason.ADJUDICATION_SKIPPED.wireValue in reasons)
    }
  }

  @Test
  fun `worker launch or return failure writes a degradation outbox row`() {
    val (_, connection) = tempDbConnection("stage-degrade-worker")
    connection.use {
      val repository = SQLiteReviewRunCompletenessRepository(it, Clock.systemUTC())
      repository.recordFindingVerdicts(
        "rvw-worker-fail",
        listOf(
          ReviewFindingVerdict(
            stage = ReviewStage.VERIFICATION,
            findingRef = "F-001",
            claimVerdict = ReviewClaimVerdict.UNRESOLVED,
            recordedAt = "2026-08-14T08:00:00Z",
            rejectionReason = "agent process failed to spawn",
          ),
        ),
      )
      emitDegradations(it, "rvw-worker-fail")
      val reasons = degradationReasons(it)
      assertTrue(ReviewStageDegradationReason.WORKER_PROCESS_FAILED.wireValue in reasons)
    }
  }

  @Test
  fun `unparseable verification output is a worker failure and unsettled admission is not`() {
    val (_, connection) = tempDbConnection("stage-degrade-unparseable")
    connection.use {
      val repository = SQLiteReviewRunCompletenessRepository(it, Clock.systemUTC())
      repository.recordFindingVerdicts(
        "rvw-unparseable",
        listOf(
          ReviewFindingVerdict(
            stage = ReviewStage.VERIFICATION,
            findingRef = "F-001",
            claimVerdict = ReviewClaimVerdict.UNRESOLVED,
            recordedAt = "2026-08-14T08:00:00Z",
            rejectionReason = "unparseable verification output",
          ),
        ),
      )
      emitDegradations(it, "rvw-unparseable")
      assertTrue(ReviewStageDegradationReason.WORKER_OUTPUT_UNUSABLE.wireValue in degradationReasons(it))
      repository.recordFindingVerdicts(
        "rvw-unsettled",
        listOf(
          ReviewFindingVerdict(
            stage = ReviewStage.VERIFICATION,
            findingRef = "F-001",
            claimVerdict = ReviewClaimVerdict.UNRESOLVED,
            recordedAt = "2026-08-14T08:00:00Z",
            rejectionReason = ReviewClaimVerdictAdmission.UNSETTLED,
          ),
        ),
      )
      emitDegradations(it, "rvw-unsettled")
      val unsettledReasons =
        TelemetryOutboxStore(it, version = "test-runtime-version").listPending(null)
          .filter { record -> record.eventName == TelemetryOutboxEvent.REVIEW_STAGE_DEGRADATION.wireValue }
          .mapNotNull { record ->
            val payload =
              JsonCodec.parseObjectOrNull(record.payloadJson)
                ?.let { node -> JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(node)) }
                ?: return@mapNotNull null
            if (payload["review_run_id"] != "rvw-unsettled") return@mapNotNull null
            payload["reason"] as? String
          }
      assertTrue(ReviewStageDegradationReason.WORKER_OUTPUT_UNUSABLE.wireValue !in unsettledReasons)
    }
  }

  @Test
  fun `a stage without a reached boundary writes a degradation outbox row`() {
    val (_, connection) = tempDbConnection("stage-degrade-boundary")
    connection.use {
      val repository = SQLiteReviewRunCompletenessRepository(it, Clock.systemUTC())
      repository.recordReviewPassClaims(
        "rvw-boundary",
        listOf(claim("F-001")),
      )
      emitDegradations(it, "rvw-boundary")
      val reasons = degradationReasons(it)
      assertTrue(ReviewStageDegradationReason.STAGE_BOUNDARY_UNREACHED.wireValue in reasons)
    }
  }

  @Test
  fun `legacy 1_8_0 review_finished is rewritten in band from durable rows`() {
    val (_, connection) = tempDbConnection("stage-legacy-rewrite")
    connection.use {
      val review = importReviewedSample(it)
      setExecutionMode(it, review.reviewRunId, "delegated")
      seedMixedVerdicts(it, review.reviewRunId)
      val legacyRestampId =
        TelemetryOutboxStore(it, version = "test-runtime-version").enqueue(
          TelemetryOutboxEvent.REVIEW_STAGE_DEGRADATION,
          JsonCodec.mapToJsonString(
            mapOf(SharedPayloadKeys.CONTRACT_VERSION to REVIEW_FINISHED_LEGACY_CONTRACT_VERSION),
          ),
        )
      it.installLegacyRestampProbe(legacyRestampId)
      TelemetryOutboxStore(it, version = "test-runtime-version").enqueue(
        TelemetryOutboxEvent.REVIEW_FINISHED,
        JsonCodec.mapToJsonString(
          mapOf(
            "event_name" to "skillbill_review_finished",
            "contract_version" to REVIEW_FINISHED_LEGACY_CONTRACT_VERSION,
            "review_run_id" to review.reviewRunId,
            "total_findings" to 2,
            "accepted_findings" to 1,
            "rejected_findings" to 1,
            "unresolved_findings" to 0,
          ),
        ),
      )
      val snapshot = ReviewStatsRuntime.statsSnapshot(it, review.reviewRunId)
      assertTrue(snapshot.health.malformedReviewPayloadRecords == 0)
      assertEquals(1, snapshot.stageMetrics?.verification?.confirmed)
      assertEquals("delegated", snapshot.stageMetrics?.resolvedTier)
      val storedAfterRead =
        TelemetryOutboxStore(it, version = "test-runtime-version").listPending(null).single {
          it.eventName == "skillbill_review_finished"
        }
      val storedAfterReadPayload = telemetryOutboxPayload(storedAfterRead.payloadJson)
      assertEquals(REVIEW_FINISHED_LEGACY_CONTRACT_VERSION, storedAfterReadPayload["contract_version"])
      assertLegacyReviewFinishedMigration(it, review, legacyRestampId)
    }
  }

  @Test
  fun `legacy finished payload with an unknown review_run_id is quarantined instead of crashing stats`() {
    val (_, connection) = tempDbConnection("stage-legacy-unknown-run")
    connection.use {
      TelemetryOutboxStore(it, version = "test-runtime-version").enqueue(
        TelemetryOutboxEvent.REVIEW_FINISHED,
        JsonCodec.mapToJsonString(
          mapOf(
            "event_name" to "skillbill_review_finished",
            "contract_version" to REVIEW_FINISHED_LEGACY_CONTRACT_VERSION,
            "review_run_id" to "rvw-missing",
            "total_findings" to 1,
          ),
        ),
      )
      val snapshot = ReviewStatsRuntime.statsSnapshot(it, reviewRunId = null)
      assertEquals(1, snapshot.health.malformedReviewPayloadRecords)
      applyLegacyTelemetryLedgerMigration(it)
      val leftover =
        TelemetryOutboxStore(it, version = "test-runtime-version").listPending(null).single { record ->
          record.eventName == "skillbill_review_finished"
        }
      val leftoverPayload =
        JsonCodec.parseObjectOrNull(leftover.payloadJson)
          ?.let { node -> JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(node)) }
          ?: emptyMap()
      assertEquals(REVIEW_FINISHED_LEGACY_CONTRACT_VERSION, leftoverPayload["contract_version"])
      assertTrue(
        TelemetryOutboxStore(it, version = "test-runtime-version").listPending(null).none { record ->
          record.eventName == TelemetryOutboxEvent.REVIEW_FINISHED_LEGACY_REGENERATED.wireValue
        },
      )
    }
  }

  @Test
  fun `a timed-out worker and a launch-budget refusal are recorded as causes of their own`() {
    assertWorkerFailureCause(
      "stage-degrade-timeout",
      rejectionReason = "agent timed out",
      expected = ReviewStageDegradationReason.WORKER_TIMED_OUT,
    )
    assertWorkerFailureCause(
      "stage-degrade-launch-budget",
      rejectionReason = "verification launch exceeded max_lane_launch_bytes",
      expected = ReviewStageDegradationReason.WORKER_LAUNCH_BUDGET_EXCEEDED,
    )
  }

  private fun assertWorkerFailureCause(
    dbName: String,
    rejectionReason: String,
    expected: ReviewStageDegradationReason,
  ) {
    val (_, connection) = tempDbConnection(dbName)
    connection.use {
      SQLiteReviewRunCompletenessRepository(it, Clock.systemUTC()).recordFindingVerdicts(
        dbName,
        listOf(
          ReviewFindingVerdict(
            stage = ReviewStage.VERIFICATION,
            findingRef = "F-001",
            claimVerdict = ReviewClaimVerdict.UNRESOLVED,
            recordedAt = "2026-08-14T08:00:00Z",
            rejectionReason = rejectionReason,
          ),
        ),
      )
      emitDegradations(it, dbName)
      val reasons = degradationReasons(it)
      assertTrue(expected.wireValue in reasons, "'$rejectionReason' was not recorded as ${expected.wireValue}")
      val otherWorkerCauses =
        listOf(
          ReviewStageDegradationReason.WORKER_PROCESS_FAILED,
          ReviewStageDegradationReason.WORKER_TIMED_OUT,
          ReviewStageDegradationReason.WORKER_OUTPUT_UNUSABLE,
          ReviewStageDegradationReason.WORKER_LAUNCH_BUDGET_EXCEEDED,
          ReviewStageDegradationReason.WORKER_LAUNCH_OR_RETURN_FAILED,
        ).filterNot { cause -> cause == expected }.map(ReviewStageDegradationReason::wireValue)
      assertTrue(
        otherWorkerCauses.none { cause -> cause in reasons },
        "'$rejectionReason' must reach an operator as one cause, not several: $reasons",
      )
    }
  }

  private fun emitDegradations(
    connection: Connection,
    reviewRunId: String,
  ) {
    val repository = SQLiteReviewRunCompletenessRepository(connection, Clock.systemUTC())
    val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
    ReviewStageDegradationSelection.select(
      ReviewStageDegradationSelectionRequest(
        reviewRunId = reviewRunId,
        spec = repository.fetchSpecProjectionReference(reviewRunId),
        boundaries = repository.fetchStageBoundaries(reviewRunId),
        verdicts = repository.fetchFindingVerdicts(reviewRunId),
        claims = repository.fetchReviewPassClaims(reviewRunId),
      ),
    ).forEach(store::reviewStageDegradation)
  }

  private fun applyLegacyTelemetryLedgerMigration(
    connection: Connection,
    forcePending: Boolean = true,
  ) {
    if (forcePending) {
      connection.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM schema_migrations WHERE name = 'migrate-legacy-telemetry-outbox'")
      }
    }
    DatabaseMigrations.apply(connection)
  }

  private fun restampUpdateCount(connection: Connection): Int =
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT update_count FROM legacy_restamp_probe").use { rows ->
        check(rows.next())
        rows.getInt(1)
      }
    }

  private fun degradationReasons(connection: Connection): List<String> =
    TelemetryOutboxStore(connection, version = "test-runtime-version").listPending(null)
      .filter { it.eventName == TelemetryOutboxEvent.REVIEW_STAGE_DEGRADATION.wireValue }
      .map { record ->
        Regex(""""reason"\s*:\s*"([^"]+)"""").find(record.payloadJson)?.groupValues?.get(1).orEmpty()
      }

  private fun seedMixedVerdicts(
    connection: Connection,
    reviewRunId: String,
  ) {
    SQLiteReviewRunCompletenessRepository(connection, Clock.systemUTC()).recordFindingVerdicts(
      reviewRunId,
      mixedVerdicts(),
    )
  }

  private fun mixedVerdicts(): List<ReviewFindingVerdict> =
    listOf(
      ReviewFindingVerdict(
        stage = ReviewStage.VERIFICATION,
        findingRef = "F-001",
        claimVerdict = ReviewClaimVerdict.CONFIRMED,
        recordedAt = "2026-08-14T08:00:00Z",
      ),
      ReviewFindingVerdict(
        stage = ReviewStage.VERIFICATION,
        findingRef = "F-002",
        claimVerdict = ReviewClaimVerdict.REFUTED,
        citations = listOf(ReviewFindingCitation("src/Main.kt", 1)),
        recordedAt = "2026-08-14T08:00:00Z",
      ),
      ReviewFindingVerdict(
        stage = ReviewStage.VERIFICATION,
        findingRef = "F-003",
        claimVerdict = ReviewClaimVerdict.UNRESOLVED,
        recordedAt = "2026-08-14T08:00:00Z",
        rejectionReason = ReviewClaimVerdictAdmission.UNCITED_REFUTATION,
      ),
      ReviewFindingVerdict(
        stage = ReviewStage.ADJUDICATION,
        findingRef = "F-001",
        claimVerdict = ReviewClaimVerdict.CONFIRMED,
        scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
        recordedAt = "2026-08-14T08:01:00Z",
        rejectionReason = ReviewSpecAdjudicationAdmission.UNCITED_DOWNGRADE,
      ),
      ReviewFindingVerdict(
        stage = ReviewStage.ADJUDICATION,
        findingRef = "F-004",
        claimVerdict = ReviewClaimVerdict.UNRESOLVED,
        recordedAt = "2026-08-14T08:01:00Z",
        rejectionReason = ReviewClaimVerdictAdmission.ALTERED_CLAIM,
      ),
      ReviewFindingVerdict(
        stage = ReviewStage.ADJUDICATION,
        findingRef = "F-005",
        claimVerdict = ReviewClaimVerdict.CONFIRMED,
        scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
        severityAdjustment =
          ReviewSeverityAdjustment(
            ReviewSeverityAdjustmentDirection.RAISE,
            "constraint",
          ),
        recordedAt = "2026-08-14T08:01:00Z",
      ),
      ReviewFindingVerdict(
        stage = ReviewStage.ADJUDICATION,
        findingRef = "F-006",
        claimVerdict = ReviewClaimVerdict.CONFIRMED,
        scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
        severityAdjustment =
          ReviewSeverityAdjustment(
            ReviewSeverityAdjustmentDirection.LOWER,
            "non-goal",
          ),
        recordedAt = "2026-08-14T08:01:00Z",
      ),
    )

  private fun seedTierRun(
    connection: Connection,
    runId: String,
    mode: String,
    refuted: Int,
    total: Int,
  ) {
    val repository = SQLiteReviewRunCompletenessRepository(connection, Clock.systemUTC())
    val verdicts =
      (1..total).map { index ->
        ReviewFindingVerdict(
          stage = ReviewStage.VERIFICATION,
          findingRef = "F-00$index",
          claimVerdict = if (index <= refuted) ReviewClaimVerdict.REFUTED else ReviewClaimVerdict.CONFIRMED,
          citations = if (index <= refuted) listOf(ReviewFindingCitation("src/Main.kt", 1)) else emptyList(),
          recordedAt = "2026-08-14T08:00:00Z",
        )
      }
    repository.recordFindingVerdicts(runId, verdicts)
    repository.recordReviewPassClaims(runId, (1..total).map { claim("F-00$it") })
    setExecutionMode(connection, runId, mode)
  }

  private fun importReviewedSample(connection: Connection): ImportedReview {
    val review = ReviewParser.parseReview(SAMPLE_REVIEW.trimIndent())
    persistImportedReview(connection, review, sourcePath = null)
    TriageRuntime.recordFeedbackWithoutTransaction(
      connection = connection,
      request =
        FeedbackRequest(
          reviewRunId = review.reviewRunId,
          findingIds = listOf("F-001"),
          eventType = "finding_accepted",
          note = "",
        ),
      telemetryOptions = FeedbackTelemetryOptions(enabled = false, level = "anonymous"),
      runtimeVersion = "test-runtime-version",
    )
    TriageRuntime.recordFeedbackWithoutTransaction(
      connection = connection,
      request =
        FeedbackRequest(
          reviewRunId = review.reviewRunId,
          findingIds = listOf("F-002"),
          eventType = "fix_rejected",
          note = "Intentional wording",
        ),
      telemetryOptions = FeedbackTelemetryOptions(enabled = false, level = "anonymous"),
      runtimeVersion = "test-runtime-version",
    )
    return review
  }

  private fun setExecutionMode(
    connection: Connection,
    reviewRunId: String,
    mode: String,
  ) {
    connection.prepareStatement(
      "UPDATE review_runs SET execution_mode = ? WHERE review_run_id = ?",
    ).use { statement ->
      statement.setString(1, mode)
      statement.setString(2, reviewRunId)
      statement.executeUpdate()
    }
  }

  private fun claim(findingRef: String) =
    ParallelReviewMergedFinding(
      fNumber = findingRef,
      agentIds = listOf("codex"),
      severity = ParallelReviewSeverity.MAJOR,
      confidence = "High",
      location = "src/Main.kt:1",
      description = "finding",
      repositoryPath = "src/Main.kt",
      line = 1,
    )

  private fun assertLegacyReviewFinishedMigration(
    connection: Connection,
    review: ImportedReview,
    legacyRestampId: Long,
  ) {
    applyLegacyTelemetryLedgerMigration(connection)
    val restamped =
      TelemetryOutboxStore(connection, version = "test-runtime-version").listPending(null).single { record ->
        record.id == legacyRestampId
      }
    val restampedPayload = telemetryOutboxPayload(restamped.payloadJson)
    assertEquals(REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION, restampedPayload[SharedPayloadKeys.CONTRACT_VERSION])
    assertEquals(1, restampUpdateCount(connection))
    val rewritten =
      TelemetryOutboxStore(connection, version = "test-runtime-version").listPending(null).single {
        it.eventName == "skillbill_review_finished"
      }
    val payload = telemetryOutboxPayload(rewritten.payloadJson)
    assertEquals(REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION, payload["contract_version"])
    assertEquals(1, payload.nestedInt("verification", "claim_verdict", "confirmed"))
    assertEquals("delegated", payload["resolved_tier"])
    val companion =
      TelemetryOutboxStore(connection, version = "test-runtime-version").listPending(null).single { record ->
        record.eventName == TelemetryOutboxEvent.REVIEW_FINISHED_LEGACY_REGENERATED.wireValue
      }
    val companionPayload = telemetryOutboxPayload(companion.payloadJson)
    assertEquals(TelemetryOutboxEvent.REVIEW_FINISHED_LEGACY_REGENERATED.wireValue, companionPayload["event_name"])
    assertEquals(REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION, companionPayload["contract_version"])
    assertEquals(review.reviewRunId, companionPayload["review_run_id"])
    assertEquals(REVIEW_FINISHED_LEGACY_CONTRACT_VERSION, companionPayload["from_version"])
    assertEquals(REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION, companionPayload["to_version"])
    applyLegacyTelemetryLedgerMigration(connection, forcePending = false)
    assertEquals(
      1,
      TelemetryOutboxStore(connection, version = "test-runtime-version").listPending(null).count { record ->
        record.eventName == TelemetryOutboxEvent.REVIEW_FINISHED_LEGACY_REGENERATED.wireValue
      },
    )
    assertEquals(1, restampUpdateCount(connection))
  }
}

private fun Map<String, Any?>.nestedInt(vararg keys: String): Int {
  var current: Any? = this
  keys.forEach { key ->
    current = (current as? Map<*, *>)?.get(key)
  }
  return (current as Number).toInt()
}

private fun Map<String, Any?>.nestedDouble(vararg keys: String): Double {
  var current: Any? = this
  keys.forEach { key ->
    current = (current as? Map<*, *>)?.get(key)
  }
  return (current as Number).toDouble()
}

private fun java.sql.Connection.installLegacyRestampProbe(legacyRestampId: Long) {
  createStatement().use { statement ->
    statement.execute("CREATE TEMP TABLE legacy_restamp_probe (update_count INTEGER NOT NULL)")
    statement.execute("INSERT INTO legacy_restamp_probe VALUES (0)")
    statement.execute(
      """
      CREATE TEMP TRIGGER count_legacy_restamp
      AFTER UPDATE OF payload_json ON telemetry_outbox
      WHEN NEW.id = $legacyRestampId
      BEGIN
        UPDATE legacy_restamp_probe SET update_count = update_count + 1;
      END
      """.trimIndent(),
    )
  }
}

private fun telemetryOutboxPayload(payloadJson: String): Map<String, Any?> =
  JsonCodec.parseObjectOrNull(payloadJson)
    ?.let { node -> JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(node)) }
    ?: emptyMap()
