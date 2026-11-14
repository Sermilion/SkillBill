package skillbill.engine.operation.core

import skillbill.engine.featuretask.runloop.observability.AgentBuildObserverFactory
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.ports.operation.UnavailableOperationCardRepository
import skillbill.ports.operation.UnavailableOperationProposalRepository
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OperationExecutorBranchWorktreeTest {
  private val repoRoot: Path = Files.createTempDirectory("operation-branch-worktree")
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
      OperationConfirmationGate(
        UnavailableOperationProposalRepository,
        UnavailableOperationCardRepository,
        NoopWorkflowGitOperations,
        Clock.systemUTC(),
      ),
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
      NoOperationCardLifecycle,
    )

  @Test
  fun `a completed operation keeps its worktree until finalize`() {
    val completed = assertIs<OperationOutcome.Completed>(execute())

    assertEquals("Verified.", completed.text)
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
  }

  private fun execute(): OperationOutcome =
    executor.execute(OperationRequest("verify", repoRoot, "claude", OperationArguments(), "SKILL-1")).outcome
}
