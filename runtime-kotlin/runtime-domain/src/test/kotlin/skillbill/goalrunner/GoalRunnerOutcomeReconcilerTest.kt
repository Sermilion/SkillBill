package skillbill.goalrunner

import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangePause
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangeReason
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GoalRunnerOutcomeReconcilerTest {
  private val launchFacts = GoalRunnerLaunchFacts()

  @Test
  fun `a retained no-change pause reports resumable without requesting operator approval`() {
    val outcome =
      GoalRunnerStoredOutcome(
        status = GoalRunnerTerminalStatus.PAUSED,
        workflowId = "wf-old-no-change",
        lastResumableStep = "audit",
        suppressPr = true,
        noChangePause =
          FeatureTaskRuntimeNoChangePause(
            reason = FeatureTaskRuntimeNoChangeReason.ALREADY_SATISFIED,
            criteria = emptyList(),
            citations = listOf("src/Admission.kt:12"),
            boundaryTrace = "Request enters production admission.",
            owningSystem = null,
            suggestedHandoff = "Continue the goal.",
            auditSummary = "Production scope is satisfied.",
          ),
      )

    val stop =
      assertIs<GoalRunnerReconciledOutcome.Stop>(
        GoalRunnerOutcomeReconciler.reconcile(subtaskId = 3, launchFacts, outcome),
      )

    assertEquals(GoalRunnerStopReason.RECONCILED_RESUMABLE, stop.reason)
    assertEquals("audit", stop.lastResumableStep)
    assertContains(stop.blockedReason, "without approval")
  }

  @Test
  fun `a reconciled child row routes to resumable instead of the terminal no-terminal-store block`() {
    val outcome =
      GoalRunnerStoredOutcome(
        status = GoalRunnerTerminalStatus.RECONCILABLE,
        workflowId = "wf-reconciled",
        lastResumableStep = "implement",
        suppressPr = true,
      )

    val reconciled = GoalRunnerOutcomeReconciler.reconcile(subtaskId = 3, launchFacts, outcome)

    val stop = assertIs<GoalRunnerReconciledOutcome.Stop>(reconciled)
    assertEquals(GoalRunnerStopReason.RECONCILED_RESUMABLE, stop.reason)
    assertEquals("implement", stop.lastResumableStep)
    assertEquals("wf-reconciled", stop.workflowId)
  }

  @Test
  fun `a no-terminal-store outcome still blocks with the existing reason`() {
    val outcome =
      GoalRunnerStoredOutcome(
        status = GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME,
        workflowId = "wf-stranded",
        suppressPr = true,
      )

    val reconciled = GoalRunnerOutcomeReconciler.reconcile(subtaskId = 4, launchFacts, outcome)

    val stop = assertIs<GoalRunnerReconciledOutcome.Stop>(reconciled)
    assertEquals(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, stop.reason)
  }

  @Test
  fun `a reconcilable row that did not suppress per-subtask PRs blocks rather than going resumable`() {
    val outcome =
      GoalRunnerStoredOutcome(
        status = GoalRunnerTerminalStatus.RECONCILABLE,
        workflowId = "wf-reconciled",
        lastResumableStep = "plan",
        suppressPr = false,
      )

    val reconciled = GoalRunnerOutcomeReconciler.reconcile(subtaskId = 5, launchFacts, outcome)

    val stop = assertIs<GoalRunnerReconciledOutcome.Stop>(reconciled)
    assertEquals(GoalRunnerStopReason.BLOCKED, stop.reason)
  }
}
