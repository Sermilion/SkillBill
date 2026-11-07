package skillbill.engine.operation.core

import skillbill.engine.featuretask.runloop.observability.AgentBuildObserverFactory
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.ports.operation.UnavailableOperationProposalRepository
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.workflow.branchworktree.RecordingBranchWorktreeResources
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolder
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolderKind
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeRelease
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OperationExecutorBranchWorktreeTest {
  private val repoRoot: Path = Files.createTempDirectory("operation-branch-worktree")
  private val worktrees = RecordingBranchWorktreeResources()
  private var outcome: OperationOutcome = OperationOutcome.Completed("Verified.")
  private val executor =
    OperationExecutor(
      OperationRegistry(
        listOf(
          object : Operation {
            override val id: String = "verify"

            override fun run(context: OperationContext): OperationRunResult = OperationRunResult.Finished(outcome)
          },
        ),
      ),
      OperationConfirmationGate(UnavailableOperationProposalRepository, NoopWorkflowGitOperations, Clock.systemUTC()),
      OperationStepRunner(
        object : PhaseRunner {
          override fun run(
            input: PhaseStepInput,
            state: PhaseLaunchState,
          ): PhaseStepOutput = error("The fake operation launches no agent step.")
        },
        NoopWorkflowGitOperations,
        AgentBuildObserverFactory.NONE,
      ),
      FixedProjectStorePort(repoRoot.resolve("project-store")),
      worktrees,
    )

  @Test
  fun `a completed operation releases its worktree and reports one it had to keep`() {
    worktrees.releaseResult = BranchWorktreeRelease.Kept(repoRoot, "git worktree remove failed: dirty")

    val completed = assertIs<OperationOutcome.Completed>(execute())

    assertEquals(
      listOf(repoRoot to BranchWorktreeHolder(BranchWorktreeHolderKind.OPERATION, "verify")),
      worktrees.released,
    )
    assertTrue(completed.text.startsWith("Verified."), completed.text)
    assertTrue(repoRoot.toString() in completed.text && "dirty" in completed.text, completed.text)
  }

  @Test
  fun `a parked or blocked operation keeps its worktree held`() {
    listOf(
      OperationOutcome.AwaitingConfirmation("wf-1", "Confirm the criteria."),
      OperationOutcome.Blocked("rehydrate-needed"),
      OperationOutcome.Failed("the agent stopped"),
    ).forEach { parked ->
      outcome = parked
      assertEquals(parked, execute())
    }

    assertEquals(emptyList(), worktrees.released)
  }

  private fun execute(): OperationOutcome =
    executor.execute(OperationRequest("verify", repoRoot, "claude", OperationArguments(), "SKILL-1")).outcome
}
