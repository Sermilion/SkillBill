package skillbill.ports.approval

import skillbill.ports.approval.model.ApprovalConsumerLease
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalOwnerLease

/** Owns the bounded liveness leases of approval owners and of the operator surface that answers them. */
interface ApprovalLeaseStore {
  /** Inserts or updates the lease of one owning process generation. */
  fun saveOwner(lease: ApprovalOwnerLease)

  /** Reads the lease of one owning process generation. */
  fun owner(key: ApprovalOwnerKey): ApprovalOwnerLease?

  /** Inserts or updates the lease of the operator surface observing one repository. */
  fun saveConsumer(lease: ApprovalConsumerLease)

  /** Reads the lease of the operator surface observing one repository. */
  fun consumer(repositoryIdentity: String): ApprovalConsumerLease?
}
