package skillbill.engine.work.control

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.operationcard.OperationCardOperationIds
import skillbill.engine.featuretask.phaserun.toStatusCleanup
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusPolicy
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.idestatus.model.StandaloneMonitorCleanup
import skillbill.ports.idestatus.model.StandalonePhaseFinalizeRequest
import skillbill.ports.idestatus.model.StandalonePhaseFinalizeResult
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.monitor.StandaloneMonitorResources
import skillbill.ports.monitor.model.MonitorRetentionReason
import skillbill.ports.monitor.model.MonitorSettlement
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.branchworktree.BranchWorktreeResources
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolder
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolderKind
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeRelease
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Path
import java.time.Instant

@Inject
public class WorkRunFinalizer(
  private val branchWorktrees: BranchWorktreeResources,
  private val monitorResources: StandaloneMonitorResources,
) {
  internal fun canFinalize(
    item: WorkDetailItem,
    phase: StandalonePhaseStatusRecord?,
    unit: UnitOfWork,
  ): Boolean {
    val snapshot = item.snapshot
    if (
      snapshot.workflowFamily == IdeStatusWorkflowFamily.FEATURE_GOAL ||
      snapshot.lifecycleState != IdeStatusLifecycleState.TERMINAL
    ) {
      return false
    }
    if (operatorFinalizedAt(item, phase, unit) != null || snapshot.pauseReason != null) {
      return false
    }
    return when (snapshot.workflowFamily) {
      IdeStatusWorkflowFamily.FEATURE_VERIFY -> verifyFinalizeEligible(unit, snapshot.workflowId)
      else ->
        phase != null &&
          phase.launchScope == IdeStatusLaunchScope.STANDALONE &&
          IdeStatusPolicy.standaloneLifecycle(phase.lifecycleState) == IdeStatusLifecycleState.TERMINAL &&
          phase.finishedAt != null
    }
  }

  internal fun writeAndRelease(
    item: WorkDetailItem,
    phase: StandalonePhaseStatusRecord?,
    unit: UnitOfWork,
    at: Instant,
  ): StandalonePhaseFinalizeResult =
    when (item.snapshot.workflowFamily) {
      IdeStatusWorkflowFamily.FEATURE_VERIFY -> finalizeFeatureVerify(item, unit, at)
      else -> finalizeStandalone(item, phase, unit, at)
    }

  private fun finalizeFeatureVerify(
    item: WorkDetailItem,
    unit: UnitOfWork,
    at: Instant,
  ): StandalonePhaseFinalizeResult {
    val workflowId = item.snapshot.workflowId ?: return StandalonePhaseFinalizeResult.INELIGIBLE
    val card = unit.operationCards.find(workflowId)
    val launch = card?.let { unit.operationCards.findLaunchContext(it.launchId) }
    val kept =
      launch?.let {
        branchWorktrees.release(
          Path.of(item.repositoryRoot),
          BranchWorktreeHolder(BranchWorktreeHolderKind.OPERATION, it.holderId),
        ) as? BranchWorktreeRelease.Kept
      }
    return if (
      unit.workflowStates.finalizeOperatorCompletion(
        WorkflowFamily.VERIFY,
        workflowId,
        at,
        kept?.path?.toString(),
        kept?.detail,
      )
    ) {
      StandalonePhaseFinalizeResult.ACCEPTED
    } else {
      StandalonePhaseFinalizeResult.INELIGIBLE
    }
  }

  private fun finalizeStandalone(
    item: WorkDetailItem,
    phase: StandalonePhaseStatusRecord?,
    unit: UnitOfWork,
    at: Instant,
  ): StandalonePhaseFinalizeResult {
    val status = phase ?: return StandalonePhaseFinalizeResult.MISSING
    val kept =
      branchWorktrees.release(Path.of(item.repositoryRoot), branchWorktreeHolder(status, unit))
        as? BranchWorktreeRelease.Kept
    return unit.standalonePhaseStatuses.finalize(
      StandalonePhaseFinalizeRequest(
        executionId = status.executionId,
        operatorFinalizedAt = at,
        branchWorktreeRetainedPath = kept?.path?.toString(),
        branchWorktreeRetainedDetail = kept?.detail,
        monitorCleanup = finalizeDeferredMonitor(status),
        terminalResultAppendix = kept?.let { "Kept worktree ${it.path}: ${it.detail}." },
      ),
    )
  }

  private fun operatorFinalizedAt(
    item: WorkDetailItem,
    phase: StandalonePhaseStatusRecord?,
    unit: UnitOfWork,
  ): Instant? {
    if (item.snapshot.workflowFamily == IdeStatusWorkflowFamily.FEATURE_VERIFY) {
      val workflowId = item.snapshot.workflowId ?: return null
      return unit.workflowStates.get(WorkflowFamily.VERIFY, workflowId)?.operatorFinalizedAt
    }
    return phase?.operatorFinalizedAt
  }

  private fun verifyFinalizeEligible(
    unit: UnitOfWork,
    workflowId: String?,
  ): Boolean {
    val id = workflowId ?: return false
    val record = unit.workflowStates.get(WorkflowFamily.VERIFY, id)?.toRecord() ?: return false
    return WorkflowStatus.fromWire(record.workflowStatus) == WorkflowStatus.COMPLETED &&
      record.finishedAt != null &&
      record.operatorFinalizedAt == null
  }

  private fun branchWorktreeHolder(
    phase: StandalonePhaseStatusRecord,
    unit: UnitOfWork,
  ): BranchWorktreeHolder {
    val operationLaunch = unit.operationCards.findLaunchContextByInvocation(phase.invocationId)
    if (operationLaunch != null) {
      return BranchWorktreeHolder(BranchWorktreeHolderKind.OPERATION, operationLaunch.holderId)
    }
    if (phase.launchArguments?.firstOrNull() == "operation" || phase.phaseId in OperationCardOperationIds.ALL) {
      return BranchWorktreeHolder(BranchWorktreeHolderKind.OPERATION, phase.phaseId)
    }
    return BranchWorktreeHolder(BranchWorktreeHolderKind.PHASE, phase.phaseId)
  }

  private fun finalizeDeferredMonitor(phase: StandalonePhaseStatusRecord): StandaloneMonitorCleanup? {
    val awaitingFinalize =
      (phase.monitorCleanup as? StandaloneMonitorCleanup.Retained)?.reason ==
        MonitorRetentionReason.AWAITING_FINALIZE
    val admission = phase.monitorTarget?.worktreePath?.let(Path::of)?.parent
    if (!awaitingFinalize && admission == null) return null
    val directory = admission ?: return null
    val settlement = monitorResources.finalizeDeferredRemoval(directory)
    if (!awaitingFinalize && settlement is MonitorSettlement.FenceLost) return null
    return settlement.toStatusCleanup()
  }
}
