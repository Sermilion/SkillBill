package skillbill.engine.featuretask.runloop.core

import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.repair.task.FeatureTaskRuntimeOperatorBlockRetry

internal sealed class FeatureTaskRuntimeRunLoopTerminalOutcome {
  data class Blocked(val report: FeatureTaskRuntimeRunReport.Blocked) : FeatureTaskRuntimeRunLoopTerminalOutcome()

  data class Paused(val report: FeatureTaskRuntimeRunReport.Paused) : FeatureTaskRuntimeRunLoopTerminalOutcome()

  data class Decomposed(val report: FeatureTaskRuntimeRunReport.Decomposed) : FeatureTaskRuntimeRunLoopTerminalOutcome()
}

internal class FeatureTaskRuntimeRunLoopSession(
  internal val operatorBlockRetry: FeatureTaskRuntimeOperatorBlockRetry?,
  initialPendingReentry: PendingReentry?,
) {
  private val phaseContentIdentitiesStorage = mutableMapOf<String, Map<String, String>>()
  private var resolvedBranchStorage: String? = null
  private var checkpointOwnershipDecidedStorage: Boolean = false
  private var terminalOutcome: FeatureTaskRuntimeRunLoopTerminalOutcome? = null
  private var operatorBlockRetryCompletedStorage: Boolean = false
  private var pendingReentryStorage: PendingReentry? = initialPendingReentry
  private var activeReentryStorage: PendingReentry? = initialPendingReentry
  private var recordRejectionSettlementPendingStorage: Boolean = false

  internal fun recordPhaseContentIdentities(
    phaseId: String,
    identities: Map<String, String>,
  ) {
    phaseContentIdentitiesStorage[phaseId] = identities.toMap()
  }

  internal fun phaseContentIdentitiesFor(phaseId: String): Map<String, String> =
    phaseContentIdentitiesStorage[phaseId].orEmpty().toMap()

  internal val checkpointOwnershipDecided: Boolean
    get() = checkpointOwnershipDecidedStorage

  internal val resolvedBranch: String?
    get() = resolvedBranchStorage

  internal val operatorBlockRetryCompleted: Boolean
    get() = operatorBlockRetryCompletedStorage

  internal val pendingReentry: PendingReentry?
    get() = pendingReentryStorage

  internal val activeReentry: PendingReentry?
    get() = activeReentryStorage

  internal val recordRejectionSettlementPending: Boolean
    get() = recordRejectionSettlementPendingStorage

  internal val blocked: FeatureTaskRuntimeRunReport.Blocked?
    get() = (terminalOutcome as? FeatureTaskRuntimeRunLoopTerminalOutcome.Blocked)?.report

  internal val paused: FeatureTaskRuntimeRunReport.Paused?
    get() = (terminalOutcome as? FeatureTaskRuntimeRunLoopTerminalOutcome.Paused)?.report

  internal val decomposed: FeatureTaskRuntimeRunReport.Decomposed?
    get() = (terminalOutcome as? FeatureTaskRuntimeRunLoopTerminalOutcome.Decomposed)?.report

  internal fun transitionToBlocked(report: FeatureTaskRuntimeRunReport.Blocked) {
    terminalOutcome = FeatureTaskRuntimeRunLoopTerminalOutcome.Blocked(report)
  }

  internal fun transitionToPaused(report: FeatureTaskRuntimeRunReport.Paused) {
    terminalOutcome = FeatureTaskRuntimeRunLoopTerminalOutcome.Paused(report)
  }

  internal fun transitionToDecomposed(report: FeatureTaskRuntimeRunReport.Decomposed) {
    terminalOutcome = FeatureTaskRuntimeRunLoopTerminalOutcome.Decomposed(report)
  }

  internal fun clearTerminalOutcome() {
    terminalOutcome = null
  }

  internal fun transitionResolvedBranch(branch: String?) {
    resolvedBranchStorage = branch
  }

  internal fun markCheckpointOwnershipDecided() {
    checkpointOwnershipDecidedStorage = true
  }

  internal fun transitionPendingReentry(reentry: PendingReentry?) {
    pendingReentryStorage = reentry
  }

  internal fun transitionActiveReentry(reentry: PendingReentry?) {
    activeReentryStorage = reentry
  }

  internal fun transitionReentryPair(
    pending: PendingReentry?,
    active: PendingReentry?,
  ) {
    pendingReentryStorage = pending
    activeReentryStorage = active
  }

  internal fun consumeOperatorBlockRetryCompletion(phaseId: String) {
    if (operatorBlockRetry?.phaseId == phaseId) {
      operatorBlockRetryCompletedStorage = true
    }
  }

  internal fun markRecordRejectionSettlementPending() {
    recordRejectionSettlementPendingStorage = true
  }

  internal fun clearRecordRejectionSettlementPending() {
    recordRejectionSettlementPendingStorage = false
  }
}
