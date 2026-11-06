package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.model.GoalWorktree
import dev.skillbill.desktop.core.domain.model.GoalWorktreeState
import dev.skillbill.desktop.core.domain.model.MainCheckoutOutcome
import skillbill.idestatus.model.IdeStatusExecutionScope
import skillbill.idestatus.model.IdeStatusFreshness
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.GoalMainCheckoutOutcome
import skillbill.ports.idestatus.model.GoalWorktreeView
import skillbill.ports.idestatus.model.IdeStatusExecutionIdentity
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import skillbill.ports.idestatus.model.WorkBoardItem
import java.nio.file.Files
import java.time.Instant
import skillbill.ports.idestatus.model.GoalWorktreeState as RuntimeGoalWorktreeState
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeWorkMapperTest {
  @Test
  fun `a standalone phase opens by its invocation and a workflow by its workflow id`() {
    val standalone = IdeStatusExecutionIdentity(
      IdeStatusExecutionScope.STANDALONE_PHASE, "exec-1", "store-1", "1", "1", invocationId = "inv-1", phaseId = "plan",
    )
    val workflow = IdeStatusExecutionIdentity(IdeStatusExecutionScope.WORKFLOW, "exec-2", "store-1", "1", "1")

    assertEquals("inv-1", board(workflowId = "wf-1", standalone).toWorkItem(1L).id)
    assertEquals("wf-2", board(workflowId = "wf-2", workflow).toWorkItem(1L).id)
  }

  @Test
  fun `a row is titled by the runtime title and keeps the summary as its summary`() {
    val item = board(workflowId = "wf-1", IdeStatusExecutionIdentity(IdeStatusExecutionScope.WORKFLOW, "e", "s", "1", "1"))
      .toWorkItem(1L)

    assertEquals("plan SKILL-452" to "Canceled by you.", item.title to item.summary)
  }

  @Test
  fun `a feature-named row is led by its issue key and a keyless one by its name alone`() {
    val execution = IdeStatusExecutionIdentity(IdeStatusExecutionScope.WORKFLOW, "e", "s", "1", "1")

    val keyed = board("wf-1", execution, title = "linear-progress-sync", issueKey = "SKILL-452").toWorkItem(1L)
    val keyless = board("wf-2", execution, title = "linear-progress-sync").toWorkItem(1L)

    assertEquals("[SKILL-452] Linear progress sync" to "Linear progress sync", keyed.title to keyless.title)
  }

  // The persisted read model can still say ACTIVE after the directory is gone; that must not send work to the dead path.
  @Test
  fun `an active worktree whose directory is gone maps to removed and keeps its other fields`() {
    val missing = Files.createTempDirectory("worktree").toRealPath().also { it.toFile().deleteRecursively() }
    val view = GoalWorktreeView(
      missing.toString(), RuntimeGoalWorktreeState.ACTIVE, "reason", "detail", GoalMainCheckoutOutcome.FAILED, "rebase in progress",
    )

    assertEquals(
      GoalWorktree(missing.toString(), GoalWorktreeState.Removed, "reason", "detail", MainCheckoutOutcome.Failed, "rebase in progress"),
      view.toDomainWorktree(),
    )
  }

  // Only a missing directory remaps; a worktree still on disk keeps the state the runtime reported.
  @Test
  fun `an active or retained worktree whose directory exists keeps its state`() {
    val present = Files.createTempDirectory("worktree").toRealPath()
    try {
      val active = GoalWorktreeView(present.toString(), RuntimeGoalWorktreeState.ACTIVE, null, null)
      val retained = GoalWorktreeView(present.toString(), RuntimeGoalWorktreeState.RETAINED, "unpublished", null)

      assertEquals(GoalWorktreeState.Active, active.toDomainWorktree().state)
      assertEquals(GoalWorktreeState.Retained, retained.toDomainWorktree().state)
    } finally {
      present.toFile().deleteRecursively()
    }
  }

  private fun board(
    workflowId: String,
    execution: IdeStatusExecutionIdentity,
    title: String = "plan SKILL-452",
    issueKey: String? = null,
  ) = WorkBoardItem(
    snapshot = IdeStatusSnapshot(
      repositoryIdentity = "repo",
      lifecycleState = IdeStatusLifecycleState.ACTIVE,
      currentStep = IdeStatusStep("implement", "Implement"),
      updatedAt = Instant.EPOCH,
      freshness = IdeStatusFreshness.FRESH,
      summary = "Canceled by you.",
      title = title,
      issueKey = issueKey,
      workflowId = workflowId,
      execution = execution,
    ),
    repositoryRoot = "/repo",
  )
}
