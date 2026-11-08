package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.model.GoalWorktree
import dev.skillbill.desktop.core.domain.model.GoalWorktreeState
import dev.skillbill.desktop.core.domain.model.MainCheckoutOutcome
import dev.skillbill.desktop.core.domain.model.WorkBuildState as DesktopBuildState
import skillbill.idestatus.model.IdeStatusExecutionScope
import skillbill.idestatus.model.IdeStatusFreshness
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.GoalMainCheckoutOutcome
import skillbill.ports.idestatus.model.GoalWorktreeView
import skillbill.ports.idestatus.model.IdeStatusExecutionIdentity
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import skillbill.ports.idestatus.model.WorkBoardItem
import skillbill.ports.idestatus.model.WorkBuildView
import skillbill.ports.idestatus.model.WorkBuildPrevious
import skillbill.ports.idestatus.model.WorkBuildState
import skillbill.ports.idestatus.model.WorkDetailItem
import java.nio.file.Files
import java.time.Instant
import skillbill.ports.idestatus.model.GoalWorktreeState as RuntimeGoalWorktreeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.toKotlinInstant

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

  @Test
  fun `board and detail builds map into the desktop model`() {
    val startedAt = Instant.parse("2026-10-01T09:00:00Z")
    val build = WorkBuildView(
      WorkBuildState.RUNNING, "./gradlew check", "gradlew check", "gradle", "kotlin", 42L,
      startedAt, 1_200L, startedAt.plusSeconds(1), 3_000L, null, null,
      WorkBuildPrevious(WorkBuildState.PASSED, 2_000L, startedAt.minusSeconds(1)),
    )
    val execution = IdeStatusExecutionIdentity(IdeStatusExecutionScope.WORKFLOW, "e", "s", "1", "1")
    val boardItem = board("wf-1", execution).copy(build = build).toWorkItem(1L)

    assertEquals(DesktopBuildState.Running, boardItem.build?.state)
    assertEquals("./gradlew check", boardItem.build?.command)
    assertEquals(42L, boardItem.build?.pid)
    assertEquals(startedAt.toKotlinInstant(), boardItem.build?.startedAt)
    assertEquals(DesktopBuildState.Passed, boardItem.build?.previous?.state)
    assertEquals(null, board("wf-2", execution).toWorkItem(1L).build)

    val failedBuild = build.copy(state = WorkBuildState.FAILED, exitCode = 1, previous = null)
    val detailItem = WorkDetailItem(
      snapshot = IdeStatusSnapshot(
        "repo", IdeStatusLifecycleState.ACTIVE, IdeStatusStep("implement", "Implement"), startedAt,
        IdeStatusFreshness.FRESH, "Implementing", "Feature", workflowId = "wf-3", execution = execution,
      ),
      recoveryWorkId = "wf-3", repositoryRoot = "/repo", stepPipeline = emptyList(), build = failedBuild,
    ).toWorkItem(1L)

    assertEquals(DesktopBuildState.Failed to 1, detailItem.build?.state to detailItem.build?.exitCode)
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
