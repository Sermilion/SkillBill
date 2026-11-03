package skillbill.engine.goalrunner

import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.engine.goalrunner.execution.core.goalRunnerDeps
import skillbill.engine.goalrunner.execution.core.testGoalRunner
import skillbill.engine.goalrunner.launch.GoalBranchCheckouts
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.goalworktree.GoalWorktreeResources
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import skillbill.workflow.decomposition.withWorkflowId
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoalRunnerExecutionRootTest {
  private val launchingRoot = Path.of("/tmp/skillbill-goal-runner")
  private val worktree = Path.of("/tmp/skillbill-goal-state/goals/repo/SKILL-56/checkout")

  private fun request() = GoalRunnerRunRequest("SKILL-56", launchingRoot, "claude")

  @Test
  fun `a new goal runs every branch checkout and child in the created worktree`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher {
        store.mutate { current -> current.withWorkflowId(1, "wfl-1") }
        outcomes["wfl-1"] = completeOutcome(1)
        launchFacts()
      }
    val git = BranchRootRecordingGit()
    val worktrees = RecordingGoalWorktrees(GoalWorktreeResult.Created(worktree))
    val runner =
      testGoalRunner(
        goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort())
          .copy(gitOperations = git, goalWorktrees = worktrees),
      )

    runner.run(request())

    assertEquals(worktree, launcher.requests.single().skillRunRequest.repoRoot)
    assertEquals(1, worktrees.creations)
    assertTrue(git.checkoutRoots.isNotEmpty())
    assertEquals(setOf(worktree), git.checkoutRoots.toSet())
    assertEquals(worktree.toString(), store.controlState("parent").executionWorktreePath)
  }

  @Test
  fun `a goal with a launched child and no recorded path stays at its bound root`() {
    val bound = Files.createTempDirectory("goal-bound-root").toRealPath()
    val identity = FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX + bound
    val store =
      InMemoryGoalManifestStore(
        manifest = manifest(subtaskCount = 1).withWorkflowId(1, "wfl-1"),
        initialControlState = GoalRunnerControlState(repositoryIdentity = identity),
      )
    val outcomes = RecordingOutcomeStore().apply { seedReviewState("wfl-1") }
    val launcher =
      RecordingSubtaskLauncher {
        outcomes["wfl-1"] = completeOutcome(1)
        launchFacts()
      }
    val worktrees = RecordingGoalWorktrees(GoalWorktreeResult.Created(worktree))
    val runner =
      testGoalRunner(
        goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()).copy(goalWorktrees = worktrees),
      )

    runner.run(request())

    assertEquals(bound, launcher.requests.single().skillRunRequest.repoRoot)
    assertEquals(0, worktrees.creations)
    assertEquals(null, store.controlState("parent").executionWorktreePath)
  }

  @Test
  fun `a branch held by another checkout blocks and names that checkout`() {
    val holder = Path.of("/tmp/skillbill-main-checkout")
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val launcher = RecordingSubtaskLauncher { launchFacts() }
    val runner =
      testGoalRunner(
        goalRunnerDeps(store, launcher, RecordingOutcomeStore(), RecordingPullRequestPort())
          .copy(goalWorktrees = RecordingGoalWorktrees(GoalWorktreeResult.Created(worktree), holder)),
      )

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(request()))

    assertEquals(GoalRunnerStopReason.BLOCKED, stopped.stop.reason)
    assertEquals(
      "Goal 'SKILL-56' needs branch 'feat/SKILL-56-goal', which is checked out at $holder. " +
        "Switch that checkout to another branch, then run skill-bill goal SKILL-56 again.",
      stopped.stop.blockedReason,
    )
    assertTrue(launcher.requests.isEmpty())
  }

  @Test
  fun `a branch held by the repository enclosing the execution root is not held elsewhere`() {
    val root = Files.createTempDirectory("goal-enclosing-root").toRealPath()
    Files.createDirectories(root.resolve(".git"))
    val subdirectory = Files.createDirectories(root.resolve("module"))
    val checkouts =
      GoalBranchCheckouts(
        NoopWorkflowGitOperations,
        RecordingGoalWorktrees(GoalWorktreeResult.Adopted(root), holder = root),
        TestRepositoryEnclosingRoot,
      )

    assertEquals(null, checkouts.heldElsewhereReason("SKILL-56", "feat/SKILL-56-goal", subdirectory))
  }
}

private class RecordingGoalWorktrees(
  private val result: GoalWorktreeResult,
  private val holder: Path? = null,
) : GoalWorktreeResources {
  var creations = 0

  override fun createOrAdopt(
    repoRoot: Path,
    issueKey: String,
    recordedPath: Path?,
    revision: String,
  ): GoalWorktreeResult {
    creations += 1
    return result
  }

  override fun checkoutHolding(
    repoRoot: Path,
    branch: String,
  ): Path? = holder
}

private class BranchRootRecordingGit : WorkflowGitOperations by NoopWorkflowGitOperations {
  val checkoutRoots = mutableListOf<Path>()

  override fun checkoutBranch(
    repoRoot: Path,
    branch: String,
    baseBranch: String?,
  ): WorkflowGitOperationResult {
    checkoutRoots.add(repoRoot)
    return WorkflowGitOperationResult.Ok(value = branch)
  }
}
