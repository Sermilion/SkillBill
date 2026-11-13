package skillbill.engine.featuretask.runloop.finalization

import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.engine.featuretask.model.phase.ValidationCommandLaunchTransport
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorLedger
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorRunRecords
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.finalization.standalone.FeatureTaskRuntimeRunLoopStandaloneMonitorCycle
import skillbill.engine.featuretask.runloop.finalization.standalone.StandaloneRepairCommit
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopLaunchingStepBinding
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindingCoordinator
import skillbill.engine.featuretask.slot.MonitorRepairCapability
import skillbill.engine.featuretask.slot.PhaseBoundStepHooks
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchCollaborationScope
import skillbill.engine.featuretask.slot.state.PhaseCiObservation
import skillbill.engine.featuretask.slot.state.PhaseMonitorStepBinding
import skillbill.engine.featuretask.slot.state.PhaseStandaloneMonitorOperations
import skillbill.engine.featuretask.slot.state.PhaseStandaloneMonitorStepBinding
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.idestatus.model.MonitorRepairValidationSnapshotKey

internal class FeatureTaskRuntimeRunLoopMonitorStepBinding(
  environment: PhaseAttemptLaunchCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int?,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator,
  hooks: PhaseStepHooks<PhaseMonitorStepBinding>,
) : FeatureTaskRuntimeRunLoopLaunchingStepBinding(environment, run, fanOutUnitId, bindingCoordinator),
  PhaseMonitorStepBinding {
  override val boundHooks: PhaseBoundStepHooks<PhaseMonitorStepBinding> =
    PhaseBoundStepHooks<PhaseMonitorStepBinding>(hooks, this)

  private val repairRoles
    get() = requireNotNull((acceptedOwner as? MonitorRepairCapability)?.monitorRepairSteps)

  override fun acceptRepairValidationSnapshot(snapshot: ProjectCheckCommandSnapshot): RequiredPhaseWrite {
    requireAcceptedStep(run, acceptedOwner.strategyId)
    environment.validationCommandLaunch.accept(repairSnapshotIdentity(), snapshot)
    return RequiredPhaseWrite.Acknowledged
  }

  override fun repairValidationSnapshot(): ProjectCheckCommandSnapshot? {
    requireAcceptedStep(run, acceptedOwner.strategyId)
    return environment.validationCommandLaunch.load(repairSnapshotIdentity())
  }

  override fun repairValidationLaunchTransport(): ValidationCommandLaunchTransport? {
    requireAcceptedStep(run, acceptedOwner.strategyId)
    val attempt = environment.progress.phase(run.phaseId).record?.attemptCount ?: 1
    return environment.validationCommandLaunch.prepareMonitorRepair(run, repairSnapshotIdentity(), attempt).second
  }

  override fun prepareRepairValidationSnapshotForLaunch(
    run: PhaseRun,
  ): Pair<RequiredPhaseWrite, ValidationCommandLaunchTransport?> {
    requireAcceptedStep(run, acceptedOwner.strategyId)
    val attempt = environment.progress.phase(run.phaseId).record?.attemptCount ?: 1
    return environment.validationCommandLaunch.prepareMonitorRepair(run, repairSnapshotIdentity(), attempt)
  }

  private fun repairSnapshotIdentity(): MonitorRepairValidationSnapshotKey =
    MonitorRepairValidationSnapshotKey(
      run.request.workflowId,
      environment.validationCommandLaunch.goalRepairCycle(run),
      run.phaseId,
    )

  override fun publishRepair(
    run: PhaseRun,
    pullRequests: PullRequestIdentityLookup,
    conflictBaseBranch: String?,
  ): String? {
    requireAcceptedStep(run, acceptedOwner.strategyId)
    return with(FeatureTaskRuntimeRunLoopCommitCycle) {
      environment.publishMonitorRepair(run, repairRoles.publication, pullRequests, conflictBaseBranch)
    }
  }

  override fun runMonitor(
    run: PhaseRun,
    observation: PhaseCiObservation,
  ): PhaseOutcome {
    requireAcceptedStep(run, acceptedOwner.strategyId)
    return with(FeatureTaskRuntimeRunLoopMonitorCycle) {
      environment.runDeclaredMonitorCycle(run, observation, repairRoles)
    }
  }
}

internal class FeatureTaskRuntimeRunLoopStandaloneMonitorStepBinding(
  environment: PhaseAttemptLaunchCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int?,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator,
  hooks: PhaseStepHooks<PhaseStandaloneMonitorStepBinding>,
) : FeatureTaskRuntimeRunLoopLaunchingStepBinding(environment, run, fanOutUnitId, bindingCoordinator),
  PhaseStandaloneMonitorStepBinding {
  override val boundHooks: PhaseBoundStepHooks<PhaseStandaloneMonitorStepBinding> =
    PhaseBoundStepHooks<PhaseStandaloneMonitorStepBinding>(hooks, this)

  private val ledger: StandaloneMonitorLedger?
    get() = (environment.recorder as? StandaloneMonitorRunRecords)?.standaloneMonitorLedger

  override fun runStandaloneMonitor(
    run: PhaseRun,
    operations: PhaseStandaloneMonitorOperations,
  ): PhaseOutcome {
    requireAcceptedStep(run, acceptedOwner.strategyId)
    return with(FeatureTaskRuntimeRunLoopStandaloneMonitorCycle) { environment.runStandaloneMonitor(run, operations) }
  }

  override fun standaloneRepairBrief(): String {
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    return ledger?.pendingRepair?.evidence.orEmpty()
  }

  override fun acceptRepairValidationSnapshot(snapshot: ProjectCheckCommandSnapshot): RequiredPhaseWrite {
    requireAcceptedStep(run, acceptedOwner.strategyId)
    environment.validationCommandLaunch.accept(repairSnapshotIdentity(), snapshot)
    return RequiredPhaseWrite.Acknowledged
  }

  override fun repairValidationSnapshot(): ProjectCheckCommandSnapshot? {
    requireAcceptedStep(run, acceptedOwner.strategyId)
    return environment.validationCommandLaunch.load(repairSnapshotIdentity())
  }

  override fun repairValidationLaunchTransport(): ValidationCommandLaunchTransport? {
    requireAcceptedStep(run, acceptedOwner.strategyId)
    val attempt = environment.progress.phase(run.phaseId).record?.attemptCount ?: 1
    return environment.validationCommandLaunch.prepareMonitorRepair(run, repairSnapshotIdentity(), attempt).second
  }

  override fun prepareRepairValidationSnapshotForLaunch(
    run: PhaseRun,
  ): Pair<RequiredPhaseWrite, ValidationCommandLaunchTransport?> {
    requireAcceptedStep(run, acceptedOwner.strategyId)
    val attempt = environment.progress.phase(run.phaseId).record?.attemptCount ?: 1
    return environment.validationCommandLaunch.prepareMonitorRepair(run, repairSnapshotIdentity(), attempt)
  }

  private fun repairSnapshotIdentity(): MonitorRepairValidationSnapshotKey {
    val cycle = environment.validationCommandLaunch.standaloneRepairCycle(run, ledger?.publishedRepairs?.size)
    return MonitorRepairValidationSnapshotKey(run.request.workflowId, cycle, run.phaseId)
  }

  override fun guardStandaloneRepairLaunch(
    run: PhaseRun,
    operations: PhaseStandaloneMonitorOperations,
  ): String? {
    requireAcceptedStep(run, acceptedOwner.strategyId)
    val owned = ledger ?: return "Standalone repair requires its prepared execution ledger."
    val worktree = StandaloneRepairCommit(environment.gitOperations, owned.execution.worktree.path)
    return operations.guardRepairLaunch(owned, worktree)
  }

  override fun publishStandaloneRepair(
    run: PhaseRun,
    operations: PhaseStandaloneMonitorOperations,
    reportedFixed: Boolean,
  ): String? {
    requireAcceptedStep(run, acceptedOwner.strategyId)
    return with(FeatureTaskRuntimeRunLoopStandaloneMonitorCycle) {
      environment.publishStandaloneRepair(operations, reportedFixed)
    }
  }
}
