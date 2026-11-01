package skillbill.engine.goalrunner.reset

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.planning.recovery.goalPlanningHardResetRemedy
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GoalRunnerHardResetOrphanTrapTest {
  private val issueKey = "SKILL-346"
  private val branch = "feat/skill-346"
  private val identity = FeatureTaskRuntimeSubtaskCommitIdentity(issueKey, "1")
  private val headSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  private val parentSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  private val repoRoot = Path.of("/tmp/skill-346-orphan-trap")

  @Test
  fun `detect matches active subtask trailer on feature branch tip`() {
    val git = orphanTrapGit()
    val trap = detectHardResetOrphanTrap(manifest(), repoRoot, git)
    requireNotNull(trap)
    assertEquals(branch, trap.featureBranch)
    assertEquals(headSha, trap.headSha)
    assertEquals(1, trap.activeSubtaskId)
  }

  @Test
  fun `coordinate moves unpushed orphan tip to parent before hard reset persists`() {
    val git = orphanTrapGit(unpushed = true)
    val trap = requireNotNull(detectHardResetOrphanTrap(manifest(), repoRoot, git))
    val coordinated = coordinateHardResetOrphanTrap(trap, issueKey, repoRoot, git)
    val documented = assertIs<GoalRunnerHardResetBranchCoordination.Documented>(coordinated)
    assertEquals("reset_feature_branch_tip_to_$parentSha", documented.branchActionTaken)
    assertEquals(listOf(parentSha), git.resetHardToCommitCalls)
  }

  @Test
  fun `coordinate documents published trailer retention for relaunch re adoption`() {
    val git = orphanTrapGit(unpushed = false)
    val trap = requireNotNull(detectHardResetOrphanTrap(manifest(), repoRoot, git))
    val coordinated = coordinateHardResetOrphanTrap(trap, issueKey, repoRoot, git)
    val documented = assertIs<GoalRunnerHardResetBranchCoordination.Documented>(coordinated)
    assertEquals(
      "retained_published_trailer_tip_$headSha; re_adopt_checkpoint_identity_on_relaunch",
      documented.branchActionTaken,
    )
    assertEquals(emptyList(), git.resetHardToCommitCalls)
  }

  @Test
  fun `detect ignores foreign subtask trailers`() {
    val git = orphanTrapGit(headMessage = "wip\n\nSkill-Bill-Subtask: $issueKey/2\n")
    assertNull(detectHardResetOrphanTrap(manifest(), repoRoot, git))
  }

  @Test
  fun `inspection refuses when checkout does not land on the feature branch`() {
    val git =
      orphanTrapGit().apply {
        currentBranchResult = WorkflowGitOperationResult.Ok(value = "other")
        landedBranchAfterCheckout = "other"
      }
    val inspection =
      assertIs<GoalRunnerHardResetOrphanTrapInspection.Unavailable>(
        inspectHardResetOrphanTrap(manifest(), repoRoot, git),
      )
    assertContains(inspection.reason, "instead of '$branch'")
  }

  @Test
  fun `review baseline block for subtask 2 includes one hard reset command`() {
    val manifest =
      skillbill.engine.goalrunner.manifest(2).copy(
        issueKey = issueKey,
        subtasks =
          listOf(
            skillbill.engine.goalrunner.manifest(2).subtasks[0].copy(status = "complete", commitSha = parentSha),
            skillbill.engine.goalrunner.manifest(2).subtasks[1].copy(status = "pending"),
          ),
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 2, action = "start"),
      )
    val reason = reviewBaselineBlockedReason(manifest, 2, "Could not capture review baseline.")
    assertEquals(
      "Could not capture review baseline. Recover with: '${goalPlanningHardResetRemedy(issueKey)}' " +
        "(prior subtask commit $parentSha is the review base).",
      reason,
    )
  }

  @Test
  fun `hard reset orphan remedy chains git reset with confirmed hard reset`() {
    assertEquals(
      "git -C $repoRoot reset --hard $parentSha && ${goalPlanningHardResetRemedy(issueKey)}",
      hardResetOrphanTrapRemedy(issueKey, repoRoot, parentSha),
    )
  }

  private fun manifest() =
    skillbill.engine.goalrunner.manifest(1).copy(
      issueKey = issueKey,
      status = "in_progress",
      featureBranch = branch,
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
      subtasks =
        listOf(
          skillbill.engine.goalrunner.manifest(1).subtasks.single().copy(
            status = "in_progress",
            workflowId = "wfl-child",
          ),
        ),
    )

  private fun orphanTrapGit(
    unpushed: Boolean = true,
    headMessage: String = "wip\n\n${identity.trailer}\n",
  ): RecordingWorkflowGitOperations =
    RecordingWorkflowGitOperations(
      currentBranchValue = branch,
      currentBranchResult = WorkflowGitOperationResult.Ok(value = branch),
    ).apply {
      headCommitShaValue = headSha
      headCommitMessageValue = headMessage
      localBranchHasUnpushedCommitsValue = unpushed
      onResolveCommit = { revision ->
        if (revision == "$headSha~1") {
          WorkflowGitOperationResult.Ok(value = parentSha)
        } else {
          null
        }
      }
    }
}
