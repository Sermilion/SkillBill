package skillbill.infrastructure.launcher.process

import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalState
import skillbill.ports.approval.model.ApprovalDecisionResult
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalPendingWait
import skillbill.ports.approval.model.ApprovalProviderClearance
import skillbill.ports.approval.model.ApprovalRegistrationCommand
import skillbill.ports.approval.model.ApprovalRegistrationResult

internal class ApprovalTestOwner : ApprovalOwnerSession {
  override val key = ApprovalOwnerKey("repo", "run", "generation")
  val requests = mutableListOf<ApprovalRegistrationCommand>()
  var failRegistration = false
  var onResolve: (ApprovalRequestRecord) -> Unit = {}

  override fun register(command: ApprovalRegistrationCommand): ApprovalRegistrationResult {
    check(!failRegistration) { "registration unavailable" }
    val request =
      command.request.copy(
        actionFingerprint = command.canonicalInput,
        authorityScope = command.canonicalInput?.let { command.request.authorityScope?.copy(actionFingerprint = it) },
      )
    requests += command.copy(request = request)
    return ApprovalRegistrationResult.Registered(request)
  }

  override fun resolveByProvider(
    approvalId: String,
    providerRequestId: ApprovalProviderRequestId,
    disposition: ApprovalProviderDisposition,
  ): ApprovalDecisionResult {
    val settled =
      requests.single { it.request.approvalId == approvalId }.request.copy(state = ApprovalState.UNSUPPORTED)
    onResolve(settled)
    return ApprovalDecisionResult.Settled(settled)
  }

  override fun clearByProvider(
    approvalId: String,
    providerRequestId: ApprovalProviderRequestId,
    clearance: ApprovalProviderClearance,
  ): ApprovalDecisionResult = ApprovalDecisionResult.Missing

  override fun pendingWait(): ApprovalPendingWait? = null

  override fun release() = Unit
}
