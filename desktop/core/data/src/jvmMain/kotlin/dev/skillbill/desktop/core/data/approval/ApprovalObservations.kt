package dev.skillbill.desktop.core.data.approval

import dev.skillbill.desktop.core.common.DatabaseDispatcher
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.data.di.DatabaseSessionScope
import dev.skillbill.desktop.core.data.session.SessionSelectionHolder
import dev.skillbill.desktop.core.data.signal.CommitSignalWatcher
import dev.skillbill.desktop.core.domain.model.ApprovalCardKey
import dev.skillbill.desktop.core.domain.model.ApprovalDecisionOutcome
import dev.skillbill.desktop.core.domain.model.ApprovalFeed
import dev.skillbill.desktop.core.domain.model.ApprovalRevokeOutcome
import dev.skillbill.desktop.core.domain.model.ApprovalRulesResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import skillbill.application.approval.ApprovalOperatorService
import skillbill.di.core.RuntimeComponent
import skillbill.ports.approval.model.ApprovalDecisionResult
import skillbill.ports.approval.model.ApprovalRequestIdentity
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.time.Clock

/**
 * The selected database session's operator surface for approvals. While at least one collector observes a repository,
 * the session holds that repository's operator consumer registration, which is what lets the runtime admit a worker's
 * permission request instead of refusing it. The last collector leaving, a database switch and the app closing all end
 * the registration, and ending it cancels the repository's outstanding requests. Card rendering and decision controls
 * belong to the surface collecting [observe]; this class owns the registration lifetime, the feed and the checked
 * decision seam.
 */
@Inject
@SingleIn(DatabaseSessionScope::class)
class ApprovalObservations(
  private val runtime: RuntimeComponent,
  private val holder: SessionSelectionHolder,
  private val signals: CommitSignalWatcher,
  private val dispatcher: DatabaseDispatcher,
  private val diagnostics: Diagnostics,
  private val clock: Clock,
) {
  private val service: ApprovalOperatorService get() = runtime.approvalOperations
  private val epoch = MutableStateFlow(0L)
  private val leases = ApprovalConsumerLeases(
    runtime.approvalOperations,
    holder.selection.scope,
    "desktop:${holder.selection.id}:${UUID.randomUUID()}",
    diagnostics,
  ) { epoch.update { it + 1 } }

  /**
   * The pending requests of the repository enclosing [repositoryRoot], re-read on database commits and registration
   * changes. Collecting makes the operator consumer live; [ApprovalFeed.Observing.live] is false whenever it is not.
   */
  fun observe(repositoryRoot: String, retainedKeys: Flow<Set<ApprovalCardKey>>): Flow<ApprovalFeed> = flow {
    val identity = try {
      onDatabase { service.repositoryIdentity(Path.of(repositoryRoot)) }
    } catch (failure: CancellationException) {
      throw failure
    } catch (_: Exception) {
      diagnostics.record("approval.repository_unresolved")
      emit(ApprovalFeed.Unavailable("The repository for this approval feed could not be resolved."))
      return@flow
    }
    // Terminal requests registered before this collector started are history, not this surface's cards.
    val observedFrom = Instant.ofEpochMilli(clock.now().toEpochMilliseconds())
    val retainedIdentities = linkedSetOf<ApprovalRequestIdentity>()
    val entry = onDatabase { leases.acquire(identity) }
    if (entry == null) {
      emit(ApprovalFeed.Unavailable(SESSION_CLOSED_REASON))
      return@flow
    }
    try {
      emitAll(
        merge(
          signals.snapshots.map { emptySet() },
          epoch.map { emptySet() },
          retainedKeys,
        ).map { keys ->
          keys.asSequence()
            .filter { it.repositoryIdentity == identity }
            .map(ApprovalCardKey::toIdentity)
            .forEach(retainedIdentities::add)
          read(entry, observedFrom, retainedIdentities)
        },
      )
    } finally {
      withContext(NonCancellable) { onDatabase { leases.release(entry) } }
    }
  }

  /** Decides one pending request with the session-held nonce; with no live registration nothing is written. */
  suspend fun decide(key: ApprovalCardKey, optionId: String, alwaysAllow: Boolean): ApprovalDecisionOutcome =
    leases.nonceFor(key.repositoryIdentity)?.let { nonce ->
      write { service.decide(key.toIdentity(), nonce, optionId, alwaysAllow) }
    } ?: ApprovalDecisionOutcome.NotLive

  suspend fun dismiss(key: ApprovalCardKey): ApprovalDecisionOutcome =
    if (leases.nonceFor(key.repositoryIdentity) == null) {
      ApprovalDecisionOutcome.NotLive
    } else {
      write { service.dismiss(key.toIdentity()) }
    }

  /** Session close: ends every operator registration, cancelling outstanding requests. Idempotent and non-suspending. */
  fun releaseAll() = leases.releaseAll()

  /** The repository's durable rules; reading selects nothing and grants nothing. */
  suspend fun rules(repositoryRoot: String): ApprovalRulesResult = try {
    val identity = onDatabase { service.repositoryIdentity(Path.of(repositoryRoot)) }
    ApprovalRulesResult.Listed(onDatabase { service.listRules(identity) }.map { it.toRule() })
  } catch (failure: CancellationException) {
    throw failure
  } catch (_: Exception) {
    diagnostics.record("approval.rules_read_failed")
    ApprovalRulesResult.Unavailable("The repository rules could not be read.")
  }

  /** Revokes one rule of the repository; later automatic grants stop, authorized actions are not undone. */
  suspend fun revokeRule(repositoryRoot: String, ruleId: String): ApprovalRevokeOutcome = try {
    val identity = onDatabase { service.repositoryIdentity(Path.of(repositoryRoot)) }
    if (onDatabase { service.revokeRule(ruleId, identity) }) {
      ApprovalRevokeOutcome.Revoked
    } else {
      ApprovalRevokeOutcome.NotFound("The rule was already revoked or no longer exists.")
    }
  } catch (failure: CancellationException) {
    throw failure
  } catch (_: Exception) {
    diagnostics.record("approval.rule_revoke_failed")
    ApprovalRevokeOutcome.Failed("The rule could not be revoked, so it is still active.")
  }

  private suspend fun read(
    entry: ApprovalConsumerLeases.Entry,
    observedFrom: Instant,
    retainedIdentities: MutableSet<ApprovalRequestIdentity>,
  ): ApprovalFeed = try {
    val cards = onDatabase { service.views(entry.repositoryIdentity, observedFrom, retainedIdentities.toSet()) }
    val mapped = cards.map { it.toCard() }
    mapped.asSequence().map { it.key.toIdentity() }.forEach(retainedIdentities::add)
    ApprovalFeed.Observing(leases.isLive(entry), mapped)
  } catch (failure: CancellationException) {
    throw failure
  } catch (_: Exception) {
    diagnostics.record("approval.pending_read_failed")
    ApprovalFeed.Unavailable("The pending approvals could not be read.")
  }

  private suspend fun write(action: () -> ApprovalDecisionResult): ApprovalDecisionOutcome = try {
    onDatabase(action).toOutcome()
  } catch (failure: CancellationException) {
    throw failure
  } catch (_: Exception) {
    diagnostics.record("approval.decision_failed")
    ApprovalDecisionOutcome.Refused("The approval decision could not be stored, so nothing was sent.")
  }

  private suspend fun <T> onDatabase(block: () -> T): T = withContext(dispatcher.dispatcher) { block() }

  private companion object {
    const val SESSION_CLOSED_REASON = "The database session is closing, so no approval card can be answered."
  }
}
