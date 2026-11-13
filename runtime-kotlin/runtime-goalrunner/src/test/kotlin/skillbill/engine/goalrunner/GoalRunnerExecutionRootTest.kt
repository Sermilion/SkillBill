package skillbill.engine.goalrunner

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.execution.core.DIRECT_GOAL_RUNNER_EXECUTION_COORDINATOR
import skillbill.engine.goalrunner.execution.core.GoalRunnerExecutionCoordinator
import skillbill.engine.goalrunner.execution.core.GoalRunnerOwnedRun
import skillbill.engine.goalrunner.execution.core.goalRunnerDeps
import skillbill.engine.goalrunner.execution.core.testGoalRunner
import skillbill.engine.goalrunner.launch.GoalBranchCheckouts
import skillbill.engine.goalrunner.model.GoalRunnerLeasePurpose
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.monitoring.GoalWorktreeCompletionSettlement
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import skillbill.ports.monitor.model.MonitorRetentionReason
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.goalworktree.GoalWorktreeResources
import skillbill.ports.workflow.goalworktree.model.GoalBranchCheckout
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeSettleRequest
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeSettlement
import skillbill.workflow.decomposition.withWorkflowId
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoalRunnerExecutionRootTest {
  private val launchingRoot = Path.of("/tmp/skillbill-goal-runner")
  private val worktree = Path.of("/tmp/skillbill-goal-state/goals/repo/SKILL-56/checkout")

  private fun request() = GoalRunnerRunRequest("SKILL-56", launchingRoot, "claude")

  @Test
  fun `already complete goal is reusable by issue key and does not bind as the run target`() {
    val worktree = Files.createTempDirectory("goal-completion-retry").toRealPath()
    val store =
      InMemoryGoalManifestStore(
        manifest(subtaskCount = 0).copy(issueKey = "SKILL-56", status = "complete"),
        initialControlState =
          GoalRunnerControlState(
            goalCompletedAt = "2026-11-03T00:00:00Z",
            executionWorktreePath = worktree.toString(),
          ),
      )
    val worktrees =
      RecordingGoalWorktrees(
        GoalWorktreeResult.Created(worktree),
        settlementResult =
          GoalWorktreeSettlement.Retained(
            MonitorRetentionReason.UNPUBLISHED_COMMITS,
            worktree,
            "the worktree has 1 unpublished commit",
          ),
      )
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          store,
          RecordingSubtaskLauncher { error("A completed goal must not launch.") },
          RecordingOutcomeStore(),
          RecordingPullRequestPort(),
        ).copy(goalWorktrees = worktrees),
      )

    val report = runner.run(request())

    assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.BLOCKED, report.stop.reason)
    assertTrue(runner.requiresPlanning(request()))
    assertEquals(0, worktrees.settlements)
  }

  @Test
  fun `a linked child missing checkout refusal is settled without requesting child execution admission`() {
    val store =
      InMemoryGoalManifestStore(
        manifest(subtaskCount = 1).withWorkflowId(1, "existing-child"),
        initialControlState =
          GoalRunnerControlState(
            executionWorktreePath =
              Files.createTempDirectory(
                "linked-child-checkout-refusal",
              ).resolve("missing").toString(),
          ),
      )
    var settlements = 0
    val coordinator =
      object : GoalRunnerExecutionCoordinator by DIRECT_GOAL_RUNNER_EXECUTION_COORDINATOR {
        override fun <T> runOwnedWithLease(
          parentWorkflowId: String,
          purpose: GoalRunnerLeasePurpose,
          block: (Long, String?) -> T,
        ): GoalRunnerOwnedRun<T> {
          assertEquals(GoalRunnerLeasePurpose.BLOCK_SETTLEMENT, purpose)
          settlements += 1
          return GoalRunnerOwnedRun.Completed(block(1, null))
        }
      }
    val launcher = RecordingSubtaskLauncher { error("A refused checkout must not launch a child") }
    val runner =
      testGoalRunner(
        goalRunnerDeps(store, launcher, RecordingOutcomeStore(), RecordingPullRequestPort()).copy(
          executionCoordinator = coordinator,
        ),
      )
    val report = assertIs<GoalRunnerRunReport.Stopped>(runner.run(request()))
    assertEquals(GoalRunnerStopReason.BLOCKED, report.stop.reason)
    assertTrue(
      report.stop.blockedReason.orEmpty().contains("recorded execution checkout"),
      report.stop.blockedReason,
    )
    assertEquals(1, settlements)
    assertTrue(launcher.requests.isEmpty())
    assertEquals("existing-child", store.manifest.subtasks.single().workflowId)
  }

  @Test
  fun `a new goal runs every branch checkout and child in the created worktree`() {
    val worktree = Files.createTempDirectory("goal-settlement-failure").toRealPath()
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher {
        store.mutate { current -> current.withWorkflowId(1, "wfl-1") }
        outcomes["wfl-1"] = completeOutcome(1)
        launchFacts()
      }
    val git = BranchRootRecordingGit()
    val diagnostics = WarningRecordingDiagnostics()
    val worktrees =
      RecordingGoalWorktrees(
        GoalWorktreeResult.Created(worktree),
        settlementFailure = IllegalStateException("worktree removal failed"),
      )
    val runner =
      testGoalRunner(
        goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort())
          .copy(gitOperations = git, goalWorktrees = worktrees, diagnostics = diagnostics),
      )

    val result = runner.run(request())
    val report = assertIs<GoalRunnerRunReport.Completed>(result, "$result ${diagnostics.warnings}")

    assertEquals("SKILL-56", report.issueKey)
    assertEquals(worktree, launcher.requests.single().skillRunRequest.repoRoot)
    assertEquals(1, worktrees.creations)
    assertTrue(git.checkoutRoots.isNotEmpty())
    assertEquals(setOf(worktree), git.checkoutRoots.toSet())
    assertEquals(worktree.toString(), store.controlState("parent").executionWorktreePath)
    assertEquals("cleanup_failed", store.controlState("parent").executionWorktreeRetentionReason)
  }

  private fun completeOneSubtaskGoal(
    worktrees: RecordingGoalWorktrees,
    diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
  ): InMemoryGoalManifestStore {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher {
        store.mutate { current -> current.withWorkflowId(1, "wfl-1") }
        outcomes["wfl-1"] = completeOutcome(1)
        launchFacts()
      }
    val runner =
      testGoalRunner(
        goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort())
          .copy(gitOperations = BranchRootRecordingGit(), goalWorktrees = worktrees, diagnostics = diagnostics),
      )
    assertIs<GoalRunnerRunReport.Completed>(runner.run(request()))
    return store
  }

  @Test
  fun `a removed worktree records a failed main checkout switch without retaining it`() {
    val worktrees =
      RecordingGoalWorktrees(
        GoalWorktreeResult.Created(Files.createTempDirectory("goal-main-checkout").toRealPath()),
        settlementResult = GoalWorktreeSettlement.Removed,
        checkoutResult = GoalBranchCheckout.Failed("a rebase is in progress in the main checkout"),
      )

    val control = completeOneSubtaskGoal(worktrees).controlState("parent")

    assertTrue(control.executionWorktreeRemovedAt != null)
    assertEquals("failed", control.executionMainCheckoutOutcome)
    assertEquals("a rebase is in progress in the main checkout", control.executionMainCheckoutDetail)
    assertEquals(null, control.executionWorktreeRetentionReason)
    assertEquals(listOf("feat/SKILL-56-goal"), worktrees.checkoutBranches)
  }

  @Test
  fun `a retained worktree never switches the main checkout`() {
    val dir = Files.createTempDirectory("goal-main-checkout-retained").toRealPath()
    val worktrees =
      RecordingGoalWorktrees(
        GoalWorktreeResult.Created(dir),
        settlementResult = GoalWorktreeSettlement.Retained(MonitorRetentionReason.DIRTY_WORKTREE, dir, "dirty"),
      )

    val control = completeOneSubtaskGoal(worktrees).controlState("parent")

    assertTrue(worktrees.checkoutBranches.isEmpty())
    assertEquals(null, control.executionMainCheckoutOutcome)
    assertEquals(null, control.executionMainCheckoutDetail)
    assertEquals("dirty_worktree", control.executionWorktreeRetentionReason)
  }

  @Test
  fun `a throwing main checkout switch still records the removal`() {
    val worktrees =
      RecordingGoalWorktrees(
        GoalWorktreeResult.Created(Files.createTempDirectory("goal-main-checkout-throw").toRealPath()),
        settlementResult = GoalWorktreeSettlement.Removed,
        checkoutFailure = IllegalStateException("boom"),
      )
    val diagnostics = WarningRecordingDiagnostics()

    val control = completeOneSubtaskGoal(worktrees, diagnostics).controlState("parent")

    assertTrue(control.executionWorktreeRemovedAt != null)
    assertEquals("failed", control.executionMainCheckoutOutcome)
    assertEquals("boom", control.executionMainCheckoutDetail)
    assertEquals(null, control.executionWorktreeRetentionReason)
    assertTrue(
      diagnostics.warnings.any { "seam=goal_worktree.main_checkout" in it },
      diagnostics.warnings.toString(),
    )
  }

  @Test
  fun `a goal without a feature branch records a failed switch without calling the port`() {
    val dir = Files.createTempDirectory("goal-main-checkout-no-branch").toRealPath()
    val worktrees =
      RecordingGoalWorktrees(GoalWorktreeResult.Created(dir), settlementResult = GoalWorktreeSettlement.Removed)
    val store =
      InMemoryGoalManifestStore(
        manifest(subtaskCount = 0).copy(featureBranch = null),
        initialControlState =
          GoalRunnerControlState(goalCompletedAt = "2026-11-03T00:00:00Z", executionWorktreePath = dir.toString()),
      )
    val state =
      GoalRunnerManifestState("parent", "/tmp/skillbill-goal-runner/metrics.db", store.manifest, store.controlState)
    val settlement =
      GoalWorktreeCompletionSettlement(
        store,
        worktrees,
        TestRepositoryEnclosingRoot,
        NoopRuntimeDiagnostics,
        Clock.systemUTC(),
        FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
      )

    settlement.settle(state)

    val control = store.controlState("parent")
    assertTrue(control.executionWorktreeRemovedAt != null)
    assertEquals("failed", control.executionMainCheckoutOutcome)
    assertEquals("the goal has no feature branch", control.executionMainCheckoutDetail)
    assertTrue(worktrees.checkoutBranches.isEmpty())
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
        "Switch that checkout to another branch, then run sona goal SKILL-56 again.",
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
  private val settlementFailure: RuntimeException? = null,
  private val settlementResult: GoalWorktreeSettlement = GoalWorktreeSettlement.Absent,
  private val checkoutResult: GoalBranchCheckout = GoalBranchCheckout.CheckedOut,
  private val checkoutFailure: RuntimeException? = null,
) : GoalWorktreeResources {
  var creations = 0
  var settlements = 0
  val checkoutBranches = mutableListOf<String>()

  override fun plannedPath(
    repoRoot: Path,
    issueKey: String,
    reservationId: String?,
  ): Path = repoRoot

  override fun gitWorktreeIdentity(path: Path): String? = null

  override fun checkoutGoalBranch(
    primaryRoot: Path,
    branch: String,
  ): GoalBranchCheckout {
    checkoutBranches += branch
    checkoutFailure?.let { throw it }
    return checkoutResult
  }

  override fun createOrAdopt(
    repoRoot: Path,
    issueKey: String,
    recordedPath: Path?,
    revision: String,
    ownership: GoalPlanningOwnership?,
  ): GoalWorktreeResult {
    creations += 1
    return result
  }

  override fun checkoutHolding(
    repoRoot: Path,
    branch: String,
  ): Path? = holder

  override fun settle(request: GoalWorktreeSettleRequest): GoalWorktreeSettlement {
    settlements += 1
    settlementFailure?.let { throw it }
    return settlementResult
  }

  override fun retentionVerdict(request: GoalWorktreeSettleRequest): GoalWorktreeSettlement.Retained? = null
}

private class WarningRecordingDiagnostics : RuntimeDiagnostics by NoopRuntimeDiagnostics {
  val warnings = mutableListOf<String>()

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    warnings += message
  }
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
