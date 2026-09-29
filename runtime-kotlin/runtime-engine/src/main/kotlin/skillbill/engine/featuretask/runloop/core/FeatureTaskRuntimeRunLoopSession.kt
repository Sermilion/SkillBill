package skillbill.engine.featuretask.runloop.core

import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.repair.task.FeatureTaskRuntimeOperatorBlockRetry

internal sealed class FeatureTaskRuntimeRunLoopTerminalOutcome {
  data class Blocked(
    val report: FeatureTaskRuntimeRunReport.Blocked,
  ) : FeatureTaskRuntimeRunLoopTerminalOutcome()

  data class Paused(
    val report: FeatureTaskRuntimeRunReport.Paused,
  ) : FeatureTaskRuntimeRunLoopTerminalOutcome()

  data class Decomposed(
    val report: FeatureTaskRuntimeRunReport.Decomposed,
  ) : FeatureTaskRuntimeRunLoopTerminalOutcome()
}

internal class FeatureTaskRuntimeRunLoopSession(
  override val operatorBlockRetry: FeatureTaskRuntimeOperatorBlockRetry?,
  initialPendingReentry: PendingReentry?,
) : FeatureTaskRuntimeRunLoopSessionObservations {
  private val phaseContentIdentitiesStorage = mutableMapOf<String, Map<String, String>>()
  private var resolvedBranchStorage: String? = null
  private var checkpointOwnershipDecidedStorage: Boolean = false
  private var terminalOutcome: FeatureTaskRuntimeRunLoopTerminalOutcome? = null
  private var operatorBlockRetryCompletedStorage: Boolean = false
  private var pendingReentryStorage: PendingReentry? = initialPendingReentry
  private var activeReentryStorage: PendingReentry? = initialPendingReentry
  private var recordRejectionSettlementPendingStorage: Boolean = false

  fun sessionSnapshot(): FeatureTaskRuntimeRunLoopSessionObservations =
    detachedSessionObservations(
      FeatureTaskRuntimeRunLoopSession(operatorBlockRetry, pendingReentry).also { captured ->
        captured.phaseContentIdentitiesStorage.putAll(
          phaseContentIdentitiesStorage.mapValues { (_, identities) -> identities.toMap() },
        )
        captured.resolvedBranchStorage = resolvedBranchStorage
        captured.checkpointOwnershipDecidedStorage = checkpointOwnershipDecidedStorage
        captured.terminalOutcome = terminalOutcome?.detached()
        captured.operatorBlockRetryCompletedStorage = operatorBlockRetryCompletedStorage
        captured.activeReentryStorage = activeReentryStorage
        captured.recordRejectionSettlementPendingStorage = recordRejectionSettlementPendingStorage
      },
    )

  internal fun recordPhaseContentIdentities(
    phaseId: String,
    identities: Map<String, String>,
  ) {
    phaseContentIdentitiesStorage[phaseId] = identities.toMap()
  }

  override fun phaseContentIdentitiesFor(phaseId: String): Map<String, String> =
    phaseContentIdentitiesStorage[phaseId].orEmpty().toMap()

  override val checkpointOwnershipDecided: Boolean
    get() = checkpointOwnershipDecidedStorage

  override val resolvedBranch: String?
    get() = resolvedBranchStorage

  override val operatorBlockRetryCompleted: Boolean
    get() = operatorBlockRetryCompletedStorage

  override val pendingReentry: PendingReentry?
    get() = pendingReentryStorage

  override val activeReentry: PendingReentry?
    get() = activeReentryStorage

  override val recordRejectionSettlementPending: Boolean
    get() = recordRejectionSettlementPendingStorage


  override val blocked: FeatureTaskRuntimeRunReport.Blocked?
    get() = (terminalOutcome as? FeatureTaskRuntimeRunLoopTerminalOutcome.Blocked)?.report?.detached()

  override val paused: FeatureTaskRuntimeRunReport.Paused?
    get() = (terminalOutcome as? FeatureTaskRuntimeRunLoopTerminalOutcome.Paused)?.report?.detached()

  override val decomposed: FeatureTaskRuntimeRunReport.Decomposed?
    get() = (terminalOutcome as? FeatureTaskRuntimeRunLoopTerminalOutcome.Decomposed)?.report?.detached()

  internal fun transitionToBlocked(report: FeatureTaskRuntimeRunReport.Blocked) {
    terminalOutcome = FeatureTaskRuntimeRunLoopTerminalOutcome.Blocked(report.detached())
  }

  internal fun transitionToPaused(report: FeatureTaskRuntimeRunReport.Paused) {
    terminalOutcome = FeatureTaskRuntimeRunLoopTerminalOutcome.Paused(report.detached())
  }

  internal fun transitionToDecomposed(report: FeatureTaskRuntimeRunReport.Decomposed) {
    terminalOutcome = FeatureTaskRuntimeRunLoopTerminalOutcome.Decomposed(report.detached())
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

private fun FeatureTaskRuntimeRunLoopTerminalOutcome.detached(): FeatureTaskRuntimeRunLoopTerminalOutcome =
  when (this) {
    is FeatureTaskRuntimeRunLoopTerminalOutcome.Blocked -> copy(report = report.detached())
    is FeatureTaskRuntimeRunLoopTerminalOutcome.Paused -> copy(report = report.detached())
    is FeatureTaskRuntimeRunLoopTerminalOutcome.Decomposed -> copy(report = report.detached())
  }

private fun FeatureTaskRuntimeRunReport.Blocked.detached() =
  copy(
    completedPhaseIds = completedPhaseIds.toList(),
    subtaskOutcome = subtaskOutcome?.let { it.copy(participatingAgentIds = it.participatingAgentIds.toList()) },
  )

private fun FeatureTaskRuntimeRunReport.Paused.detached() =
  copy(
    completedPhaseIds = completedPhaseIds.toList(),
    subtaskOutcome = subtaskOutcome?.let { it.copy(participatingAgentIds = it.participatingAgentIds.toList()) },
  )

private fun FeatureTaskRuntimeRunReport.Decomposed.detached() =
  copy(
    completedPhaseIds = completedPhaseIds.toList(),
    subtaskSpecPaths = subtaskSpecPaths.toList(),
  )
