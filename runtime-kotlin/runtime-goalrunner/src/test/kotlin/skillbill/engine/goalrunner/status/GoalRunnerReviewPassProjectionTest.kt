package skillbill.engine.goalrunner.status

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.goal.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestDatabase
import skillbill.engine.goalrunner.execution.core.goalRunnerDefaultPhaseRecorder
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.execution.core.testPhaseRecorder
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.persist.goalReviewWorkflowRecord
import skillbill.goalrunner.model.GoalRunnerSubtaskPhaseResults
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.review.repository.ReviewRepository
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFindingStatus
import skillbill.review.model.ReviewLaneResolutionState
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.model.ReviewRunLane
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class GoalRunnerReviewPassProjectionTest {
  @Test
  fun `a running review projects ordered lane dispositions without adding a completed pass`() {
    val state = completedState()
    val lanes =
      listOf(
        reviewLane("lane-b", order = 2, disposition = ReviewLaneReviewDisposition.INCOMPLETE),
        reviewLane("lane-a", order = 1, disposition = ReviewLaneReviewDisposition.COMPLETE),
      )

    val results = runningReviewProjection(state, passNumber = 2, runId = "rvw-running", lanes = lanes)

    assertEquals(1, results.reviewPasses.single().passNumber)
    assertEquals(2, results.runningReviewPass?.passNumber)
    assertEquals(
      listOf("lane-a" to true, "lane-b" to false),
      results.runningReviewPass?.lanes?.map { it.name to it.finished },
    )
  }

  @Test
  fun `a stale running review record does not reproject a completed pass`() {
    val results = runningReviewProjection(completedState(), passNumber = 1, runId = "rvw-running", lanes = emptyList())

    assertNull(results.runningReviewPass)
  }

  @Test
  fun `a failed running lane read is degraded and a missing run id stays empty`() {
    val initial =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "a".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.AUTO,
      )
    val failed =
      runningReviewProjection(
        initial,
        passNumber = 1,
        runId = "rvw-running",
        reviews = runningLanesReviews(emptyList(), failLanes = true),
      )
    val missingRun = runningReviewProjection(initial, passNumber = 1, runId = null, lanes = emptyList())

    assertEquals("subtask_review_lanes", failed.degradedReadSource)
    assertEquals(1, failed.runningReviewPass?.passNumber)
    assertEquals(emptyList(), failed.runningReviewPass?.lanes)
    assertNull(missingRun.degradedReadSource)
    assertEquals(emptyList(), missingRun.runningReviewPass?.lanes)
  }

  @Test
  fun `a completed subtask projects its stored review pass with the lanes its review run recorded`() {
    val workflowId = "wftr-reviewed-subtask"
    val states = InMemoryWorkflowStates()
    val state =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "a".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.AUTO,
      ).reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        unresolvedFindingCount = 0,
        findings = emptyList(),
      )
    val rawReviewResult =
      JsonCodec.mapToJsonString(
        mapOf("status" to "completed", "produced_outputs" to mapOf("review_run_id" to "rvw-subtask-1")),
      )
    states.saveFeatureTaskWorkflow(goalReviewWorkflowRecord(workflowId, state, rawReviewResult), RUNTIME)
    val lanes = listOf("bill-kotlin-code-review-architecture", "bill-kotlin-code-review-persistence")
    val manifest =
      manifest(1).copy(
        status = "complete",
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 1,
              name = "Subtask 1",
              specPath = ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
              dependencies = emptyList(),
              status = "complete",
              workflowId = workflowId,
              commitSha = "sha-1",
            ),
          ),
      )

    val result =
      requireNotNull(
        testGoalRunnerStatusService(
          manifestStore = InMemoryGoalManifestStore(manifest),
          outcomeStore = RecordingOutcomeStore(),
          phaseRecorder = testPhaseRecorder(FakeDatabaseSessionFactory(states), testWorkflowSnapshotValidator),
          database = ReviewStoreDatabase(GoalRunnerStatusTestDatabase(), lanesOnlyReviews(lanes)),
        ).status(GoalRunnerStatusRequest(issueKey = manifest.issueKey, invokedAgentId = "codex")),
      )

    val pass = result.completedSubtaskPhaseResults.single().reviewPasses.single()
    assertEquals(1, pass.passNumber)
    assertEquals("approved", pass.verdict)
    assertEquals(emptyList(), pass.findings)
    assertEquals(lanes, pass.lanes)
    assertFalse(result.degradedDurableRead)
  }

  @Test
  fun `a throwing findings read names its source and keeps compact findings`() {
    val reviews = failingFindingsReviews()
    val results = projectedReview(reviewedState(compactFinding), reviews)

    assertEquals("subtask_review_findings", results.degradedReadSource)
    assertEquals("F-001", results.reviewPasses.single().findings.single().ref)
  }

  @Test
  fun `compact findings with no stored findings name the findings source`() {
    val results = projectedReview(reviewedState(compactFinding), lanesOnlyReviews(emptyList()))

    assertEquals("subtask_review_findings", results.degradedReadSource)
    assertEquals("F-001", results.reviewPasses.single().findings.single().ref)
  }

  @Test
  fun `an undecodable review artifact names the raw results source because the first failure wins`() {
    val noCompletedPass =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "a".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.AUTO,
      )

    val results = projectedReview(noCompletedPass, lanesOnlyReviews(emptyList()))

    assertEquals("subtask_review_raw_results", results.degradedReadSource)
  }

  @Test
  fun `a malformed audit artifact names the phase results source`() {
    val workflowId = "wfl-malformed-audit"
    val recorder = goalRunnerDefaultPhaseRecorder()
    recorder.openTestWorkflow(workflowId, "goal-malformed-audit")
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = workflowId,
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        status = "completed",
        attemptCount = 1,
        resolvedAgentId = "claude",
        finished = true,
        outputArtifact = "{not json",
      ),
    )
    val manifest = singleSubtaskManifest(workflowId)

    val result =
      requireNotNull(
        testGoalRunnerStatusService(
          manifestStore = InMemoryGoalManifestStore(manifest),
          outcomeStore = RecordingOutcomeStore(),
          phaseRecorder = recorder,
        ).status(GoalRunnerStatusRequest(issueKey = manifest.issueKey)),
      )

    assertEquals("subtask_phase_results", result.completedSubtaskPhaseResults.single().degradedReadSource)
  }

  @Test
  fun `a complete subtask whose workflow is unknown names the phase results source`() {
    val manifest = singleSubtaskManifest("wfl-unknown")

    val result =
      requireNotNull(
        testGoalRunnerStatusService(
          manifestStore = InMemoryGoalManifestStore(manifest),
          outcomeStore = RecordingOutcomeStore(),
        ).status(GoalRunnerStatusRequest(issueKey = manifest.issueKey)),
      )

    assertEquals("subtask_phase_results", result.completedSubtaskPhaseResults.single().degradedReadSource)
  }

  @Test
  fun `a blank workflow id stays empty and not degraded`() {
    val manifest = singleSubtaskManifest("", status = "in_progress")

    val result =
      requireNotNull(
        testGoalRunnerStatusService(
          manifestStore = InMemoryGoalManifestStore(manifest),
          outcomeStore = RecordingOutcomeStore(),
        ).status(GoalRunnerStatusRequest(issueKey = manifest.issueKey)),
      )

    assertNull(result.completedSubtaskPhaseResults.single().degradedReadSource)
  }

  private val compactFinding = GoalSubtaskReviewCompactFinding("minor", "Minor", "A compact finding", "F-001")

  private fun reviewedState(finding: GoalSubtaskReviewCompactFinding): GoalSubtaskReviewState =
    GoalSubtaskReviewState.initial(
      reviewBaseSha = "a".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.AUTO,
    ).reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      unresolvedFindingCount = 0,
      findings = listOf(finding),
    )

  private fun completedState(): GoalSubtaskReviewState =
    GoalSubtaskReviewState.initial(
      reviewBaseSha = "a".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.AUTO,
    ).reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      unresolvedFindingCount = 0,
      findings = emptyList(),
    )

  private fun projectedReview(
    state: GoalSubtaskReviewState,
    reviews: ReviewRepository,
  ): GoalRunnerSubtaskPhaseResults {
    val workflowId = "wftr-degraded-subtask"
    val states = InMemoryWorkflowStates()
    val rawReviewResult =
      JsonCodec.mapToJsonString(
        mapOf("status" to "completed", "produced_outputs" to mapOf("review_run_id" to "rvw-subtask-1")),
      )
    states.saveFeatureTaskWorkflow(goalReviewWorkflowRecord(workflowId, state, rawReviewResult), RUNTIME)
    val manifest = singleSubtaskManifest(workflowId)
    return requireNotNull(
      testGoalRunnerStatusService(
        manifestStore = InMemoryGoalManifestStore(manifest),
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = testPhaseRecorder(FakeDatabaseSessionFactory(states), testWorkflowSnapshotValidator),
        database = ReviewStoreDatabase(GoalRunnerStatusTestDatabase(), reviews),
      ).status(GoalRunnerStatusRequest(issueKey = manifest.issueKey, invokedAgentId = "codex")),
    ).completedSubtaskPhaseResults.single()
  }

  private fun runningReviewProjection(
    state: GoalSubtaskReviewState,
    passNumber: Int,
    runId: String?,
    lanes: List<ReviewRunLane> = emptyList(),
    reviews: ReviewRepository = runningLanesReviews(lanes),
  ): GoalRunnerSubtaskPhaseResults {
    val workflowId = "wftr-running-subtask"
    val states = InMemoryWorkflowStates()
    states.saveFeatureTaskWorkflow(
      goalReviewWorkflowRecord(
        workflowId,
        state,
        JsonCodec.mapToJsonString(mapOf("status" to "completed")),
        includeRawResult = state.completedPassCount > 0,
      ),
      RUNTIME,
    )
    val db = FakeDatabaseSessionFactory(states)
    val recorder = testPhaseRecorder(db, testWorkflowSnapshotValidator)
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = workflowId,
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        status = "running",
        attemptCount = 1,
        resolvedAgentId = "claude",
        finished = false,
        reviewPassNumber = passNumber,
        reviewRunId = runId,
      ),
    )
    val manifest = singleSubtaskManifest(workflowId, status = "in_progress")
    return requireNotNull(
      testGoalRunnerStatusService(
        manifestStore = InMemoryGoalManifestStore(manifest),
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = recorder,
        database = ReviewStoreDatabase(GoalRunnerStatusTestDatabase(), reviews),
      ).status(GoalRunnerStatusRequest(issueKey = manifest.issueKey, invokedAgentId = "codex")),
    ).completedSubtaskPhaseResults.single()
  }

  private fun singleSubtaskManifest(
    workflowId: String,
    status: String = "complete",
  ) = manifest(1).copy(
    status = status,
    subtasks =
      listOf(
        DecompositionSubtask(
          id = 1,
          name = "Subtask 1",
          specPath = ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
          dependencies = emptyList(),
          status = status,
          workflowId = workflowId,
          commitSha = "sha-1",
        ),
      ),
  )

  private fun failingFindingsReviews(): ReviewRepository =
    Proxy.newProxyInstance(
      ReviewRepository::class.java.classLoader,
      arrayOf(ReviewRepository::class.java),
    ) { _, method, _ -> error("${method.name} is unavailable.") } as ReviewRepository

  private fun lanesOnlyReviews(lanes: List<String>): ReviewRepository =
    Proxy.newProxyInstance(
      ReviewRepository::class.java.classLoader,
      arrayOf(ReviewRepository::class.java),
    ) { _, method, args ->
      val runId = args?.firstOrNull()
      when (method.name) {
        "fetchFindingStatuses" -> emptyMap<String, ReviewFindingStatus>()
        "fetchReviewRunLanes" ->
          if (runId == "rvw-subtask-1") {
            lanes.mapIndexed { order, name ->
              ReviewRunLane(
                name, "kotlin", name.substringAfterLast('-'), 0, false, order, listOf("kotlin"),
                ReviewLaneResolutionState.RESOLVED, ReviewLaneReviewDisposition.COMPLETE,
              )
            }
          } else {
            emptyList()
          }
        "fetchNumberedFindings" -> throw IllegalArgumentException("Unknown review run id '$runId'.")
        else -> error("${method.name} is not exercised by GoalRunnerReviewPassProjectionTest.")
      }
    } as ReviewRepository

  private fun runningLanesReviews(
    lanes: List<ReviewRunLane>,
    failLanes: Boolean = false,
  ): ReviewRepository =
    Proxy.newProxyInstance(
      ReviewRepository::class.java.classLoader,
      arrayOf(ReviewRepository::class.java),
    ) { _, method, args ->
      when (method.name) {
        "fetchFindingStatuses" -> emptyMap<String, ReviewFindingStatus>()
        "fetchReviewRunLanes" -> {
          check(!failLanes) { "Lane read unavailable." }
          if (args?.firstOrNull() == "rvw-running") lanes else emptyList<ReviewRunLane>()
        }
        "fetchNumberedFindings" -> emptyList<NumberedFinding>()
        else -> error("${method.name} is not exercised by GoalRunnerReviewPassProjectionTest.")
      }
    } as ReviewRepository

  private fun reviewLane(
    name: String,
    order: Int,
    disposition: ReviewLaneReviewDisposition,
  ) = ReviewRunLane(
    name, "kotlin", name, 0, false, order, listOf("kotlin"),
    ReviewLaneResolutionState.RESOLVED, disposition,
  )

  private class ReviewStoreDatabase(
    private val inner: DatabaseSessionFactory,
    private val reviews: ReviewRepository,
  ) : DatabaseSessionFactory by inner {
    override fun <T> read(block: (UnitOfWork) -> T): T =
      inner.read { unit ->
        block(
          object : UnitOfWork by unit {
            override val reviews = this@ReviewStoreDatabase.reviews
          },
        )
      }
  }
}
