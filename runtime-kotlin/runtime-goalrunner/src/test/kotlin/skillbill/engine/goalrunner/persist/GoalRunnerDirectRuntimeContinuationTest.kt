package skillbill.engine.goalrunner.persist

import skillbill.application.testHarnessClock
import skillbill.application.testProjectStore
import skillbill.engine.featuretask.runloop.observability.AgentBuildObserverFactory
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerProgressReader
import skillbill.engine.goalrunner.execution.core.SubtaskLaunchRequestArgs
import skillbill.engine.goalrunner.execution.core.testActivityStampWriter
import skillbill.engine.goalrunner.execution.core.testWorktreeEditJournalWriter
import skillbill.engine.goalrunner.launch.GoalRunnerLaunchReconciler
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.ports.agentrun.model.AgentRunApprovalRequirement
import skillbill.ports.diagnostics.ChildFailureOutputStore
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
        projectStore = testProjectStore,
        childFailureOutputStore = ChildFailureOutputStore { _, _, _ -> "test.log" },
        buildObserverFactory = AgentBuildObserverFactory.NONE,
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
    assertEquals("/tmp/skillbill-goal-runner/.feature-specs/SKILL-56-goal/spec_subtask_1.md", context.specPath)
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
        projectStore = testProjectStore,
        childFailureOutputStore = ChildFailureOutputStore { _, _, _ -> "test.log" },
        buildObserverFactory = AgentBuildObserverFactory.NONE,
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

  @Test
  fun `store-relative subtask spec path reaches the child as its project store location`() {
    val base = manifest(subtaskCount = 1)
    val store =
      InMemoryGoalManifestStore(
        manifest =
          base.copy(
            parentSpecPath = "specs/SKILL-56-goal/spec.md",
            subtasks = base.subtasks.map { it.copy(specPath = "specs/SKILL-56-goal/spec_subtask_${it.id}.md") },
          ),
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
        projectStore = testProjectStore,
        childFailureOutputStore = ChildFailureOutputStore { _, _, _ -> "test.log" },
        buildObserverFactory = AgentBuildObserverFactory.NONE,
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

    assertEquals(
      testProjectStore.resolve(Path.of("/tmp/skillbill-goal-runner")).root
        .resolve("specs/SKILL-56-goal/spec_subtask_1.md").toString(),
      context.specPath,
    )
  }

  @Test
  fun `launch without a goal manifest is unowned and names the missing manifest`() {
    val reconciler = reconciler(InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1)))

    val launchRequest = reconciler.subtaskLaunchRequest(launchArgs(issueKey = "SKILL-57"))

    assertNull(launchRequest.skillRunRequest.goalContinuation)
    assertEquals(
      AgentRunApprovalRequirement.Unowned("goal SKILL-57 has no manifest"),
      launchRequest.skillRunRequest.approval,
    )
  }

  @Test
  fun `launch for a subtask without a spec path is unowned and names the missing spec path`() {
    val base = manifest(subtaskCount = 1)
    val reconciler =
      reconciler(
        InMemoryGoalManifestStore(manifest = base.copy(subtasks = base.subtasks.map { it.copy(specPath = "") })),
      )

    val launchRequest = reconciler.subtaskLaunchRequest(launchArgs(issueKey = "SKILL-56"))

    assertNull(launchRequest.skillRunRequest.goalContinuation)
    assertEquals(
      AgentRunApprovalRequirement.Unowned("goal SKILL-56 subtask 1 has no spec path"),
      launchRequest.skillRunRequest.approval,
    )
  }

  private fun reconciler(store: InMemoryGoalManifestStore): GoalRunnerLaunchReconciler {
    val outcomeStore = RecordingOutcomeStore()
    return GoalRunnerLaunchReconciler(
      manifestStore = store,
      outcomeStore = outcomeStore,
      progressReader = GoalRunnerProgressReader(outcomeStore),
      activityStampWriter = testActivityStampWriter(),
      worktreeEditJournalWriter = testWorktreeEditJournalWriter(),
      clock = testHarnessClock,
      diagnostics = NoopRuntimeDiagnostics,
      projectStore = testProjectStore,
      childFailureOutputStore = ChildFailureOutputStore { _, _, _ -> "test.log" },
      buildObserverFactory = AgentBuildObserverFactory.NONE,
    )
  }

  private fun launchArgs(issueKey: String): SubtaskLaunchRequestArgs =
    SubtaskLaunchRequestArgs(
      issueKey = issueKey,
      subtaskId = 1,
      request = wiringRunRequest().copy(issueKey = issueKey),
      assignedWorkflowId = null,
      reviewBaseline = null,
      spawnAuthorization = null,
    )

  private fun wiringRunRequest(): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
    )
}
