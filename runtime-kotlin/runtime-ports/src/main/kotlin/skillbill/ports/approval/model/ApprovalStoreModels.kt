package skillbill.ports.approval.model

import skillbill.approval.model.ApprovalExecutionMode
import skillbill.approval.model.ApprovalGrantFacts
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.contracts.approval.ApprovalRequestRecord

enum class ApprovalDeliveryState(val databaseValue: String) {
  WRITTEN("written"),
  UNCERTAIN("uncertain"),
  NOT_REQUIRED("not_required"),
}

sealed interface ApprovalRegistration {
  data object Created : ApprovalRegistration

  data class Existing(val stored: ApprovalStoredRequest) : ApprovalRegistration

  data object IdentityConflict : ApprovalRegistration
}

data class ApprovalStoredRequest(
  val record: ApprovalRequestRecord,
  val facts: ApprovalGrantFacts,
  val delivery: ApprovalDeliveryProgress? = null,
  val rulePattern: String? = null,
)

enum class ApprovalDeliveryProgress { CLAIMED, WRITTEN, UNCERTAIN, NOT_REQUIRED }

data class ApprovalRequestIdentity(
  val approvalId: String,
  val repositoryIdentity: String,
  val runId: String,
  val processGeneration: String,
  val provider: String,
  val providerRequestId: ApprovalProviderRequestId,
)

data class ApprovalOwnerKey(
  val repositoryIdentity: String,
  val runId: String,
  val processGeneration: String,
)

data class ApprovalOwnerLease(
  val key: ApprovalOwnerKey,
  val nonce: String,
  val executionMode: ApprovalExecutionMode,
  val enclosingDeadline: String?,
  val createdAt: String,
  val expiresAt: String?,
  val endedAt: String?,
)

data class ApprovalConsumerLease(
  val repositoryIdentity: String,
  val consumerId: String,
  val nonce: String,
  val createdAt: String,
  val expiresAt: String,
  val endedAt: String?,
)
