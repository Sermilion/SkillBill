package skillbill.engine.goalrunner.persist

import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerOutcomeStore
import skillbill.engine.goalrunner.model.GoalAttemptLedgerEntryDraft
import skillbill.engine.goalrunner.model.GoalProgressEventDraft
import skillbill.engine.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerObservabilityLivenessClass
import skillbill.engine.goalrunner.model.GoalRunnerObservabilityWorkerRole
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestRejectionReason
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowGoalRunnerCompletedChildBookkeepingTest {
  @Test
  fun `a completed child acknowledges its review summary pass and stays completed`() {
    val database = completedChildDatabase()
    val store = testWorkflowGoalRunnerOutcomeStore(database)

    assertEquals(listOf(1), store.unemittedGoalReviewPasses(CHILD_WORKFLOW_ID).map { it.passNumber })
    assertTrue(store.acknowledgeGoalReviewPass(CHILD_WORKFLOW_ID, 1))

    assertEquals(emptyList(), store.unemittedGoalReviewPasses(CHILD_WORKFLOW_ID))
    assertStillCompleted(database)
  }

  @Test
  fun `runner bookkeeping lands on a completed child without reopening it`() {
    val database = completedChildDatabase()
    val store = testWorkflowGoalRunnerOutcomeStore(database)

    assertTrue(
      store.recordAttemptLedgerEntry(
        GoalRunnerAttemptLedgerRecordRequest(
          workflowId = CHILD_WORKFLOW_ID,
          issueKey = ISSUE_KEY,
          draft =
            GoalAttemptLedgerEntryDraft(
              action = GoalAttemptLedgerAction.FINAL_RECONCILED_OUTCOME,
              timestamp = COMPLETED_AT,
              finalReconciledResult = "complete",
            ),
        ),
      ),
    )
    assertTrue(
      store.recordProgressEvent(
        GoalRunnerProgressEventRecordRequest(
          workflowId = CHILD_WORKFLOW_ID,
          issueKey = ISSUE_KEY,
          draft =
            GoalProgressEventDraft(
              eventKind = GoalProgressEventKind.PHASE_STARTED,
              workflowId = CHILD_WORKFLOW_ID,
              workflowPhase = "goal_runner_supervision",
              processAlive = false,
              timestamp = COMPLETED_AT,
            ),
        ),
      ),
    )
    assertTrue(
      store.recordObservabilityEvent(
        GoalRunnerObservabilityRecordRequest(
          workflowId = CHILD_WORKFLOW_ID,
          issueKey = ISSUE_KEY,
          subtaskId = SUBTASK_ID,
          workflowPhase = "commit_push",
          workerRole = GoalRunnerObservabilityWorkerRole.GOAL_RUNNER_SUPERVISOR.wireValue,
          livenessClass = GoalRunnerObservabilityLivenessClass.COMPLETION.wireValue,
          activitySummary = "Subtask $SUBTASK_ID completed.",
          timestamp = COMPLETED_AT.toString(),
        ),
      ),
    )
    assertTrue(
      store.recordWorkerSubtaskRequestOutcomes(
        workflowId = CHILD_WORKFLOW_ID,
        outcomes =
          listOf(
            GoalRunnerWorkerSubtaskRequestOutcome.Rejected(
              sourceStream = "stdout",
              reason = GoalRunnerWorkerSubtaskRequestRejectionReason.UNSAFE_PATH,
              message = "unsafe path",
            ),
          ),
      ),
    )

    val artifacts = assertStillCompleted(database).artifacts.toMap()
    assertEquals(1, (artifacts["goal_attempt_ledger"] as List<*>).size)
    assertEquals(1, (artifacts["goal_progress_run_history"] as List<*>).size)
    assertTrue(artifacts.containsKey("goal_observability_latest_event"))
    assertEquals(1, (artifacts["goal_worker_subtask_request_outcomes"] as List<*>).size)
  }

  private fun completedChildDatabase(): SQLiteDatabaseSessionFactory {
    val root = Files.createTempDirectory("goal-completed-child-bookkeeping")
    val database =
      sqliteDatabaseSessionFactory(
        userHome = root,
        dbPathOverride = root.resolve("runtime.db").toString(),
        environment = mapOf("SKILL_BILL_TEST" to "1"),
      )
    database.transaction { unit ->
      unit.workflowStates.saveFeatureTaskWorkflow(completedChildRecord(), FeatureTaskWorkflowMode.RUNTIME)
    }
    return database
  }

  private fun completedChildRecord(): WorkflowStateRecord {
    val reviewState =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "a".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.AUTO,
      ).reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        unresolvedFindingCount = 0,
        findings = emptyList(),
      )
    val reviewed =
      goalReviewWorkflowRecord(
        workflowId = CHILD_WORKFLOW_ID,
        state = reviewState,
        rawReviewResult =
          """
          [F-001] Major | path="runtime-kotlin/Example.kt" | line=10 | description=example finding in prose.
          """.trimIndent(),
      ).toSnapshot()
    return WorkflowEngine().updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      reviewed,
      WorkflowUpdateInput(
        workflowStatus = WorkflowStatus.COMPLETED,
        currentStepId = "commit_push",
        stepUpdates = null,
        artifactsPatch = null,
        sessionId = reviewed.sessionId,
        terminalInstant = COMPLETED_AT,
      ),
    ).toRecord()
  }

  private fun assertStillCompleted(database: SQLiteDatabaseSessionFactory): WorkflowStateSnapshot {
    val child =
      requireNotNull(database.read { unit -> unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, CHILD_WORKFLOW_ID) })
    assertEquals(WorkflowStatus.COMPLETED, child.workflowStatus)
    assertEquals("commit_push", child.currentStepId)
    assertEquals(COMPLETED_AT, child.finishedAt)
    return child
  }

  private companion object {
    const val CHILD_WORKFLOW_ID: String = "wftr-completed-child"
    const val ISSUE_KEY: String = "SKILL-119"
    const val SUBTASK_ID: Int = 2
    val COMPLETED_AT: Instant = Instant.parse("2026-10-19T06:48:30Z")
  }
}
