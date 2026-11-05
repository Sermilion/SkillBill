package skillbill.engine.operation.core

import skillbill.contracts.operationcard.OperationCardOperationIds
import skillbill.contracts.operationcard.OperationCardProjection
import skillbill.contracts.operationcard.OperationLaunchContext
import skillbill.contracts.operationcard.OperationLaunchRetention
import skillbill.engine.goal.goalRepositoryRootFromIdentity
import skillbill.ports.operation.OperationProposalRepository
import skillbill.ports.workflow.branchworktree.BranchWorktreeResources
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolder
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolderKind
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeObservation
import java.nio.file.Path

internal class OperationCardEligibility(
  private val proposals: OperationProposalRepository,
  private val gate: OperationConfirmationGate,
  private val branchWorktrees: BranchWorktreeResources,
  private val registry: OperationRegistry,
) {
  fun refusalReason(
    card: OperationCardProjection,
    launch: OperationLaunchContext?,
    databaseIdentity: String,
  ): String? {
    val identityReason = identityRefusal(card, launch, databaseIdentity)
    if (identityReason != null || launch == null) return identityReason ?: LAUNCH_UNAVAILABLE
    return proposalRefusal(card, launch) ?: checkoutRefusal(card, launch) ?: verifyRefusal(card, launch)
  }

  private fun proposalRefusal(
    card: OperationCardProjection,
    launch: OperationLaunchContext,
  ): String? {
    val proposal = proposals.find(card.token)
    val open =
      proposal != null && proposal.operationId == card.operationId && proposal.repoRoot == launch.checkoutRoot &&
        proposal.consumedAt == null && proposal.supersededAt == null
    return "The original proposal is no longer available; start a new proposal from chat.".takeUnless { open }
  }

  private fun checkoutRefusal(
    card: OperationCardProjection,
    launch: OperationLaunchContext,
  ): String? {
    val observed = observedCheckout(branchWorktrees.observe(Path.of(launch.checkoutRoot), holder(launch)), launch)
    if (observed == null || !validCheckout(card, launch, observed)) {
      val legacyReason = if (launch.retention == null) " Its original retention evidence was not recorded." else ""
      return "The original checkout or its retained ownership is unavailable.$legacyReason " +
        "Start a new proposal from chat."
    }
    return gate.revalidateAuthority(card.token, card.operationId, launch.checkoutRoot, observed.head, observed.branch)
      ?.reason
  }

  private fun identityRefusal(
    card: OperationCardProjection,
    launch: OperationLaunchContext?,
    databaseIdentity: String,
  ): String? =
    when {
      launch == null -> LAUNCH_UNAVAILABLE
      card.databaseIdentity != databaseIdentity || launch.databaseIdentity != databaseIdentity ->
        "The selected database changed; start a new proposal from chat."
      card.operationId != launch.operationId || card.repositoryIdentity != launch.repositoryIdentity ||
        card.checkoutRoot != launch.checkoutRoot ->
        "The original proposal identity changed; start a new proposal from chat."
      else -> null
    }

  private fun observedCheckout(
    observation: BranchWorktreeObservation,
    launch: OperationLaunchContext,
  ): ObservedCheckout? =
    when (observation) {
      is BranchWorktreeObservation.CallerOwned ->
        ObservedCheckout(
          observation.checkoutRoot,
          observation.primaryRepositoryRoot,
          observation.branch,
          observation.head,
          retained = launch.retention == OperationLaunchRetention.CALLER_OWNED,
        )
      is BranchWorktreeObservation.RuntimeManaged ->
        ObservedCheckout(
          observation.checkoutRoot,
          observation.primaryRepositoryRoot,
          observation.branch,
          observation.head,
          retained =
            (launch.retention == OperationLaunchRetention.MANAGED_HOLDER || launch.retention == null) &&
              observation.holderPresent,
        )
      is BranchWorktreeObservation.Unavailable -> null
    }

  private fun validCheckout(
    card: OperationCardProjection,
    launch: OperationLaunchContext,
    observed: ObservedCheckout,
  ): Boolean {
    val expectedRoot = goalRepositoryRootFromIdentity(card.repositoryIdentity) ?: return false
    val sameCheckout = observed.checkoutRoot.toString() == launch.checkoutRoot && observed.primaryRoot == expectedRoot
    val sameSource = observed.branch == card.branch && observed.head == card.sourceAnchors[HEAD_SHA_ANCHOR]
    return observed.retained && sameCheckout && sameSource
  }

  private data class ObservedCheckout(
    val checkoutRoot: Path,
    val primaryRoot: Path,
    val branch: String,
    val head: String,
    val retained: Boolean,
  )

  private fun verifyRefusal(
    card: OperationCardProjection,
    launch: OperationLaunchContext,
  ): String? {
    if (card.operationId != OperationCardOperationIds.VERIFY) return null
    val verify = registry.find(card.operationId) as? SelfConfirmingOperation
    return "The verify workflow is no longer parked; start a new proposal from chat."
      .takeUnless { verify?.confirmable(card.token, Path.of(launch.checkoutRoot)) == true }
  }

  private fun holder(launch: OperationLaunchContext): BranchWorktreeHolder =
    BranchWorktreeHolder(BranchWorktreeHolderKind.OPERATION, launch.holderId)

  private companion object {
    const val LAUNCH_UNAVAILABLE = "The original launch context is unavailable; start a new proposal from chat."
  }
}
