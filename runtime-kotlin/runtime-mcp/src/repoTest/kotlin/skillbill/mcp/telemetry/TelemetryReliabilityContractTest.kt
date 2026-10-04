package skillbill.mcp.telemetry

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.infrastructure.sqlite.ageTelemetryReliabilitySession
import skillbill.infrastructure.sqlite.telemetryReliabilityEmittedEnvelope
import skillbill.infrastructure.sqlite.telemetryReliabilityReviewFinishedEnvelope
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.review.attribution.normalizeTelemetrySlug
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelemetryReliabilityContractTest {
  private val schemaNode: JsonNode by lazy {
    val schemaFile = repoRootFromTest().resolve(TelemetryEventSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema file is missing at $schemaFile.")
    YAMLMapper().readTree(Files.readString(schemaFile))
  }

  @Test
  fun `representative completed issue terminal telemetry carries complete summary fields`() {
    val terminal = goalIssueFinishedEnvelope()
    assertEquals("completed", terminal["status"])
    assertPositiveBoundedDuration(terminal)
    listOf(
      "subtasks_complete",
      "subtasks_blocked",
      "subtasks_skipped",
      "total_invocations",
      "total_blocks",
      "total_resumes",
    )
      .forEach { key -> assertTrue((terminal[key] as? Int) != null, "$key must be present as an integer.") }
    listOf("first_started_at", "finished_at", "mode").forEach { key -> assertNonBlankString(terminal, key) }
    TelemetryEventSchemaValidator.validate(envelope = terminal, eventName = terminal["event_name"] as String)
  }

  @Test
  fun `representative finished telemetry carries nonblank routing labels and plausible durations`() {
    val schemaEnvelopes =
      listOf(
        goalFinishedEnvelope(),
        goalIssueFinishedEnvelope(),
        qualityCheckFinishedEnvelope(),
        featureVerifyFinishedEnvelope(),
      )
    val runtimeEnvelope = featureTaskRuntimeFinishedEnvelope()

    (schemaEnvelopes + runtimeEnvelope).forEach(::assertPositiveBoundedDuration)
    schemaEnvelopes.forEach { envelope ->
      TelemetryEventSchemaValidator.validate(envelope = envelope, eventName = envelope["event_name"] as String)
    }

    val routedEnvelopes = listOf(qualityCheckFinishedEnvelope(), reviewFinishedEnvelope())
    routedEnvelopes.forEach { envelope ->
      listOf("routed_skill", "detected_stack").forEach { key -> assertNonBlankString(envelope, key) }
      assertNormalizedSlug(envelope, "detected_stack")
      envelope["platform_slug"]?.let { assertNormalizedSlug(envelope, "platform_slug") }
      TelemetryEventSchemaValidator.validate(envelope = envelope, eventName = envelope["event_name"] as String)
    }

    val review = reviewFinishedEnvelope()
    assertEquals("kmp", review["detected_stack"], "the mixed KMP routing label must be normalized to its slug.")
    assertEquals("kmp", review["platform_slug"])
    assertEquals("KMP/Kotlin mixed workspace", review["detected_stack_detail"])
  }

  @Test
  fun `goal status and stop_reason values adhere to canonical enums`() {
    val subtaskStatuses = schemaEnum("goalSubtaskStatusEnum")
    val goalStatuses = schemaEnum("goalFinishedStatusEnum")
    val stopReasons = schemaEnum("goalRunnerStopReasonEnum")

    assertEquals(setOf("complete", "blocked", "skipped"), subtaskStatuses)
    assertEquals(setOf("completed", "paused", "blocked", "abandoned"), goalStatuses)
    assertEquals(GoalRunnerStopReason.entries.map { it.name }.toSet(), stopReasons)

    val blockedSubtask =
      goalSubtaskFinishedEnvelope(
        "status" to "blocked",
        "blocked_reason" to "validation: tests failed",
      )
    val blockedGoal = goalFinishedEnvelope("status" to "blocked", "stop_reason" to "BLOCKED")
    val pausedGoal = goalFinishedEnvelope("status" to "paused", "stop_reason" to "PAUSED")

    assertBlockedReason(blockedSubtask)
    listOf(blockedSubtask, blockedGoal, pausedGoal).forEach { envelope ->
      TelemetryEventSchemaValidator.validate(envelope = envelope, eventName = envelope["event_name"] as String)
    }
  }

  @Test
  fun `schema rejects invalid status stop_reason and blank blocked reasons`() {
    assertFailsWith<SkillBillRuntimeException> {
      TelemetryEventSchemaValidator.validate(
        envelope = goalSubtaskFinishedEnvelope("status" to "finished"),
        eventName = "goal_subtask_finished",
      )
    }.also { assertEquals(InstallFailureCode.INVALID_TELEMETRY_EVENT_SCHEMA, it.code) }

    assertFailsWith<SkillBillRuntimeException> {
      TelemetryEventSchemaValidator.validate(
        envelope = goalFinishedEnvelope("stop_reason" to "NOT_A_STOP_REASON"),
        eventName = "goal_finished",
      )
    }.also { assertEquals(InstallFailureCode.INVALID_TELEMETRY_EVENT_SCHEMA, it.code) }

    assertFailsWith<AssertionError> {
      assertBlockedReason(goalSubtaskFinishedEnvelope("status" to "blocked", "blocked_reason" to "   "))
    }
  }

  @Test
  fun `semantic reliability assertions reject mutations of real emitted payloads`() {
    assertFailsWith<AssertionError> { assertPositiveBoundedDuration(goalFinishedEnvelope("duration_seconds" to 0)) }
    assertFailsWith<AssertionError> {
      assertPositiveBoundedDuration(featureVerifyFinishedEnvelope("duration_seconds" to 0))
    }
    assertFailsWith<AssertionError> {
      assertPositiveBoundedDuration(featureTaskRuntimeFinishedEnvelope("duration_seconds" to null))
    }
    assertFailsWith<AssertionError> {
      assertNonBlankString(reviewFinishedEnvelope().apply { put("detected_stack", " ") }, "detected_stack")
    }
    assertFailsWith<AssertionError> {
      val unnormalized = reviewFinishedEnvelope().apply { put("detected_stack", "KMP/Kotlin mixed workspace") }
      assertNormalizedSlug(unnormalized, "detected_stack")
    }
    assertFailsWith<AssertionError> {
      val incomplete = goalIssueFinishedEnvelope().apply { remove("first_started_at") }
      assertNonBlankString(incomplete, "first_started_at")
    }
    assertFailsWith<AssertionError> {
      val inaccurate = goalIssueFinishedEnvelopeWithSegmentStarts(3).apply { put("total_invocations", 9) }
      assertAccurateInvocationAggregate(inaccurate, expectedSegmentStarts = 3)
    }
  }

  @Test
  fun `aggregate accuracy assertion rejects a total inconsistent with the emitted segment history`() {
    val segmentStarts = 3
    assertAccurateInvocationAggregate(goalIssueFinishedEnvelopeWithSegmentStarts(segmentStarts), segmentStarts)
    assertFailsWith<AssertionError> {
      assertAccurateInvocationAggregate(
        goalIssueFinishedEnvelopeWithSegmentStarts(segmentStarts).apply { put("total_invocations", segmentStarts + 4) },
        segmentStarts,
      )
    }
  }

  @Test
  fun `stale reconciled terminal payloads validate against the canonical schema`() {
    val schemaEnvelopes =
      listOf(
        featureVerifyFinishedEnvelope("completion_status" to "stale"),
        qualityCheckFinishedEnvelope().apply { put("result", "stale") },
      )
    val runtimeEnvelope = featureTaskRuntimeFinishedEnvelope("completion_status" to "stale")

    assertEquals("stale", runtimeEnvelope["completion_status"])
    schemaEnvelopes.forEach { envelope ->
      TelemetryEventSchemaValidator.validate(envelope = envelope, eventName = envelope["event_name"] as String)
    }
  }

  @Test
  fun `review and feature-task health fields remain explicitly covered`() {
    val reviewFinished = reviewFinishedEnvelope()
    assertEquals(0, reviewFinished["unresolved_findings"])
    TelemetryEventSchemaValidator.validate(envelope = reviewFinished, eventName = "skillbill_review_finished")
  }

  private fun schemaEnum(name: String): Set<String> =
    schemaNode.path("\$defs").path(name).path("enum").elements().asSequence().map { it.asText() }.toSet()

  private fun assertPositiveBoundedDuration(envelope: Map<String, Any?>) {
    val duration = envelope["duration_seconds"] as? Int
    assertNotNull(duration, "duration_seconds must be present as an integer.")
    assertTrue(duration > 0, "duration_seconds must be positive.")
    assertTrue(duration < 86_400, "duration_seconds must stay below the existing health threshold.")
  }

  private fun assertNonBlankString(
    envelope: Map<String, Any?>,
    key: String,
  ) {
    val value = envelope[key] as? String
    assertNotNull(value, "$key must be present as a string.")
    assertTrue(value.trim().isNotEmpty(), "$key must not be blank.")
  }

  private fun assertNormalizedSlug(
    envelope: Map<String, Any?>,
    key: String,
  ) {
    val value = envelope[key] as? String
    assertNotNull(value, "$key must be present as a string.")
    assertEquals(
      normalizeTelemetrySlug(value),
      value,
      "$key must be emitted as a normalized slug, not a verbatim routing label.",
    )
  }

  private fun assertAccurateInvocationAggregate(
    envelope: Map<String, Any?>,
    expectedSegmentStarts: Int,
  ) {
    val totalInvocations = envelope["total_invocations"] as? Int
    assertNotNull(totalInvocations, "total_invocations must be present as an integer.")
    assertEquals(
      expectedSegmentStarts,
      totalInvocations,
      "total_invocations must equal the number of goal segments recorded for the issue.",
    )
  }

  private fun assertBlockedReason(envelope: Map<String, Any?>) {
    if (envelope["status"] == "blocked") {
      assertNonBlankString(envelope, "blocked_reason")
    }
  }

  private fun goalSubtaskFinishedEnvelope(vararg overrides: Pair<String, Any?>): LinkedHashMap<String, Any?> =
    emittedEnvelope("skillbill_goal_subtask_finished") { store, _ ->
      store.goalSubtaskFinished(
        GoalSubtaskFinishedRecord(
          issueKey = "SKILL-109",
          workflowId = "wfl-skill-109",
          subtaskId = 6,
          subtaskName = "reliability contract test",
          status = "complete",
          startedAt = "2026-07-09T08:00:00Z",
          finishedAt = "2026-07-09T08:18:20Z",
          durationMs = 1_100_000,
          attemptCount = 1,
          blockedReason = null,
        ),
        "full",
      )
    }.apply {
      overrides.forEach { (key, value) -> put(key, value) }
    }

  private fun goalFinishedEnvelope(vararg overrides: Pair<String, Any?>): LinkedHashMap<String, Any?> =
    emittedEnvelope("skillbill_goal_finished") { store, _ ->
      store.goalFinished(goalFinishedRecord(), "full")
    }.apply {
      overrides.forEach { (key, value) -> put(key, value) }
    }

  private fun goalIssueFinishedEnvelope(): LinkedHashMap<String, Any?> =
    emittedEnvelope("skillbill_goal_issue_finished") { store, _ ->
      val started = goalStartedRecord(parentWorkflowId = "wfl-parent")
      store.goalStarted(started, "full")
      store.goalFinished(goalFinishedRecord().copy(parentWorkflowId = "wfl-parent"), "full")
      store.goalIssueFinished(
        GoalIssueFinishedRecord(
          issueKey = "SKILL-109",
          parentWorkflowId = "wfl-parent",
          status = "completed",
          subtasksComplete = 6,
          subtasksBlocked = 0,
          subtasksSkipped = 0,
          finishedAt = "2026-07-09T08:20:00Z",
          mode = "runtime",
        ),
        "full",
      )
    }

  private fun goalIssueFinishedEnvelopeWithSegmentStarts(segmentStarts: Int): LinkedHashMap<String, Any?> =
    emittedEnvelope("skillbill_goal_issue_finished") { store, _ ->
      repeat(segmentStarts) { index ->
        store.goalStarted(
          goalStartedRecord(parentWorkflowId = "wfl-parent").copy(workflowId = "wfl-parent:seg:$index"),
          "full",
        )
      }
      store.goalIssueFinished(
        GoalIssueFinishedRecord(
          issueKey = "SKILL-109",
          parentWorkflowId = "wfl-parent",
          status = "completed",
          subtasksComplete = 6,
          subtasksBlocked = 0,
          subtasksSkipped = 0,
          finishedAt = "2026-07-09T08:20:00Z",
          mode = "runtime",
        ),
        "full",
      )
    }

  private fun featureVerifyFinishedEnvelope(vararg overrides: Pair<String, Any?>): LinkedHashMap<String, Any?> =
    emittedEnvelope("skillbill_feature_verify_finished") { store, connection ->
      store.featureVerifyStarted(
        FeatureVerifyStartedRecord("fvs-reliability", 3, false, "SKILL-109 telemetry reliability"),
        "full",
      )
      ageTelemetryReliabilitySession(connection, "feature_verify_sessions", "fvs-reliability", 600)
      store.featureVerifyFinished(
        FeatureVerifyFinishedRecord(
          "fvs-reliability",
          false,
          1,
          "all_pass",
          "completed",
          "medium",
          "medium",
          emptyList(),
        ),
        "full",
      )
    }.apply {
      overrides.forEach { (key, value) -> put(key, value) }
    }

  private fun featureTaskRuntimeFinishedEnvelope(vararg overrides: Pair<String, Any?>): LinkedHashMap<String, Any?> =
    emittedEnvelope("skillbill_feature_task_runtime_finished") { store, connection ->
      store.featureTaskRuntimeStarted(
        FeatureTaskRuntimeStartedRecord("ftr-reliability", "MEDIUM", "SKILL-109", "reliability"),
        "full",
      )
      ageTelemetryReliabilitySession(connection, "feature_task_runtime_sessions", "ftr-reliability", 540)
      store.featureTaskRuntimeFinished(
        FeatureTaskRuntimeFinishedRecord(
          "ftr-reliability",
          "completed",
          listOf("implement", "review"),
          mapOf("implement" to "completed", "review" to "completed"),
          "",
          "",
          "feat/SKILL-109",
        ),
        "full",
      )
    }.apply {
      overrides.forEach { (key, value) -> put(key, value) }
    }

  private fun qualityCheckFinishedEnvelope(): LinkedHashMap<String, Any?> =
    emittedEnvelope("skillbill_quality_check_finished") { store, connection ->
      val started =
        QualityCheckStartedRecord(
          "qck-reliability",
          "bill-kotlin-code-check",
          "kotlin",
          false,
          null,
          "branch_diff",
          2,
        )
      store.qualityCheckStarted(started, "full")
      ageTelemetryReliabilitySession(connection, "quality_check_sessions", started.sessionId, 420)
      store.qualityCheckFinished(
        QualityCheckFinishedRecord(
          started.sessionId,
          started.routedSkill,
          started.detectedStack,
          started.fallback,
          started.fallbackReason,
          started.scopeType,
          started.initialFailureCount,
          0,
          1,
          "pass",
          emptyList(),
          "",
        ),
        "full",
      )
    }

  private fun reviewFinishedEnvelope(): LinkedHashMap<String, Any?> =
    telemetryReliabilityReviewFinishedEnvelope(
      """
      Review session ID: rvs-reliability
      Review run ID: rvw-reliability
      Routed to: bill-kmp-code-review
      Detected review scope: branch diff
      Detected stack: KMP/Kotlin mixed workspace
      Execution mode: delegated

      ### 2. Risk Register
      No findings.
      """.trimIndent(),
      TELEMETRY_EVENT_CONTRACT_VERSION,
      mapOf("bill-kmp-code-review" to "kmp"),
    )

  private fun emittedEnvelope(
    eventName: String,
    emit: (LifecycleTelemetryRepository, Connection) -> Unit,
  ): LinkedHashMap<String, Any?> =
    telemetryReliabilityEmittedEnvelope(eventName, TELEMETRY_EVENT_CONTRACT_VERSION, emit)

  private fun goalStartedRecord(parentWorkflowId: String? = null): GoalStartedRecord =
    GoalStartedRecord(
      issueKey = "SKILL-109",
      featureName = "reliability",
      workflowId = "wfl-skill-109",
      subtaskTotal = 6,
      resumed = false,
      startedAt = "2026-07-09T08:00:00Z",
      mode = "runtime",
      parentWorkflowId = parentWorkflowId,
    )

  private fun goalFinishedRecord(): GoalFinishedRecord =
    GoalFinishedRecord(
      issueKey = "SKILL-109",
      workflowId = "wfl-skill-109",
      status = "completed",
      startedAt = "2026-07-09T08:00:00Z",
      finishedAt = "2026-07-09T08:20:00Z",
      durationMs = 1_200_000,
      subtasksComplete = 6,
      subtasksBlocked = 0,
      subtasksSkipped = 0,
      mode = "runtime",
    )
}
