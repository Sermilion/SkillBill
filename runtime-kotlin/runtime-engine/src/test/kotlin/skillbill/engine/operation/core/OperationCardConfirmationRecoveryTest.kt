package skillbill.engine.operation.core

import skillbill.contracts.operationcard.OperationCardDetails
import skillbill.contracts.operationcard.OperationCardOperationIds
import skillbill.contracts.operationcard.OperationCardProjection
import skillbill.contracts.operationcard.OperationCardStatus
import skillbill.contracts.operationcard.OperationLaunchContext
import skillbill.contracts.operationcard.OperationLaunchRetention
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.operation.EmptyOperationCardRepository
import skillbill.ports.operation.OperationCardRepository
import skillbill.ports.operation.OperationProposalRepository
import skillbill.ports.operation.model.ConfirmationExecution
import skillbill.ports.operation.model.ConfirmationExecutionState
import skillbill.ports.operation.model.OperationAnchors
import skillbill.ports.operation.model.OperationProposal
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.branchworktree.RecordingBranchWorktreeResources
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OperationCardConfirmationRecoveryTest {
  private val owner = FeatureTaskRuntimeProcessIdentity("host-a", "boot-1", 4242, "birth-1")
  private val desktop = FeatureTaskRuntimeProcessIdentity("host-a", "boot-1", 5151, "birth-2")
  private val clock = Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC)
  private val launch =
    OperationLaunchContext(
      launchId = LAUNCH_ID,
      operationId = OperationCardOperationIds.VERIFY,
      repositoryIdentity = "repo-identity",
      databaseIdentity = DATABASE,
      checkoutRoot = CHECKOUT,
      branch = "main",
      originalArguments = listOf("operation", "verify"),
      selectedAgent = "claude",
      holderId = "holder-1",
      retention = OperationLaunchRetention.MANAGED_HOLDER,
    )

  @Test
  fun `a confirmation that consumed its proposal and lost its process settles consumed-uncertain and never replays`() {
    val store = ConfirmingCardStore(confirmingCard(), launch, claimedExecution())
    val worktrees = RecordingBranchWorktreeResources()
    val lifecycle = lifecycle(store, worktrees, FeatureTaskRuntimeProcessInspection.NotRunning)

    val shown = assertIs<OperationCardRevalidation.Present>(lifecycle.revalidate(TOKEN, DATABASE))

    assertEquals(OperationCardStatus.CONSUMED_UNCERTAIN, shown.card.status)
    assertFalse(shown.canConfirm)
    assertTrue(shown.reason.orEmpty().contains("It will not run again; start a new proposal from chat."), shown.reason)
    assertEquals(listOf(Path.of(CHECKOUT)), worktrees.released.map { it.first })

    val replay = lifecycle.resolve(confirmRequest(), Path.of(CHECKOUT))
    assertIs<OperationLaunchResolution.Refused>(replay)
    val again = assertIs<OperationCardRevalidation.Present>(lifecycle.revalidate(TOKEN, DATABASE))
    assertEquals(OperationCardStatus.CONSUMED_UNCERTAIN, again.card.status)
    assertFalse(again.canConfirm)
  }

  @Test
  fun `a confirmation whose process cannot be verified stays confirming and keeps its checkout`() {
    val store = ConfirmingCardStore(confirmingCard(), launch, claimedExecution())
    val worktrees = RecordingBranchWorktreeResources()
    val lifecycle = lifecycle(store, worktrees, FeatureTaskRuntimeProcessInspection.PidReused)

    val shown = assertIs<OperationCardRevalidation.Present>(lifecycle.revalidate(TOKEN, DATABASE))

    assertEquals(OperationCardStatus.CONFIRMING, shown.card.status)
    assertFalse(shown.canConfirm)
    assertTrue(shown.reason.orEmpty().contains("cannot be verified"), shown.reason)
    assertEquals(emptyList(), worktrees.released)
  }

  private fun lifecycle(
    store: OperationCardRepository,
    worktrees: RecordingBranchWorktreeResources,
    inspection: FeatureTaskRuntimeProcessInspection,
  ): RepositoryOperationCardLifecycle {
    val proposals = FixedProposals(consumedProposal())
    return RepositoryOperationCardLifecycle(
      store,
      proposals,
      OperationConfirmationGate(proposals, store, NoopWorkflowGitOperations, clock),
      worktrees,
      OperationRegistry(emptyList()),
      FixedInspector(desktop, inspection),
      clock,
      NoopRuntimeDiagnostics,
    )
  }

  private fun confirmingCard(): OperationCardProjection =
    OperationCardProjection(
      launchId = LAUNCH_ID,
      operationId = OperationCardOperationIds.VERIFY,
      invocationId = "inv-propose",
      repositoryIdentity = "repo-identity",
      databaseIdentity = DATABASE,
      checkoutRoot = CHECKOUT,
      branch = "main",
      sourceAnchors = mapOf(),
      originalArguments = listOf("operation", "verify"),
      selectedAgent = "claude",
      token = TOKEN,
      fullSummary = "Verify SKILL-1",
      proposalDetails = OperationCardDetails.Verify(listOf("AC1"), "SKILL-1", null, "main", "audit", "skip"),
      status = OperationCardStatus.CONFIRMING,
    )

  private fun claimedExecution(): ConfirmationExecution =
    ConfirmationExecution(
      executionId = 7,
      token = TOKEN,
      launchId = LAUNCH_ID,
      state = ConfirmationExecutionState.CLAIMED,
      admissionOwner = desktop,
      invocationId = "inv-confirm",
      childOwner = owner,
    )

  private fun consumedProposal(): OperationProposal =
    OperationProposal(
      token = TOKEN,
      operationId = OperationCardOperationIds.VERIFY,
      repoRoot = CHECKOUT,
      anchors = OperationAnchors("abc123", "main"),
      proposalValue = "Verify SKILL-1",
      createdAt = "2026-11-05T11:00:00Z",
      consumedAt = "2026-11-05T11:30:00Z",
    )

  private fun confirmRequest(): OperationRequest =
    OperationRequest(
      operationId = OperationCardOperationIds.VERIFY,
      repoRoot = Path.of(CHECKOUT),
      invokedAgentId = "claude",
      arguments = OperationArguments(confirm = TOKEN),
      instructions = null,
      launchId = LAUNCH_ID,
    )

  private class ConfirmingCardStore(
    private var card: OperationCardProjection,
    private val launch: OperationLaunchContext,
    private var active: ConfirmationExecution?,
  ) : OperationCardRepository by EmptyOperationCardRepository {
    private var pending: ConfirmationExecution? = null

    override fun databaseIdentity(): String = launch.databaseIdentity

    override fun findLaunchContext(launchId: String): OperationLaunchContext? =
      launch.takeIf { it.launchId == launchId }

    override fun find(token: String): OperationCardProjection? = card.takeIf { it.token == token }

    override fun activeExecution(token: String): ConfirmationExecution? = active?.takeIf { it.token == token }

    override fun settleConfirmation(
      token: String,
      executionId: Long,
      to: OperationCardStatus,
      evidence: String?,
      at: String,
    ): Boolean {
      val current = active?.takeIf { it.token == token && it.executionId == executionId } ?: return false
      if (card.status != OperationCardStatus.CONFIRMING) return false
      card = card.copy(status = to, evidence = evidence)
      active = null
      if (to != OperationCardStatus.AWAITING_CONFIRMATION) pending = current
      return true
    }

    override fun pendingCleanup(token: String): ConfirmationExecution? = pending?.takeIf { it.token == token }

    override fun completeCleanup(executionId: Long) {
      if (pending?.executionId == executionId) pending = null
    }
  }

  private class FixedProposals(private val proposal: OperationProposal) : OperationProposalRepository {
    override fun createSupersedingPrior(proposal: OperationProposal) = Unit

    override fun find(token: String): OperationProposal? = proposal.takeIf { it.token == token }

    override fun markConsumed(
      token: String,
      consumedAt: String,
    ): Boolean = false
  }

  private class FixedInspector(
    private val current: FeatureTaskRuntimeProcessIdentity,
    private val inspection: FeatureTaskRuntimeProcessInspection,
  ) : MonitorProcessInspector {
    override fun currentProcess(): FeatureTaskRuntimeProcessIdentity = current

    override fun inspect(recorded: FeatureTaskRuntimeProcessIdentity): FeatureTaskRuntimeProcessInspection = inspection
  }

  private companion object {
    const val TOKEN = "opt-1"
    const val LAUNCH_ID = "opl-1"
    const val DATABASE = "db-1"
    const val CHECKOUT = "/work/repo"
  }
}
