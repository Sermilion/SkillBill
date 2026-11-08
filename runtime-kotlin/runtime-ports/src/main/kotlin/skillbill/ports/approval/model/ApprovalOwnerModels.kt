package skillbill.ports.approval.model

import skillbill.approval.model.ApprovalExecutionMode
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.contracts.approval.ApprovalProviderRequestId
import java.nio.file.Path
import java.time.Instant

data class ApprovalOwnerOpenRequest(
  val checkoutRoot: Path,
  val runId: String,
  val provider: String,
  val executionMode: ApprovalExecutionMode,
  val profileIdentity: String,
  val enclosingDeadline: Instant?,
)

sealed interface ApprovalOwnerOpenResult {
  data class Opened(val session: ApprovalOwnerSession) : ApprovalOwnerOpenResult

  data class Refused(val reason: String) : ApprovalOwnerOpenResult
}

enum class ApprovalProviderClearance { RESOLVED, DENIED }

/** One live owner generation. Everything it registers is keyed by [key] and ends with [release]. */
interface ApprovalOwnerSession {
  val key: ApprovalOwnerKey

  /** Registers one provider permission request under this generation. */
  fun register(command: ApprovalRegistrationCommand): ApprovalRegistrationResult

  /** The provider itself resolved a still pending request (denial or required interaction). */
  fun resolveByProvider(
    approvalId: String,
    providerRequestId: ApprovalProviderRequestId,
    disposition: ApprovalProviderDisposition,
  ): ApprovalDecisionResult

  /** Clears one exact live request after the provider reports it no longer needs a response. */
  fun clearByProvider(
    approvalId: String,
    providerRequestId: ApprovalProviderRequestId,
    clearance: ApprovalProviderClearance,
  ): ApprovalDecisionResult

  /**
   * Process-local view of this generation's still pending requests, without reading the database. Null when none is
   * pending. A request leaves the view when its response is delivered or the generation is released.
   */
  fun pendingWait(): ApprovalPendingWait?

  /** Normal closure: cancels every outstanding request of this generation and detaches the response capability. */
  fun release()
}

data class ApprovalPendingWait(
  val count: Int,
  val earliestDeadline: Instant,
  val latestTitle: String,
)
