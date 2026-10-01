package skillbill.engine.goalrunner.reset

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.lifecycle.subtask.FeatureTaskRuntimeSubtaskCommitAmend
import skillbill.engine.featuretask.lifecycle.subtask.FeatureTaskRuntimeSubtaskCommitCreate
import skillbill.engine.featuretask.lifecycle.subtask.FeatureTaskRuntimeSubtaskCommitHeadState
import skillbill.engine.featuretask.lifecycle.subtask.FeatureTaskRuntimeSubtaskCommitResolver
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.goalTestPhaseRecorder
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerResetRequest
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.planning.recovery.goalPlanningHardResetRemedy
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GoalHardResetCommitSpanRecoveryTest {
  private val issueKey = "SKILL-346"
  private val repoRoot = Path.of("/tmp/skill-346-recovery-sequence")
  private val branch = "feat/skill-346"
  private val headSha = "cccccccccccccccccccccccccccccccccccccccc"
  private val parentSha = "dddddddddddddddddddddddddddddddddddddddd"
  private val identity = FeatureTaskRuntimeSubtaskCommitIdentity(issueKey, "1")

  @Test
  fun `issue 346 sequence rejects unusable scoped recovery then hard reset clears ambiguous span`() {
    val git = sequenceGit()
    val service = interruptedSequenceService(git)
    val soft = service.reset(GoalRunnerResetRequest(issueKey = issueKey, hard = false))
    requireNotNull(soft)
    assertEquals(goalPlanningHardResetRemedy(issueKey), soft.recovery?.recoveryCommand)
    assertFailsWith<IllegalArgumentException> {
      service.reset(
        GoalRunnerResetRequest(
          issueKey = issueKey,
          hard = false,
          subtaskId = 1,
          deleteChildWorkflow = true,
        ),
      )
    }
    val hard =
      service.reset(
        GoalRunnerResetRequest(issueKey = issueKey, hard = true, repoRoot = repoRoot),
      )
    requireNotNull(hard)
    assertNull(hard.refusalReason)
    assertEquals("reset_feature_branch_tip_to_$parentSha", hard.branchActionTaken)
    assertEquals(parentSha, git.headCommitShaValue)
    assertIs<FeatureTaskRuntimeSubtaskCommitCreate>(
      FeatureTaskRuntimeSubtaskCommitResolver.decide(
        identity = identity,
        durableCommitSha = null,
        head =
          FeatureTaskRuntimeSubtaskCommitHeadState(
            sha = parentSha,
            commitMessage = null,
            isUnpushed = true,
          ),
        sequenceNumber = 0,
      ),
    )
  }

  @Test
  fun `published trailer survives hard reset with documented relaunch recovery`() {
    val git =
      sequenceGit().apply {
        localBranchHasUnpushedCommitsValue = false
      }
    val store =
      InMemoryGoalManifestStore(
        manifest(subtaskCount = 1).copy(
          issueKey = issueKey,
          featureBranch = branch,
          currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
          subtasks =
            listOf(
              manifest(subtaskCount = 1).subtasks.single().copy(
                status = "in_progress",
                workflowId = "wfl-interrupted",
              ),
            ),
        ),
      )
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
        ports = GoalRunnerStatusTestPorts(gitOperations = git),
      )

    val hard =
      requireNotNull(
        service.reset(GoalRunnerResetRequest(issueKey = issueKey, hard = true, repoRoot = repoRoot)),
      )

    assertEquals(
      "retained_published_trailer_tip_$headSha; re_adopt_checkpoint_identity_on_relaunch",
      hard.branchActionTaken,
    )
    val decision =
      assertIs<FeatureTaskRuntimeSubtaskCommitAmend>(
        FeatureTaskRuntimeSubtaskCommitResolver.decide(
          identity = identity,
          durableCommitSha = null,
          head =
            FeatureTaskRuntimeSubtaskCommitHeadState(
              sha = headSha,
              commitMessage = "wip\n\n${identity.trailer}\n",
              isUnpushed = false,
            ),
          sequenceNumber = 0,
        ),
      )
    assertEquals(true, decision.recoveredFromTrailer)
  }

  @Test
  fun `hard reset refuses before durable mutation when orphan tip cannot move`() {
    val git =
      sequenceGit().apply {
        resetHardToCommitResult = WorkflowGitOperationResult.Failed(error = "protected branch")
      }
    val store =
      InMemoryGoalManifestStore(
        manifest(subtaskCount = 1).copy(
          issueKey = issueKey,
          featureBranch = branch,
          currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
          subtasks =
            listOf(
              manifest(subtaskCount = 1).subtasks.single().copy(
                status = "in_progress",
                workflowId = "wfl-interrupted",
              ),
            ),
        ),
      )
    val savesBefore = store.saveCount
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
        ports = GoalRunnerStatusTestPorts(gitOperations = git),
      )
    val hard =
      service.reset(
        GoalRunnerResetRequest(issueKey = issueKey, hard = true, repoRoot = repoRoot),
      )
    requireNotNull(hard)
    assertNotNull(hard.refusalReason)
    assertNotNull(hard.remedyCommand)
    assertEquals(savesBefore, store.saveCount)
  }

  @Test
  fun `hard reset refuses before durable mutation when branch identity cannot be inspected`() {
    val git =
      sequenceGit().apply {
        currentBranchResult = WorkflowGitOperationResult.Failed(error = "git unavailable")
      }
    val store =
      InMemoryGoalManifestStore(
        manifest(subtaskCount = 1).copy(
          issueKey = issueKey,
          featureBranch = branch,
          currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
          subtasks =
            listOf(
              manifest(subtaskCount = 1).subtasks.single().copy(
                status = "in_progress",
                workflowId = "wfl-interrupted",
              ),
            ),
        ),
      )
    val savesBefore = store.saveCount
    val service =
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = goalTestPhaseRecorder(),
        ports = GoalRunnerStatusTestPorts(gitOperations = git),
      )

    val hard =
      requireNotNull(
        service.reset(GoalRunnerResetRequest(issueKey = issueKey, hard = true, repoRoot = repoRoot)),
      )

    assertContains(requireNotNull(hard.refusalReason), "could not be inspected")
    assertEquals(goalPlanningHardResetRemedy(issueKey), hard.remedyCommand)
    assertEquals(savesBefore, store.saveCount)
  }

  private fun sequenceGit(): RecordingWorkflowGitOperations =
    RecordingWorkflowGitOperations(
      currentBranchValue = branch,
      currentBranchResult = WorkflowGitOperationResult.Ok(value = branch),
    ).apply {
      headCommitShaValue = headSha
      headCommitMessageValue = "wip\n\n${identity.trailer}\n"
      localBranchHasUnpushedCommitsValue = true
      onResolveCommit = { revision ->
        if (revision == "$headSha~1") {
          WorkflowGitOperationResult.Ok(value = parentSha)
        } else {
          null
        }
      }
    }

  private fun interruptedSequenceService(git: RecordingWorkflowGitOperations): GoalRunnerStatusService {
    val outcomes =
      RecordingOutcomeStore().apply {
        progresses["wfl-interrupted"] =
          GoalRunnerWorkflowProgress(
            workflowId = "wfl-interrupted",
            workflowStatus = WorkflowStatus.FAILED,
            currentStepId = "implement",
            progressToken = "token",
          )
      }
    val store =
      InMemoryGoalManifestStore(
        manifest(subtaskCount = 1).copy(
          issueKey = issueKey,
          status = "in_progress",
          featureBranch = branch,
          currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
          subtasks =
            listOf(
              manifest(subtaskCount = 1).subtasks.single().copy(
                status = "in_progress",
                workflowId = "wfl-interrupted",
                branch = branch,
              ),
            ),
        ),
      )
    return testGoalRunnerStatusService(
      manifestStore = store,
      outcomeStore = outcomes,
      phaseRecorder = goalTestPhaseRecorder(),
      ports = GoalRunnerStatusTestPorts(gitOperations = git),
    )
  }
}
