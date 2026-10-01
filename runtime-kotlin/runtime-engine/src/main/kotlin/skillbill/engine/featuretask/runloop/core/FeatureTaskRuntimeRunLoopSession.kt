package skillbill.engine.featuretask.runloop.core

import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry

internal sealed class FeatureTaskRuntimeRunTerminalOutcome {
  data class Blocked(
    val report: FeatureTaskRuntimeRunReport.Blocked,
  ) : FeatureTaskRuntimeRunTerminalOutcome()

  data class Paused(
    val report: FeatureTaskRuntimeRunReport.Paused,
  ) : FeatureTaskRuntimeRunTerminalOutcome()

  data class Decomposed(
    val report: FeatureTaskRuntimeRunReport.Decomposed,
  ) : FeatureTaskRuntimeRunTerminalOutcome()
}

internal class FeatureTaskRuntimeRunLoopSession(
  override val operatorBlockRetry: FeatureTaskRuntimeOperatorBlockRetry?,
  initialPendingReentry: PendingReentry?,
) : FeatureTaskRuntimeRunSessionObservations {
  private val phaseContentIdentitiesStorage = mutableMapOf<String, Map<String, String>>()
  private var resolvedBranchStorage: String? = null
  private var checkpointOwnershipDecidedStorage: Boolean = false
  private var terminalOutcome: FeatureTaskRuntimeRunTerminalOutcome? = null
  private var operatorBlockRetryCompletedStorage: Boolean = false
  private var pendingReentryStorage: PendingReentry? = initialPendingReentry
  private var activeReentryStorage: PendingReentry? = initialPendingReentry
  private var recordRejectionSettlementPendingStorage: Boolean = false

  fun sessionSnapshot(): FeatureTaskRuntimeRunSessionObservations =
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
    get() = (terminalOutcome as? FeatureTaskRuntimeRunTerminalOutcome.Blocked)?.report?.detached()

  override val paused: FeatureTaskRuntimeRunReport.Paused?
    get() = (terminalOutcome as? FeatureTaskRuntimeRunTerminalOutcome.Paused)?.report?.detached()

  override val decomposed: FeatureTaskRuntimeRunReport.Decomposed?
    get() = (terminalOutcome as? FeatureTaskRuntimeRunTerminalOutcome.Decomposed)?.report?.detached()

  internal fun transitionToBlocked(report: FeatureTaskRuntimeRunReport.Blocked) {
    terminalOutcome = FeatureTaskRuntimeRunTerminalOutcome.Blocked(report.detached())
  }

  internal fun transitionToPaused(report: FeatureTaskRuntimeRunReport.Paused) {
    terminalOutcome = FeatureTaskRuntimeRunTerminalOutcome.Paused(report.detached())
  }

  internal fun transitionToDecomposed(report: FeatureTaskRuntimeRunReport.Decomposed) {
    terminalOutcome = FeatureTaskRuntimeRunTerminalOutcome.Decomposed(report.detached())
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

private fun FeatureTaskRuntimeRunTerminalOutcome.detached(): FeatureTaskRuntimeRunTerminalOutcome =
  when (this) {
    is FeatureTaskRuntimeRunTerminalOutcome.Blocked -> copy(report = report.detached())
    is FeatureTaskRuntimeRunTerminalOutcome.Paused -> copy(report = report.detached())
    is FeatureTaskRuntimeRunTerminalOutcome.Decomposed -> copy(report = report.detached())
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
