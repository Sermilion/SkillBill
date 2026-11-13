package skillbill.engine.operation.core

import skillbill.contracts.operationcard.OperationCardProjection
import skillbill.contracts.operationcard.OperationCardStatus
import skillbill.contracts.operationcard.OperationLaunchContext
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.operation.OperationCardRepository
import skillbill.ports.operation.OperationProposalRepository
import skillbill.ports.operation.model.ConfirmationExecution
import skillbill.ports.operation.model.ConfirmationExecutionState
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.taskruntime.model.isConfirmedDead
import skillbill.ports.workflow.branchworktree.BranchWorktreeResources
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolder
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolderKind
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeRelease
import java.nio.file.Path
import java.time.Clock

internal class OperationConfirmationRecovery(
  private val cards: OperationCardRepository,
  private val proposals: OperationProposalRepository,
  private val eligibility: OperationCardEligibility,
  private val branchWorktrees: BranchWorktreeResources,
  private val registry: OperationRegistry,
  private val processes: MonitorProcessInspector,
  private val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
) {
  fun currentProcess(): FeatureTaskRuntimeProcessIdentity = processes.currentProcess()

  fun reconcile(card: OperationCardProjection): OperationCardRevalidation.Present {
    val active = cards.activeExecution(card.token) ?: return unrecorded(card)
    val reason =
      when (liveness(active.childOwner ?: active.admissionOwner)) {
        OwnerLiveness.LIVE ->
          if (active.state == ConfirmationExecutionState.CLAIMED) RUNNING else STARTING
        OwnerLiveness.UNKNOWN -> {
          diagnostics.warning("seam=operation-card.reconcile outcome=owner_unverifiable token=${card.token}")
          UNVERIFIABLE
        }
        OwnerLiveness.DEAD -> {
          endExecution(card, active)
          null
        }
      }
    val current = cards.find(card.token) ?: card
    return OperationCardRevalidation.Present(current, false, reason ?: current.evidence ?: current.status.wireValue)
  }

  fun childEnded(
    token: String,
    executionId: Long,
  ) {
    val card = cards.find(token) ?: return
    if (card.status != OperationCardStatus.CONFIRMING) return finishCleanup(card)
    val active = cards.activeExecution(token)
    if (active != null && active.executionId == executionId) endExecution(card, active)
  }

  fun finishCleanup(card: OperationCardProjection) {
    val pending = cards.pendingCleanup(card.token) ?: return
    if (card.status != OperationCardStatus.COMPLETED) {
      cards.findLaunchContext(pending.launchId)?.let(::releaseHolder)
    }
    cards.completeCleanup(pending.executionId)
  }

  fun releaseHolder(launch: OperationLaunchContext): BranchWorktreeRelease {
    val released = branchWorktrees.release(Path.of(launch.checkoutRoot), holder(launch))
    if (released is BranchWorktreeRelease.Kept) {
      diagnostics.warning(
        "seam=operation-card.release outcome=kept path=${released.path} reason=${released.detail}",
      )
    }
    return released
  }

  fun withdraw(
    operationId: String,
    token: String,
  ) {
    (registry.find(operationId) as? SelfConfirmingOperation)?.withdraw(token)
  }

  private fun endExecution(
    card: OperationCardProjection,
    active: ConfirmationExecution,
  ) {
    val launch = cards.findLaunchContext(card.launchId)
    val unconsumed = proposals.find(card.token)?.let { it.consumedAt == null && it.supersededAt == null } == true
    val (target, evidence) =
      when {
        !unconsumed -> OperationCardStatus.CONSUMED_UNCERTAIN to INTERRUPTED_AFTER_CONSUMPTION
        eligibility.refusalReason(card, launch, cards.databaseIdentity()) == null ->
          OperationCardStatus.AWAITING_CONFIRMATION to INTERRUPTED_BEFORE_CONSUMPTION
        else -> OperationCardStatus.REFUSED to INTERRUPTED_AND_INELIGIBLE
      }
    if (!cards.settleConfirmation(card.token, active.executionId, target, evidence, clock.instant().toString())) return
    diagnostics.warning("seam=operation-card.reconcile outcome=${target.wireValue} token=${card.token}")
    if (target == OperationCardStatus.REFUSED) withdraw(card.operationId, card.token)
    if (target != OperationCardStatus.AWAITING_CONFIRMATION) finishCleanup(card)
  }

  private fun unrecorded(card: OperationCardProjection): OperationCardRevalidation.Present {
    val unconsumed = proposals.find(card.token)?.let { it.consumedAt == null && it.supersededAt == null } == true
    val (target, evidence) =
      if (unconsumed) {
        OperationCardStatus.REFUSED to NOT_ESTABLISHED
      } else {
        OperationCardStatus.CONSUMED_UNCERTAIN to NO_EVIDENCE
      }
    val settled = cards.settleUnrecordedConfirmation(card.token, target, evidence, clock.instant().toString())
    if (settled) {
      diagnostics.warning(
        "seam=operation-card.reconcile outcome=${target.wireValue}_unrecorded token=${card.token} holder=retained",
      )
      if (target == OperationCardStatus.REFUSED) withdraw(card.operationId, card.token)
    }
    val current = cards.find(card.token) ?: card
    return OperationCardRevalidation.Present(current, false, current.evidence ?: current.status.wireValue)
  }

  private fun liveness(owner: FeatureTaskRuntimeProcessIdentity): OwnerLiveness {
    val local = catchingNonCancellation { processes.currentProcess() }.getOrNull()
    val inspection =
      if (local != null && local.hostIdentity == owner.hostIdentity) {
        catchingNonCancellation { processes.inspect(owner) }.getOrNull()
      } else {
        null
      }
    return when {
      inspection == FeatureTaskRuntimeProcessInspection.ExactLive -> OwnerLiveness.LIVE
      inspection != null && inspection.isConfirmedDead() -> OwnerLiveness.DEAD
      else -> OwnerLiveness.UNKNOWN
    }
  }

  private fun holder(launch: OperationLaunchContext): BranchWorktreeHolder =
    BranchWorktreeHolder(BranchWorktreeHolderKind.OPERATION, launch.holderId)

  private enum class OwnerLiveness { LIVE, DEAD, UNKNOWN }

  private companion object {
    const val RUNNING = "The confirmation is still running."
    const val STARTING = "The confirmation is starting."
    const val UNVERIFIABLE =
      "The confirmation's process cannot be verified, so its checkout stays retained. Check again later."
    const val INTERRUPTED_AFTER_CONSUMPTION =
      "The confirmation ended after it consumed the proposal but before it recorded an outcome. " +
        "It will not run again; start a new proposal from chat."
    const val INTERRUPTED_BEFORE_CONSUMPTION =
      "The confirmation ended before it consumed the proposal; confirm again to retry."
    const val INTERRUPTED_AND_INELIGIBLE =
      "The confirmation ended before it consumed the proposal, and the original checkout or authority no longer " +
        "qualifies. Start a new proposal from chat."
    const val NOT_ESTABLISHED =
      "This confirmation predates execution records, so its execution was not established. The proposal was " +
        "withdrawn; start a new proposal from chat."
    const val NO_EVIDENCE =
      "The proposal was consumed, but this confirmation predates execution records, so its process and outcome " +
        "evidence is unavailable. It will not run again; start a new proposal from chat."
  }
}
