package skillbill.engine.goalrunner.persist

import skillbill.application.testHarnessClock
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerProgressReader
import skillbill.engine.goalrunner.execution.core.SubtaskLaunchRequestArgs
import skillbill.engine.goalrunner.execution.core.testActivityStampWriter
import skillbill.engine.goalrunner.execution.core.testWorktreeEditJournalWriter
import skillbill.engine.goalrunner.launch.GoalRunnerLaunchReconciler
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.workflow.decomposition.withWorkflowId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalRunnerDirectRuntimeContinuationTest {
  @Test
  fun `reconciler threads direct runtime goal-continuation context onto the child launch request`() {
    val store =
      InMemoryGoalManifestStore(
        manifest = manifest(subtaskCount = 1).withWorkflowId(subtaskId = 1, workflowId = "wfl-child-runtime"),
      )
    val outcomeStore = RecordingOutcomeStore()
    val reconciler =
      GoalRunnerLaunchReconciler(
        manifestStore = store,
        outcomeStore = outcomeStore,
        progressReader = GoalRunnerProgressReader(outcomeStore),
        activityStampWriter = testActivityStampWriter(),
        worktreeEditJournalWriter = testWorktreeEditJournalWriter(),
        clock = testHarnessClock,
        diagnostics = NoopRuntimeDiagnostics,
      )

    val launchRequest =
      reconciler.subtaskLaunchRequest(
        SubtaskLaunchRequestArgs(
          issueKey = "SKILL-56",
          subtaskId = 1,
          request = wiringRunRequest(),
          assignedWorkflowId = null,
          reviewBaseline = null,
          spawnAuthorization = null,
        ),
      )
    val context = requireNotNull(launchRequest.skillRunRequest.goalContinuation)

    assertEquals("SKILL-56", context.parentIssueKey)
    assertEquals(1, context.subtaskId)
    assertEquals("feat/SKILL-56-goal", context.goalBranch)
    assertTrue(context.suppressPr)
    assertEquals(".feature-specs/SKILL-56-goal/spec_subtask_1.md", context.specPath)
    assertEquals("wfl-child-runtime", context.childWorkflowId)
    assertNull(launchRequest.skillRunRequest.promptOverride)
  }

  @Test
  fun `fresh assigned workflow id wins over stale manifest workflow id`() {
    val store =
      InMemoryGoalManifestStore(
        manifest = manifest(subtaskCount = 1).withWorkflowId(subtaskId = 1, workflowId = "wfl-stale-blocked"),
      )
    val outcomeStore = RecordingOutcomeStore()
    val reconciler =
      GoalRunnerLaunchReconciler(
        manifestStore = store,
        outcomeStore = outcomeStore,
        progressReader = GoalRunnerProgressReader(outcomeStore),
        activityStampWriter = testActivityStampWriter(),
        worktreeEditJournalWriter = testWorktreeEditJournalWriter(),
        clock = testHarnessClock,
        diagnostics = NoopRuntimeDiagnostics,
      )

    val launchRequest =
      reconciler.subtaskLaunchRequest(
        SubtaskLaunchRequestArgs(
          issueKey = "SKILL-56",
          subtaskId = 1,
          request = wiringRunRequest(),
          assignedWorkflowId = "wftr-fresh-assigned",
          reviewBaseline = null,
          spawnAuthorization = null,
        ),
      )
    val context = requireNotNull(launchRequest.skillRunRequest.goalContinuation)

    assertNull(context.childWorkflowId)
    assertEquals("wftr-fresh-assigned", context.assignedWorkflowId)
  }

  private fun wiringRunRequest(): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
    )
}
