package skillbill.ports.approval.model

import skillbill.approval.model.ApprovalActionClass
import skillbill.approval.model.ApprovalExecutionMode
import skillbill.approval.model.ApprovalGrantRefusal
import skillbill.approval.model.ApprovalMatchFacts
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.contracts.approval.ApprovalRequestRecord
import java.time.Instant

data class ApprovalRegistrationCommand(
  val request: ApprovalRequestRecord,
  val canonicalInput: String?,
  val actionClass: ApprovalActionClass,
  val writeCapable: Boolean,
  val providerDisposition: ApprovalProviderDisposition,
  val enclosingDeadline: Instant?,
  val matchFacts: ApprovalMatchFacts? = null,
)

data class ApprovalOwnerRegistration(
  val key: ApprovalOwnerKey,
  val executionMode: ApprovalExecutionMode,
  val enclosingDeadline: Instant?,
)

sealed interface ApprovalOwnerResult {
  data class Registered(val lease: ApprovalOwnerLease) : ApprovalOwnerResult

  /** A generation is registered once; an existing lease is never revived or replaced. */
  data object AlreadyRegistered : ApprovalOwnerResult
}

sealed interface ApprovalRegistrationResult {
  data class Registered(val request: ApprovalRequestRecord) : ApprovalRegistrationResult

  /** An exact stored rule allowed the request; the one-time response was claimed for the live owner. */
  data class AutoAllowed(val request: ApprovalRequestRecord) : ApprovalRegistrationResult

  data class Refused(val request: ApprovalRequestRecord) : ApprovalRegistrationResult

  data object IdentityConflict : ApprovalRegistrationResult
}

sealed interface ApprovalDecisionResult {
  data class Settled(val request: ApprovalRequestRecord) : ApprovalDecisionResult

  data class AlreadySettled(val request: ApprovalRequestRecord) : ApprovalDecisionResult

  data class OptionUnavailable(val request: ApprovalRequestRecord) : ApprovalDecisionResult

  data class GrantRefused(val request: ApprovalRequestRecord, val refusal: ApprovalGrantRefusal) :
    ApprovalDecisionResult

  /** No verified live owner generation or operator consumer holds authority for this request. */
  data class NotLive(val request: ApprovalRequestRecord) : ApprovalDecisionResult

  data class InvalidRecord(val request: ApprovalRequestRecord) : ApprovalDecisionResult

  data class DeadlineNotReached(val request: ApprovalRequestRecord) : ApprovalDecisionResult

  data object Missing : ApprovalDecisionResult
}

sealed interface ApprovalConsumerResult {
  data class Registered(val lease: ApprovalConsumerLease) : ApprovalConsumerResult

  /** The lease is not the live one for its repository; a newer registration superseded it or it already ended. */
  data object Stale : ApprovalConsumerResult
}
