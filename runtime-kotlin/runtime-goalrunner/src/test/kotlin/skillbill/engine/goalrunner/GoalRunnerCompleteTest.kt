package skillbill.engine.goalrunner

import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.goalRunnerDeps
import skillbill.engine.goalrunner.execution.core.testGoalRunner
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.model.GoalRunnerCompleteRequest
import skillbill.engine.goalrunner.model.GoalRunnerCompleteResult
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val RESOLVED_SHA = "abc1234abc1234abc1234abc1234abc1234abcd"

class GoalRunnerCompleteTest {
  @Test
  fun `complete accepts unfinished subtasks and a later run reports the goal already complete`() {
    val store = InMemoryGoalManifestStore(manifest = partlyFinishedManifest())
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
        ports = GoalRunnerStatusTestPorts(gitOperations = AcceptGitOperations()),
      )

    val completed =
      assertIs<GoalRunnerCompleteResult.Completed>(service.complete(completeRequest()))

    assertEquals(listOf(2), completed.acceptedSubtaskIds)
    assertEquals(RESOLVED_SHA, completed.commitSha)
    assertEquals("complete", store.manifest.status)
    assertEquals(listOf("sha-1", RESOLVED_SHA), store.manifest.subtasks.map { it.commitSha })
    assertEquals(setOf(2), store.acceptances.keys)
    assertNotNull(store.controlState.goalCompletedAt)

    val outcomes =
      object : TestGoalQualityOutcomeStore(settled = true) {
        override fun hasRequiredGoalChildHistoryDraft(
          parentWorkflowId: String,
          subtaskId: Int,
          childWorkflowId: String,
        ): Boolean = childWorkflowId == "wfl-1"
      }
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = RecordingSubtaskLauncher { error("A completed goal must not launch subtasks.") },
          outcomeStore = outcomes,
          pullRequestPort = RecordingPullRequestPort(),
        ),
      )
    assertIs<GoalRunnerRunReport.AlreadyComplete>(
      runner.run(
        GoalRunnerRunRequest(
          issueKey = "SKILL-56",
          repoRoot = Files.createTempDirectory("goal-complete"),
          invokedAgentId = "claude",
        ),
      ),
    )
  }

  @Test
  fun `complete refuses while a runner holds a live execution lease`() {
    val original = partlyFinishedManifest()
    val store = InMemoryGoalManifestStore(manifest = original)
    store.executionLeaseForTest =
      GoalRunnerExecutionLease(
        generation = 1,
        ownerToken = "owner",
        hostIdentity = "host",
        bootIdentity = "boot",
        pid = 42,
        processBirthToken = "birth-42",
        heartbeatAt = Instant.now().toString(),
        expiresAt = Instant.now().plusSeconds(3600).toString(),
      )
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
        ports = GoalRunnerStatusTestPorts(gitOperations = AcceptGitOperations()),
      )

    val rejected = assertIs<GoalRunnerCompleteResult.Rejected>(service.complete(completeRequest()))

    assertContains(rejected.reason, "goal stop SKILL-56")
    assertEquals(original, store.manifest)
    assertTrue(store.acceptances.isEmpty())
  }

  private fun completeRequest() =
    GoalRunnerCompleteRequest(
      issueKey = "SKILL-56",
      commitSha = "abc1234",
      reason = "Finished outside the runtime.",
      repoRoot = Path.of("."),
    )

  private fun partlyFinishedManifest(): DecompositionManifest =
    manifest(subtaskCount = 2).let { original ->
      original.copy(
        status = "blocked",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 2, action = "blocked"),
        subtasks =
          original.subtasks.map { subtask ->
            if (subtask.id == 1) {
              subtask.copy(status = "complete", workflowId = "wfl-1", commitSha = "sha-1")
            } else {
              subtask.copy(status = "blocked", blockedReason = "session limit", lastResumableStep = "review")
            }
          },
      )
    }
}
