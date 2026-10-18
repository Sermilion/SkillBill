package skillbill.engine.goalrunner.status

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.workflow.decomposition.withWorkflowId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class GoalRunnerStatusReadOnlyMutationTest {
  @Test
  fun `goal status projection does not prune checkpoint refs on read`() {
    val git = RecordingWorkflowGitOperations()
    val manifestStore =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1).withWorkflowId(
            subtaskId = 1,
            workflowId = "wftr-child-1",
          ),
      )
    val repoRoot = Files.createTempDirectory("goal-status-readonly").also { it.toFile().deleteOnExit() }
    val service =
      testGoalRunnerStatusService(
        manifestStore = manifestStore,
        outcomeStore = RecordingOutcomeStore(),
        ports = GoalRunnerStatusTestPorts(gitOperations = git),
      )

    service.status(
      GoalRunnerStatusRequest(
        issueKey = "SKILL-56",
        repoRoot = repoRoot,
        pruneCheckpointRefs = true,
      ),
    )

    assertTrue(git.deleteCheckpointRefCalls.isEmpty())
    assertTrue(git.updateCheckpointRefCalls.isEmpty())
  }
}
