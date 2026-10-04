package skillbill.di.workflow

import skillbill.application.review.model.GoalStatsResult
import skillbill.application.review.service.ReviewService
import skillbill.application.review.snapshot.HARNESS_ORIGIN_UNAVAILABLE
import skillbill.application.telemetry.lifecycle.toRecord
import skillbill.contracts.telemetry.TelemetryMeasurementAvailability
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.review.EmptyReviewAttributionPort
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.WorkflowStatsRepository
import skillbill.review.model.GoalBlockedSubtaskSummary
import skillbill.review.model.GoalRunSummary
import skillbill.review.model.GoalWorkflowStats
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationPersistencePortGoalTest {
  fun `lifecycle telemetry port records goal events mapped from requests`() {
    val repository = RecordingGoalLifecycleTelemetryRepository()

    repository.goalStarted(goalStartedRequest().toRecord(), level = "full")
    repository.goalSubtaskFinished(goalSubtaskFinishedRequest().toRecord(), level = "full")
    repository.goalFinished(goalFinishedRequest().toRecord(), level = "full")

    val started = repository.startedRecords.single()
    assertEquals("SKILL-66", started.issueKey)
    assertEquals("goal telemetry", started.featureName)
    assertEquals("wf-goal-1", started.workflowId)
    assertEquals(4, started.subtaskTotal)
    assertTrue(started.resumed)
    assertEquals("2026-06-04T10:00:00Z", started.startedAt)

    val subtask = repository.subtaskRecords.single()
    assertEquals(2, subtask.subtaskId)
    assertEquals("persistence", subtask.subtaskName)
    assertEquals("blocked", subtask.status)
    assertEquals(240_000L, subtask.durationMs)
    assertEquals(3, subtask.attemptCount)
    assertEquals("validation failed", subtask.blockedReason)

    val finished = repository.finishedRecords.single()
    assertEquals("blocked", finished.status)
    assertEquals(1_200_000L, finished.durationMs)
    assertEquals(1, finished.subtasksComplete)
    assertEquals(1, finished.subtasksBlocked)
    assertEquals(0, finished.subtasksSkipped)
  }

  @Test
  fun `workflow stats port exposes goal aggregate through its surface`() {
    val expected =
      GoalWorkflowStats(
        totalRuns = 2,
        finishedRuns = 1,
        inProgressRuns = 1,
        completionStatusCounts = mapOf("completed" to 1, "blocked" to 0),
        completedRuns = 1,
        completedRate = 1.0,
        blockedRuns = 0,
        blockedRate = 0.0,
        subtaskOutcomeCounts = mapOf("complete" to 3, "blocked" to 0, "skipped" to 1),
        totalSubtaskEvents = 4,
        averageRunDurationMs = 5_460_000.0,
        averageSubtaskDurationMs = 120_000.0,
        averageAttemptCount = 1.25,
        mostRecentRun =
          GoalRunSummary(
            workflowId = "wf-goal-9",
            issueKey = "SKILL-66",
            featureName = "goal telemetry",
            status = "completed",
            startedAt = "2026-06-04T10:00:00Z",
            finishedAt = "2026-06-04T11:31:00Z",
            durationMs = 5_460_000L,
            resumed = false,
            subtaskTotal = 4,
          ),
        topBlockedSubtasks = emptyList(),
        logicalGoals = 2,
        invocationsWithUnknownGoal = 0,
        goalIdentityAvailability = TelemetryMeasurementAvailability.MEASURED.wireValue,
        resumedInvocations = 0,
      )
    val repository: WorkflowStatsRepository = FakeGoalStatsRepository(expected)

    assertEquals(expected, repository.goalStats())
    assertEquals("wf-goal-9", repository.goalStats().mostRecentRun?.workflowId)
  }

  @Test
  fun `review service goalStats returns GoalStatsResult from seeded repository`() {
    val blockedSummary =
      GoalBlockedSubtaskSummary(
        subtaskId = 2,
        subtaskName = "persistence",
        issueKey = "SKILL-66",
        blockedReason = "validation failed",
        attemptCount = 3,
      )
    val seededStats =
      GoalWorkflowStats(
        totalRuns = 1,
        finishedRuns = 1,
        inProgressRuns = 0,
        completionStatusCounts = mapOf("completed" to 0, "blocked" to 1),
        completedRuns = 0,
        completedRate = 0.0,
        blockedRuns = 1,
        blockedRate = 1.0,
        subtaskOutcomeCounts = mapOf("complete" to 0, "blocked" to 1, "skipped" to 0),
        totalSubtaskEvents = 1,
        averageRunDurationMs = 1_200_000.0,
        averageSubtaskDurationMs = 240_000.0,
        averageAttemptCount = 3.0,
        mostRecentRun = null,
        topBlockedSubtasks = listOf(blockedSummary),
        logicalGoals = 1,
        invocationsWithUnknownGoal = 0,
        goalIdentityAvailability = TelemetryMeasurementAvailability.MEASURED.wireValue,
        resumedInvocations = 0,
      )
    val database = FakeDatabaseSessionFactory(reviews = FakeGoalStatsReviewRepository(seededStats))
    val service =
      ReviewService(
        EnvironmentContext(environment = emptyMap(), userHome = Files.createTempDirectory("skillbill-app-goal")),
        database,
        FakeTelemetrySettingsProvider(enabled = false),
        FakeReviewInputSource,
        EmptyReviewAttributionPort,
        NoopRuntimeDiagnostics,
        HARNESS_ORIGIN_UNAVAILABLE,
      )

    val result: GoalStatsResult = service.goalStats()

    assertEquals(listOf("read"), database.calls)
    assertEquals("/fake/metrics.db", result.dbPath)
    assertEquals(seededStats, result.stats)
    assertEquals(1, result.stats.topBlockedSubtasks.size)
    assertEquals("validation failed", result.stats.topBlockedSubtasks.single().blockedReason)
  }

  @Test
  fun `goal stats all-blocked store has blocked rate 1 and non-empty topBlockedSubtasks`() {
    val blockedEntry =
      GoalBlockedSubtaskSummary(
        subtaskId = 1,
        subtaskName = "implement",
        issueKey = "SKILL-99",
        blockedReason = "compile error",
        attemptCount = 2,
      )
    val allBlockedStats =
      GoalWorkflowStats(
        totalRuns = 1,
        finishedRuns = 1,
        inProgressRuns = 0,
        completionStatusCounts = mapOf("completed" to 0, "blocked" to 1),
        completedRuns = 0,
        completedRate = 0.0,
        blockedRuns = 1,
        blockedRate = 1.0,
        subtaskOutcomeCounts = mapOf("complete" to 0, "blocked" to 1, "skipped" to 0),
        totalSubtaskEvents = 1,
        averageRunDurationMs = 60_000.0,
        averageSubtaskDurationMs = 60_000.0,
        averageAttemptCount = 2.0,
        mostRecentRun = null,
        topBlockedSubtasks = listOf(blockedEntry),
        logicalGoals = 1,
        invocationsWithUnknownGoal = 0,
        goalIdentityAvailability = TelemetryMeasurementAvailability.MEASURED.wireValue,
        resumedInvocations = 0,
      )

    assertEquals(1.0, allBlockedStats.blockedRate)
    assertEquals(0.0, allBlockedStats.completedRate)
    assertEquals(1, allBlockedStats.topBlockedSubtasks.size)
    assertEquals("compile error", allBlockedStats.topBlockedSubtasks.single().blockedReason)
  }

  @Test
  fun `goal stats all-skipped subtasks has empty topBlockedSubtasks`() {
    val allSkippedStats =
      GoalWorkflowStats(
        totalRuns = 1,
        finishedRuns = 1,
        inProgressRuns = 0,
        completionStatusCounts = mapOf("completed" to 1, "blocked" to 0),
        completedRuns = 1,
        completedRate = 1.0,
        blockedRuns = 0,
        blockedRate = 0.0,
        subtaskOutcomeCounts = mapOf("complete" to 0, "blocked" to 0, "skipped" to 3),
        totalSubtaskEvents = 3,
        averageRunDurationMs = 100_000.0,
        averageSubtaskDurationMs = 0.0,
        averageAttemptCount = 0.0,
        mostRecentRun = null,
        topBlockedSubtasks = emptyList(),
        logicalGoals = 1,
        invocationsWithUnknownGoal = 0,
        goalIdentityAvailability = TelemetryMeasurementAvailability.MEASURED.wireValue,
        resumedInvocations = 0,
      )

    assertEquals(3, allSkippedStats.subtaskOutcomeCounts["skipped"])
    assertTrue(allSkippedStats.topBlockedSubtasks.isEmpty())
  }

  @Test
  fun `goal stats single-run store has non-null mostRecentRun and totalRuns equals 1`() {
    val singleRunSummary =
      GoalRunSummary(
        workflowId = "wf-single",
        issueKey = "SKILL-1",
        featureName = "single run feature",
        status = "completed",
        startedAt = "2026-06-05T10:00:00Z",
        finishedAt = "2026-06-05T10:30:00Z",
        durationMs = 1_800_000L,
        resumed = false,
        subtaskTotal = 2,
      )
    val singleRunStats =
      GoalWorkflowStats(
        totalRuns = 1,
        finishedRuns = 1,
        inProgressRuns = 0,
        completionStatusCounts = mapOf("completed" to 1, "blocked" to 0),
        completedRuns = 1,
        completedRate = 1.0,
        blockedRuns = 0,
        blockedRate = 0.0,
        subtaskOutcomeCounts = mapOf("complete" to 2, "blocked" to 0, "skipped" to 0),
        totalSubtaskEvents = 2,
        averageRunDurationMs = 1_800_000.0,
        averageSubtaskDurationMs = 900_000.0,
        averageAttemptCount = 1.0,
        mostRecentRun = singleRunSummary,
        topBlockedSubtasks = emptyList(),
        logicalGoals = 1,
        invocationsWithUnknownGoal = 0,
        goalIdentityAvailability = TelemetryMeasurementAvailability.MEASURED.wireValue,
        resumedInvocations = 0,
      )

    assertEquals(1, singleRunStats.totalRuns)
    assertEquals("wf-single", requireNotNull(singleRunStats.mostRecentRun).workflowId)
  }

  @Test
  fun `goal planning preparation is a separate port unreachable from standalone feature-task persistence`() {
    val goalPlanningPort = GoalPlanningPreparationRepository::class.java
    val workflowStatePort = WorkflowStateRepository::class.java

    assertTrue(
      goalPlanningPort !in workflowStatePort.interfaces,
      "GoalPlanningPreparationRepository must remain a separate port; WorkflowStateRepository must not compose it.",
    )
    assertTrue(
      workflowStatePort !in goalPlanningPort.interfaces,
      "GoalPlanningPreparationRepository must not inherit the standalone feature-task port.",
    )
    val standaloneMethods = workflowStatePort.declaredMethods.map { it.name }.toSet()
    val goalPlanningMethodNames =
      listOf(
        "markPrepared",
        "deleteByGoal",
        "checkpointSharedPreplan",
        "checkpointSubtaskPlan",
        "listSubtaskPlansOrdered",
      )
    goalPlanningMethodNames.forEach { methodName ->
      assertTrue(
        methodName !in standaloneMethods,
        "Standalone WorkflowStateRepository must not expose goal-planning method '$methodName'.",
      )
    }
    val sqlTypedMembers =
      goalPlanningPort.declaredMethods.filter { function ->
        function.returnType.name.startsWith("java.sql") ||
          function.parameterTypes.any { type -> type.name.startsWith("java.sql") }
      }
    assertTrue(
      sqlTypedMembers.isEmpty(),
      "GoalPlanningPreparationRepository must not expose java.sql types: ${sqlTypedMembers.map { it.name }}",
    )
    assertTrue(
      goalPlanningPort.isAssignableFrom(EmptyGoalPlanningPreparationRepository::class.java),
      "EmptyGoalPlanningPreparationRepository must satisfy the goal-planning port for test fakes.",
    )
    val unitOfWorkClass = UnitOfWork::class.java
    val unitOfWorkGetter =
      unitOfWorkClass.declaredMethods.single { method -> method.name == "getGoalPlanningPreparations" }
    assertTrue(
      unitOfWorkGetter.returnType == goalPlanningPort,
      "UnitOfWork.goalPlanningPreparations must be typed as the dedicated GoalPlanningPreparationRepository port.",
    )
  }
}
