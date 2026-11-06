package skillbill.ports.approval

import skillbill.approval.model.ApprovalGrantFacts
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalRuleRecord
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalRegistration
import skillbill.ports.approval.model.ApprovalRequestIdentity
import skillbill.ports.approval.model.ApprovalStoredRequest

/** Owns durable approval admission, terminal claims, lifetime leases and repository-scoped rule state. */
interface ApprovalStore : ApprovalLeaseStore, ApprovalRuleStore {
  /** Registers a request with its grant facts or returns the existing record for the exact request identity. */
  fun register(
    request: ApprovalRequestRecord,
    facts: ApprovalGrantFacts,
  ): ApprovalRegistration

  /** Reads one record through its complete process and provider correlation identity. */
  fun find(identity: ApprovalRequestIdentity): ApprovalStoredRequest?

  /** Settles a pending record, optionally inserts its rule, and claims delivery in the caller's transaction. */
  fun settleAndClaim(
    identity: ApprovalRequestIdentity,
    settled: ApprovalRequestRecord,
    rule: ApprovalRuleRecord?,
    claimedAt: String,
  ): Boolean

  /** Settles a provider-cleared pending request without creating a response claim. */
  fun settleWithoutDelivery(
    identity: ApprovalRequestIdentity,
    settled: ApprovalRequestRecord,
  ): Boolean

  /** Records an observed provider denial without changing the immutable local decision. */
  fun recordProviderDisposition(
    identity: ApprovalRequestIdentity,
    disposition: ApprovalProviderDisposition,
  ): Boolean

  /** Records whether the already claimed provider response was written or became uncertain. */
  fun finishDelivery(
    identity: ApprovalRequestIdentity,
    state: ApprovalDeliveryState,
  ): Boolean

  /** Lists still pending requests of one repository, narrowed to one owning process generation when given. */
  fun pending(
    repositoryIdentity: String,
    owner: ApprovalOwnerKey?,
  ): List<ApprovalStoredRequest>

  /** The newest [limit] requests of one repository, pending or terminal, newest first by registration order. */
  fun recent(
    repositoryIdentity: String,
    limit: Int,
  ): List<ApprovalStoredRequest>

  /**
   * Settled-but-unwritten claims of exactly one owning process generation, oldest claim first. Only the process that
   * holds that generation's response capability may deliver them.
   */
  fun claimedDeliveries(owner: ApprovalOwnerKey): List<ApprovalStoredRequest>
}
