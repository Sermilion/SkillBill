package skillbill.infrastructure.launcher.process.interaction

import skillbill.ports.approval.model.ApprovalPendingWait

/**
 * The live approval channel of exactly one spawned agent process generation. It owns the writable stdin of that
 * process until the process ends, observes the provider's stdout, and answers permission requests only through the
 * broker's decision for that generation.
 */
interface AgentRunApprovalChannel {
  /** Writes the provider's opening frames and keeps stdin open. False when the opening could not be written. */
  fun attach(process: Process): Boolean

  /** Offers one decoded stdout chunk. Never blocks on the broker. */
  fun observe(chunk: String)

  /** Process-local view of the generation's still pending approvals; null when none is pending. */
  fun pendingWait(): ApprovalPendingWait?

  /**
   * Per-tick check that the provider answered the opening control handshake in time; stops the owning process with an
   * unsupported diagnostic otherwise. It touches no deadline or clock of the run.
   */
  fun enforceOpening()

  /** Ends the generation: cancels its outstanding requests while stdin is still writable, then closes stdin. */
  fun finish()
}

/** Opens one broker-backed channel per spawn; a continuation relaunch opens a new generation. */
fun interface AgentRunApprovalChannelOpening {
  fun open(): AgentRunApprovalOpenResult
}

sealed interface AgentRunApprovalOpenResult {
  data class Opened(val channel: AgentRunApprovalChannel) : AgentRunApprovalOpenResult

  data class Refused(val reason: String) : AgentRunApprovalOpenResult
}
