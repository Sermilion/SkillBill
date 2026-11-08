package skillbill.application.approval

import me.tatarka.inject.annotations.Inject
import skillbill.application.approval.model.ApprovalOperatorView
import skillbill.application.runtime.RuntimeSingleton
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalRuleRecord
import skillbill.contracts.approval.ApprovalState
import skillbill.ports.approval.model.ApprovalConsumerResult
import skillbill.ports.approval.model.ApprovalDecisionResult
import skillbill.ports.approval.model.ApprovalRequestIdentity
import skillbill.ports.approval.model.ApprovalStoredRequest
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.repository.RepositoryEnclosingRootPort
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

@RuntimeSingleton
@Inject
class ApprovalOperatorService(
  private val broker: ApprovalBroker,
  private val database: DatabaseSessionFactory,
  private val repositories: RepositoryEnclosingRootPort,
  private val clock: Clock,
) {
  fun repositoryIdentity(checkoutRoot: Path): String = repositories.primaryRepositoryIdentity(checkoutRoot)

  fun pending(repositoryIdentity: String): List<ApprovalRequestRecord> =
    database.read { unit -> unit.approvals.pending(repositoryIdentity, null) }.map { stored -> stored.record }

  fun views(
    repositoryIdentity: String,
    observedFrom: Instant,
    retainedIdentities: Set<ApprovalRequestIdentity> = emptySet(),
  ): List<ApprovalOperatorView> {
    val now = clock.instant()
    return database.read { unit ->
      val store = unit.approvals
      val byIdentity = LinkedHashMap<ApprovalRequestIdentity, ApprovalStoredRequest>()
      store.recent(repositoryIdentity, RECENT_LIMIT).asReversed().forEach { stored ->
        val created = parseInstant(stored.record.createdAt)
        if (stored.record.state == ApprovalState.PENDING || created == null || !created.isBefore(observedFrom)) {
          byIdentity[stored.record.identity()] = stored
        }
      }
      store.pending(repositoryIdentity, null).forEach { stored ->
        byIdentity.putIfAbsent(stored.record.identity(), stored)
      }
      retainedIdentities.asSequence()
        .filter { it.repositoryIdentity == repositoryIdentity }
        .forEach { identity -> store.find(identity)?.let { byIdentity[identity] = it } }
      byIdentity.values.map { stored -> stored.toOperatorView(store, now) }
    }
  }

  fun view(identity: ApprovalRequestIdentity): ApprovalOperatorView? {
    val now = clock.instant()
    return database.read { unit -> unit.approvals.find(identity)?.toOperatorView(unit.approvals, now) }
  }

  fun decide(
    identity: ApprovalRequestIdentity,
    consumerNonce: String,
    selectedOptionId: String,
    alwaysAllow: Boolean,
  ): ApprovalDecisionResult = broker.decide(identity, consumerNonce, selectedOptionId, alwaysAllow)

  fun dismiss(identity: ApprovalRequestIdentity): ApprovalDecisionResult = broker.dismiss(identity)

  fun listRules(repositoryIdentity: String): List<ApprovalRuleRecord> = broker.listRules(repositoryIdentity)

  fun revokeRule(
    ruleId: String,
    repositoryIdentity: String,
  ): Boolean = broker.revokeRule(ruleId, repositoryIdentity)

  fun registerConsumer(
    repositoryIdentity: String,
    consumerId: String,
  ): ApprovalConsumerResult.Registered = broker.registerConsumer(repositoryIdentity, consumerId)

  fun renewConsumer(
    repositoryIdentity: String,
    nonce: String,
  ): ApprovalConsumerResult = broker.renewConsumer(repositoryIdentity, nonce)

  fun releaseConsumer(
    repositoryIdentity: String,
    nonce: String,
  ): Boolean = broker.releaseConsumer(repositoryIdentity, nonce)

  private companion object {
    const val RECENT_LIMIT = 200
  }
}
