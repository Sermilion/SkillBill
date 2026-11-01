package skillbill.engine.goalrunner.status

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.contracts.JsonCodec
import skillbill.engine.goal.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.execution.core.testPhaseRecorder
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.persist.goalReviewWorkflowRecord
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.review.repository.ReviewRepository
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.model.ReviewFindingStatus
import skillbill.review.model.ReviewLaneResolutionState
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.model.ReviewRunLane
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GoalRunnerReviewPassProjectionTest {
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
          database = ReviewStoreDatabase(FakeDatabaseSessionFactory(InMemoryWorkflowStates()), lanesOnlyReviews(lanes)),
        ).status(GoalRunnerStatusRequest(issueKey = manifest.issueKey, invokedAgentId = "codex")),
      )

    val pass = result.completedSubtaskPhaseResults.single().reviewPasses.single()
    assertEquals(1, pass.passNumber)
    assertEquals("approved", pass.verdict)
    assertEquals(emptyList(), pass.findings)
    assertEquals(lanes, pass.lanes)
    assertFalse(result.degradedDurableRead)
  }

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
