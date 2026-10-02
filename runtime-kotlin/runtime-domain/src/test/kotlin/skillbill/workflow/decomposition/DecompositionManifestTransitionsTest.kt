package skillbill.workflow.decomposition

import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionDependency
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.DecompositionSubtaskAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecompositionManifestTransitionsTest {
  @Test
  fun `parent status rolls up blocked over complete and complete only when every subtask is terminal`() {
    val complete = DecompositionStatus.COMPLETE.wireValue
    val skipped = DecompositionStatus.SKIPPED.wireValue
    val blocked = DecompositionStatus.BLOCKED.wireValue
    val pending = DecompositionStatus.PENDING.wireValue

    assertEquals(complete, manifest(subtask(1, complete), subtask(2, skipped)).withParentStatus().status)
    assertEquals(blocked, manifest(subtask(1, complete), subtask(2, blocked)).withParentStatus().status)
    assertEquals(
      DecompositionStatus.IN_PROGRESS.wireValue,
      manifest(subtask(1, complete), subtask(2, pending)).withParentStatus().status,
    )
    assertEquals(pending, manifest(subtask(1, pending), subtask(2, pending)).withParentStatus().status)
  }

  @Test
  fun `retrying a blocked subtask clears its reason and leaves siblings untouched`() {
    val blockedManifest =
      manifest(subtask(1, DecompositionStatus.COMPLETE.wireValue), subtask(2, DecompositionStatus.PENDING.wireValue))
        .withBlockedSubtask(subtaskId = 2, reason = " ", lastResumableStep = "review")

    val blockedSubtask = blockedManifest.subtasks.single { it.id == 2 }
    assertEquals(DecompositionStatus.BLOCKED.wireValue, blockedManifest.status)
    assertEquals(DecompositionStatus.BLOCKED.wireValue, blockedSubtask.status)
    assertEquals("Subtask 2 is blocked.", blockedSubtask.blockedReason)

    val retried = blockedManifest.withRetriedSubtask(subtaskId = 2, workflowId = "wfl-2", lastResumableStep = "review")

    val retriedSubtask = retried.subtasks.single { it.id == 2 }
    assertEquals(DecompositionStatus.IN_PROGRESS.wireValue, retried.status)
    assertEquals(CurrentSubtaskIntent(subtaskId = 2, action = "resume"), retried.currentSubtaskIntent)
    assertEquals(DecompositionStatus.IN_PROGRESS.wireValue, retriedSubtask.status)
    assertEquals("wfl-2", retriedSubtask.workflowId)
    assertNull(retriedSubtask.blockedReason)
    assertEquals(blockedManifest.subtasks.single { it.id == 1 }, retried.subtasks.single { it.id == 1 })
  }

  @Test
  fun `retrying an unknown subtask fails loudly`() {
    val error =
      assertFailsWith<IllegalArgumentException> {
        manifest(subtask(1, DecompositionStatus.BLOCKED.wireValue))
          .withRetriedSubtask(subtaskId = 9, workflowId = "wfl-9", lastResumableStep = "implement")
      }
    assertEquals("Cannot retry unknown decomposition subtask '9'.", error.message)
  }

  @Test
  fun `completing a subtask derives the parent status from the remaining siblings`() {
    val outcome =
      GoalRunnerReconciledOutcome.Complete(workflowId = "wfl-1", commitSha = "sha-1", lastResumableStep = "pr")
    val pending = DecompositionStatus.PENDING.wireValue

    val allTerminal =
      manifest(subtask(1, pending), subtask(2, DecompositionStatus.SKIPPED.wireValue))
        .withCompletedSubtask(subtaskId = 1, outcome = outcome)
    val workRemains =
      manifest(subtask(1, pending), subtask(2, pending))
        .withCompletedSubtask(subtaskId = 1, outcome = outcome)

    assertEquals(DecompositionStatus.COMPLETE.wireValue, allTerminal.status)
    assertEquals(
      CurrentSubtaskIntent(0, DecompositionSubtaskAction.COMPLETE.wireValue),
      allTerminal.currentSubtaskIntent,
    )
    assertEquals(DecompositionStatus.IN_PROGRESS.wireValue, workRemains.status)
  }

  @Test
  fun `hard reset returns every subtask to a clean pending state`() {
    val reset =
      manifest(
        subtask(1, DecompositionStatus.COMPLETE.wireValue).dirty(),
        subtask(2, DecompositionStatus.BLOCKED.wireValue).dirty(),
      ).resetManifest(hard = true)

    reset.subtasks.forEach { subtask ->
      assertEquals(DecompositionStatus.PENDING.wireValue, subtask.status)
      assertNull(subtask.branch)
      assertNull(subtask.commitSha)
      assertNull(subtask.workflowId)
      assertNull(subtask.blockedReason)
      assertNull(subtask.lastResumableStep)
    }
    assertEquals(CurrentSubtaskIntent(1, DecompositionSubtaskAction.START.wireValue), reset.currentSubtaskIntent)
    assertEquals(DecompositionStatus.PENDING.wireValue, reset.status)
  }

  @Test
  fun `soft reset keeps completed work and live children and resets the rest`() {
    val reset =
      manifest(
        subtask(1, DecompositionStatus.COMPLETE.wireValue).dirty(),
        subtask(2, DecompositionStatus.BLOCKED.wireValue).dirty(),
        subtask(3, DecompositionStatus.BLOCKED.wireValue).dirty().copy(workflowId = null),
      ).resetManifest(hard = false)

    val completed = reset.subtasks.single { it.id == 1 }
    assertEquals(DecompositionStatus.COMPLETE.wireValue, completed.status)
    assertEquals("sha", completed.commitSha)
    assertNull(completed.blockedReason)
    assertNull(completed.lastResumableStep)

    val launched = reset.subtasks.single { it.id == 2 }
    assertEquals(DecompositionStatus.IN_PROGRESS.wireValue, launched.status)
    assertEquals("wfl", launched.workflowId)
    assertNull(launched.blockedReason)
    assertEquals(CurrentSubtaskIntent(2, DecompositionSubtaskAction.RESUME.wireValue), reset.currentSubtaskIntent)

    val unlaunched = reset.subtasks.single { it.id == 3 }
    assertEquals(DecompositionStatus.PENDING.wireValue, unlaunched.status)
    assertNull(unlaunched.commitSha)
  }

  @Test
  fun `restart intent starts the first pending subtask whose dependencies are done`() {
    val pending = DecompositionStatus.PENDING.wireValue
    val intent =
      restartIntent(
        listOf(
          subtask(1, pending).copy(dependencies = listOf(DecompositionDependency(subtaskId = 2))),
          subtask(2, pending),
        ),
      )

    assertEquals(CurrentSubtaskIntent(2, DecompositionSubtaskAction.START.wireValue), intent)
  }

  @Test
  fun `unlaunched boundary is false once a child is running even if the intent says start`() {
    val pending = subtask(1, DecompositionStatus.PENDING.wireValue)
    val unselected = manifest(pending).copy(currentSubtaskIntent = CurrentSubtaskIntent(0, "none"))
    val selected = manifest(pending)
    val running = manifest(pending.copy(status = DecompositionStatus.IN_PROGRESS.wireValue, workflowId = "wfl"))

    assertTrue(unselected.isAtUnlaunchedBoundary())
    assertTrue(selected.isAtUnlaunchedBoundary())
    assertFalse(running.isAtUnlaunchedBoundary())
  }

  private fun DecompositionSubtask.dirty(): DecompositionSubtask =
    copy(branch = "b", commitSha = "sha", workflowId = "wfl", blockedReason = "why", lastResumableStep = "step")

  private fun manifest(vararg subtasks: DecompositionSubtask): DecompositionManifest =
    DecompositionManifest(
      issueKey = "SKILL-370",
      featureName = "transitions",
      parentSpecPath = ".feature-specs/SKILL-370-transitions/spec.md",
      baseBranch = "main",
      featureBranch = "feat/SKILL-370-transitions",
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
      subtasks = subtasks.toList(),
    )

  private fun subtask(
    id: Int,
    status: String,
  ): DecompositionSubtask =
    DecompositionSubtask(
      id = id,
      name = "subtask-$id",
      specPath = ".feature-specs/SKILL-370-transitions/spec_subtask_$id.md",
      status = status,
    )
}
