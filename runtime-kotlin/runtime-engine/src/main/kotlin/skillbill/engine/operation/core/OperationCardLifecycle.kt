package skillbill.engine.operation.core

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.operationcard.OperationCardProjection
import skillbill.contracts.operationcard.OperationCardStatus
import skillbill.contracts.operationcard.OperationLaunchContext
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.operation.OperationCardRepository
import skillbill.ports.operation.OperationProposalRepository
import skillbill.ports.operation.model.ConfirmationExecution
import skillbill.ports.operation.model.OperationProposal
import skillbill.ports.workflow.branchworktree.BranchWorktreeResources
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeRelease
import java.nio.file.Path
import java.time.Clock

/**
 * Publishes and settles the durable operator card of one operation run that an operator launch started.
 *
 * Every member is a no-op for an invocation without a launch id, so an operation run from a terminal keeps its
 * existing behavior.
 */
public interface OperationCardLifecycle {
  /**
   * Revalidates a card for display and confirmation. A confirming card is reconciled against the recorded owner of its
   * execution, so a restored desktop sees an interrupted confirmation settle without replaying it.
   */
  public fun revalidate(
    token: String,
    databaseIdentity: String,
  ): OperationCardRevalidation

  /** Resolves the launch context the request names, or the refusal that explains why it cannot be trusted. */
  public fun resolve(
    request: OperationRequest,
    checkoutRoot: Path,
  ): OperationLaunchResolution

  /** Moves an awaiting card to confirming and records the admitting process; null when the card changed. */
  public fun admit(
    token: String,
    selection: String?,
  ): ConfirmationExecution?

  /** Claims the admitted continuation for the calling invocation and process before the operation runs. */
  public fun claim(
    token: String,
    launchId: String,
    invocationId: String,
  ): Boolean

  /** Settles the admitted execution whose child process ended, or never started, without recording an outcome. */
  public fun childEnded(
    token: String,
    executionId: Long,
  )

  /** Publishes the card for [outcome] and returns the outcome the caller sees; a publish failure is a failure. */
  public fun publish(
    launch: OperationLaunchContext,
    invocationId: String,
    outcome: OperationOutcome.AwaitingConfirmation,
    authority: OperationAuthority,
  ): OperationOutcome

  /**
   * Records how a confirm invocation ended, or with a null [token] ends a launch that published no card, and releases
   * the launch holder once no card still awaits. Returns that release, or null when nothing was released.
   */
  public fun settle(
    launch: OperationLaunchContext?,
    token: String?,
    invocationId: String,
    outcome: OperationOutcome,
  ): BranchWorktreeRelease?

  /** Closes the parked operation state behind an invalidated or dismissed card [token] of [operationId]. */
  public fun withdraw(
    operationId: String,
    token: String,
  )

  /**
   * Cancels the awaiting card [token] and supersedes its authority in one step, so its token cannot be reused. Returns
   * false when the card is no longer awaiting confirmation.
   */
  public fun dismiss(token: String): Boolean
}

public sealed interface OperationLaunchResolution {
  public data object Unbound : OperationLaunchResolution

  public data class Bound(val launch: OperationLaunchContext) : OperationLaunchResolution

  public data class Refused(val outcome: OperationOutcome) : OperationLaunchResolution
}

public sealed interface OperationCardRevalidation {
  public data object Missing : OperationCardRevalidation

  public data class Present(
    public val card: OperationCardProjection,
    public val canConfirm: Boolean,
    public val reason: String? = null,
  ) : OperationCardRevalidation
}

public sealed interface OperationAuthority {
  public data object Stored : OperationAuthority

  public data class Bind(val operationId: String, val context: OperationContext) : OperationAuthority
}

public object NoOperationCardLifecycle : OperationCardLifecycle {
  override fun revalidate(
    token: String,
    databaseIdentity: String,
  ): OperationCardRevalidation = OperationCardRevalidation.Missing

  override fun resolve(
    request: OperationRequest,
    checkoutRoot: Path,
  ): OperationLaunchResolution = OperationLaunchResolution.Unbound

  override fun admit(
    token: String,
    selection: String?,
  ): ConfirmationExecution? = null

  override fun claim(
    token: String,
    launchId: String,
    invocationId: String,
  ): Boolean = false

  override fun childEnded(
    token: String,
    executionId: Long,
  ): Unit = Unit

  override fun publish(
    launch: OperationLaunchContext,
    invocationId: String,
    outcome: OperationOutcome.AwaitingConfirmation,
    authority: OperationAuthority,
  ): OperationOutcome = outcome

  override fun settle(
    launch: OperationLaunchContext?,
    token: String?,
    invocationId: String,
    outcome: OperationOutcome,
  ): BranchWorktreeRelease? = null

  override fun withdraw(
    operationId: String,
    token: String,
  ): Unit = Unit

  override fun dismiss(token: String): Boolean = false
}

@Inject
public class RepositoryOperationCardLifecycle(
  private val cards: OperationCardRepository,
  private val proposals: OperationProposalRepository,
  private val gate: OperationConfirmationGate,
  branchWorktrees: BranchWorktreeResources,
  registry: OperationRegistry,
  processes: MonitorProcessInspector,
  private val clock: Clock,
  diagnostics: RuntimeDiagnostics,
) : OperationCardLifecycle {
  private val eligibility = OperationCardEligibility(proposals, gate, branchWorktrees, registry)
  private val recovery =
    OperationConfirmationRecovery(
      cards,
      proposals,
      eligibility,
      branchWorktrees,
      registry,
      processes,
      diagnostics,
      clock,
    )

  override fun revalidate(
    token: String,
    databaseIdentity: String,
  ): OperationCardRevalidation {
    val card = cards.find(token) ?: return OperationCardRevalidation.Missing
    return when (card.status) {
      OperationCardStatus.AWAITING_CONFIRMATION -> {
        val reason = eligibility.refusalReason(card, cards.findLaunchContext(card.launchId), databaseIdentity)
        OperationCardRevalidation.Present(card, reason == null, reason)
      }
      OperationCardStatus.CONFIRMING ->
        recovery.reconcile(card).let { settled ->
          if (settled.card.status == OperationCardStatus.AWAITING_CONFIRMATION) {
            revalidate(token, databaseIdentity)
          } else {
            settled
          }
        }
      else -> {
        recovery.finishCleanup(card)
        OperationCardRevalidation.Present(card, false, card.status.wireValue)
      }
    }
  }

  override fun resolve(
    request: OperationRequest,
    checkoutRoot: Path,
  ): OperationLaunchResolution {
    val token = request.arguments.confirm
    val card = token?.let(cards::find)
    if (card != null) {
      val launchId = request.launchId
      if (launchId == null || launchId != card.launchId) {
        return refused("Operation confirmation '$token' requires its admitted desktop continuation.")
      }
      if (card.status != OperationCardStatus.CONFIRMING) {
        return refused("Operation confirmation '$token' has not been admitted by its operator card.")
      }
    }
    val launchId = request.launchId ?: return OperationLaunchResolution.Unbound
    val launch = cards.findLaunchContext(launchId)
    return when {
      launch == null -> refused("Operation launch '$launchId' is unknown.")
      launch.operationId != request.operationId ->
        refused("Operation launch '$launchId' belongs to another operation.")
      launch.checkoutRoot != checkoutRoot.toString() ->
        refused("Operation launch '$launchId' belongs to a different checkout.")
      launch.databaseIdentity != cards.databaseIdentity() ->
        refused("Operation launch '$launchId' belongs to a different database.")
      card != null && !card.matches(launch, request.arguments.select) ->
        refused("Operation confirmation '$token' does not match its admitted card facts.")
      else -> OperationLaunchResolution.Bound(launch)
    }
  }

  private fun OperationCardProjection.matches(
    launch: OperationLaunchContext,
    select: String?,
  ): Boolean =
    launchId == launch.launchId && operationId == launch.operationId &&
      repositoryIdentity == launch.repositoryIdentity && databaseIdentity == launch.databaseIdentity &&
      checkoutRoot == launch.checkoutRoot && selectedAgent == launch.selectedAgent &&
      selectedProfile == launch.selectedProfile && selection == select

  override fun admit(
    token: String,
    selection: String?,
  ): ConfirmationExecution? = cards.admitConfirmation(token, selection, recovery.currentProcess())

  override fun claim(
    token: String,
    launchId: String,
    invocationId: String,
  ): Boolean = cards.claimConfirmation(token, launchId, invocationId, recovery.currentProcess())

  override fun childEnded(
    token: String,
    executionId: Long,
  ): Unit = recovery.childEnded(token, executionId)

  override fun publish(
    launch: OperationLaunchContext,
    invocationId: String,
    outcome: OperationOutcome.AwaitingConfirmation,
    authority: OperationAuthority,
  ): OperationOutcome {
    val facts = outcome.card ?: return invalidate(launch, outcome, "The operation proposed no card details.")
    val stored =
      when (authority) {
        OperationAuthority.Stored -> null
        is OperationAuthority.Bind ->
          gate.bind(authority.operationId, authority.context, outcome.token)
            ?: return invalidate(launch, outcome, "The operation proposal could not be anchored.")
      }
    val superseded =
      try {
        cards.publish(projection(launch, invocationId, outcome, facts, stored), stored)
      } catch (error: SkillBillRuntimeException) {
        return invalidate(launch, outcome, "The operation card could not be stored: ${error.message}")
      }
    superseded.filter { it.launchId != launch.launchId }.forEach(recovery::releaseHolder)
    return outcome
  }

  override fun settle(
    launch: OperationLaunchContext?,
    token: String?,
    invocationId: String,
    outcome: OperationOutcome,
  ): BranchWorktreeRelease? {
    if (token == null) return launch?.let(recovery::releaseHolder)
    val card = cards.find(token)?.takeIf { it.status == OperationCardStatus.CONFIRMING }
    val active =
      cards.activeExecution(token)?.takeIf { execution ->
        execution.launchId == launch?.launchId && execution.invocationId == invocationId
      }
    if (card == null || active == null || launch == null) return null
    return settleActive(card, launch, active, outcome)
  }

  private fun settleActive(
    card: OperationCardProjection,
    launch: OperationLaunchContext,
    active: ConfirmationExecution,
    outcome: OperationOutcome,
  ): BranchWorktreeRelease? {
    val target = settledStatus(card, launch, outcome)
    val settled = cards.settleConfirmation(card.token, active.executionId, target, evidence(outcome), now())
    if (!settled || target == OperationCardStatus.AWAITING_CONFIRMATION) return null
    if (target == OperationCardStatus.REFUSED) recovery.withdraw(card.operationId, card.token)
    return recovery.releaseHolder(launch).also { cards.completeCleanup(active.executionId) }
  }

  private fun settledStatus(
    card: OperationCardProjection,
    launch: OperationLaunchContext,
    outcome: OperationOutcome,
  ): OperationCardStatus =
    when (outcome) {
      is OperationOutcome.Completed -> OperationCardStatus.COMPLETED
      is OperationOutcome.Failed -> OperationCardStatus.FAILED
      is OperationOutcome.AwaitingConfirmation -> OperationCardStatus.CONSUMED_UNCERTAIN
      is OperationRefusal ->
        if (eligibility.refusalReason(card, launch, cards.databaseIdentity()) == null) {
          OperationCardStatus.AWAITING_CONFIRMATION
        } else {
          OperationCardStatus.REFUSED
        }
    }

  private fun evidence(outcome: OperationOutcome): String? =
    when (outcome) {
      is OperationOutcome.Completed -> outcome.text
      is OperationOutcome.Failed -> outcome.reason
      is OperationRefusal -> outcome.reason
      is OperationOutcome.AwaitingConfirmation -> outcome.proposalSummary
    }

  private fun projection(
    launch: OperationLaunchContext,
    invocationId: String,
    outcome: OperationOutcome.AwaitingConfirmation,
    facts: OperationCardFacts,
    bound: OperationProposal?,
  ): OperationCardProjection {
    val anchors =
      bound?.let { mapOf(HEAD_SHA_ANCHOR to it.anchors.headSha, BRANCH_ANCHOR to it.anchors.branch) }
        ?: facts.sourceAnchors
    return OperationCardProjection(
      launchId = launch.launchId,
      operationId = launch.operationId,
      invocationId = invocationId,
      repositoryIdentity = launch.repositoryIdentity,
      databaseIdentity = launch.databaseIdentity,
      checkoutRoot = launch.checkoutRoot,
      branch = anchors[BRANCH_ANCHOR] ?: launch.branch,
      sourceAnchors = anchors,
      originalArguments = launch.originalArguments,
      selectedAgent = launch.selectedAgent,
      selectedProfile = launch.selectedProfile,
      token = outcome.token,
      fullSummary = outcome.proposalSummary,
      proposalDetails = facts.details,
    )
  }

  override fun withdraw(
    operationId: String,
    token: String,
  ): Unit = recovery.withdraw(operationId, token)

  override fun dismiss(token: String): Boolean = cards.dismiss(token, now())

  private fun invalidate(
    launch: OperationLaunchContext,
    outcome: OperationOutcome.AwaitingConfirmation,
    reason: String,
  ): OperationOutcome {
    proposals.markConsumed(outcome.token, now())
    withdraw(launch.operationId, outcome.token)
    return OperationOutcome.Failed("$reason\n\n${outcome.proposalSummary}")
  }

  private fun now(): String = clock.instant().toString()

  private fun refused(reason: String): OperationLaunchResolution =
    OperationLaunchResolution.Refused(OperationOutcome.Blocked(reason))
}
