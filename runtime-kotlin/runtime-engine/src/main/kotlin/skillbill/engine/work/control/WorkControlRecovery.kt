package skillbill.engine.work.control

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.block.recovery.BlockIncidentRecoveryRefusal
import skillbill.engine.diagnostics.block.recovery.BlockIncidentRecoveryRequest
import skillbill.engine.diagnostics.block.recovery.BlockIncidentRecoveryResult
import skillbill.engine.diagnostics.block.recovery.BlockIncidentRecoveryResumeClaim
import skillbill.engine.diagnostics.block.recovery.BlockIncidentRecoveryService
import skillbill.engine.work.IdeStatusService
import skillbill.engine.work.control.model.WorkControlRejection
import skillbill.engine.work.control.model.WorkControlResult
import skillbill.engine.work.control.model.WorkRecoveryPreviewResult
import skillbill.engine.work.control.model.WorkRunTarget
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentPrerequisiteKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAction
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import java.nio.file.Path
import java.util.concurrent.CancellationException

@Inject
class WorkControlRecovery(
  private val status: IdeStatusService,
  private val recovery: BlockIncidentRecoveryService,
  private val runs: WorkRunActions,
) {
  fun preview(
    target: WorkRunTarget,
    incidentToken: String,
    confirmedPrerequisites: Set<BlockIncidentPrerequisiteKind>,
  ): WorkRecoveryPreviewResult {
    val basis =
      recovery.requestBasis(incidentToken)
        ?: return WorkRecoveryPreviewResult.Rejected(WorkControlRejection.NOT_FOUND)
    val result =
      recovery.preview(request(target, incidentToken, basis.incidentRevision, basis.action, confirmedPrerequisites))
    return when (result) {
      is BlockIncidentRecoveryResult.Previewed -> WorkRecoveryPreviewResult.Available(result.preview)
      is BlockIncidentRecoveryResult.PrerequisitesRequired ->
        WorkRecoveryPreviewResult.NeedsPrerequisites(result.instructions, result.preview)
      is BlockIncidentRecoveryResult.Existing ->
        result.attempt.let { WorkRecoveryPreviewResult.ExistingAttempt(it.attemptId, it.status, it.failure) }
      else -> rejection(result).let { WorkRecoveryPreviewResult.Rejected(it.reason, it.detail) }
    }
  }

  fun recover(
    target: WorkRunTarget,
    incidentToken: String,
    incidentRevision: Long,
    confirmedPrerequisites: Set<BlockIncidentPrerequisiteKind>,
    agentId: String?,
  ): WorkControlResult {
    val basis =
      recovery.requestBasis(incidentToken)
        ?: return WorkControlResult.Rejected(WorkControlRejection.NOT_FOUND)
    val result =
      recovery.apply(request(target, incidentToken, incidentRevision, basis.action, confirmedPrerequisites))
    return when (result) {
      is BlockIncidentRecoveryResult.Applied -> resume(target, result.attempt, agentId)
      is BlockIncidentRecoveryResult.Existing ->
        if (result.attempt.status == BlockIncidentRecoveryAttemptStatus.APPLIED) {
          resume(target, result.attempt, agentId)
        } else {
          settled(result.attempt)
        }
      is BlockIncidentRecoveryResult.Failed -> recovered(result.attempt, null)
      is BlockIncidentRecoveryResult.Resumed -> recovered(result.attempt, null)
      else -> rejection(result)
    }
  }

  private fun request(
    target: WorkRunTarget,
    incidentToken: String,
    incidentRevision: Long,
    action: BlockIncidentRecoveryAction,
    confirmedPrerequisites: Set<BlockIncidentPrerequisiteKind>,
  ): BlockIncidentRecoveryRequest =
    BlockIncidentRecoveryRequest(
      target.databaseFileObservation,
      incidentToken,
      incidentRevision,
      action,
      confirmedPrerequisites,
      requestedTargetId = target.id,
    )

  private fun resume(
    target: WorkRunTarget,
    attempt: BlockIncidentRecoveryAttempt,
    agentId: String?,
  ): WorkControlResult {
    val claim =
      when (val claimed = recovery.claimResume(target.databaseFileObservation, attempt.idempotencyKey)) {
        BlockIncidentRecoveryResumeClaim.ChangedDatabaseFile ->
          return WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
        is BlockIncidentRecoveryResumeClaim.NotClaimed ->
          return claimed.attempt?.let(::settled) ?: WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
        is BlockIncidentRecoveryResumeClaim.Claimed -> claimed
      }
    val launch = launchResumed(target, claim.attempt, agentId)
    val launched = launch == WorkControlResult.Accepted
    return when (val settled = recovery.settleResume(target.databaseFileObservation, claim, launched)) {
      is BlockIncidentRecoveryResult.Resumed -> recovered(settled.attempt, launch)
      is BlockIncidentRecoveryResult.Failed -> recovered(settled.attempt, launch)
      is BlockIncidentRecoveryResult.Existing -> recovered(settled.attempt, launch)
      else -> rejection(settled)
    }
  }

  private fun launchResumed(
    requested: WorkRunTarget,
    attempt: BlockIncidentRecoveryAttempt,
    agentId: String?,
  ): WorkControlResult {
    val id = attempt.targetInvocationId ?: attempt.targetWorkflowId
    val item =
      status.detail(WorkDetailRequest(id)).item
        ?: return WorkControlResult.Rejected(WorkControlRejection.UNKNOWN_TARGET)
    val root = Path.of(item.repositoryRoot).toAbsolutePath().normalize()
    if (item.snapshot.repositoryIdentity != requested.repositoryIdentity ||
      root != requested.repositoryRoot.toAbsolutePath().normalize()
    ) {
      return WorkControlResult.Rejected(WorkControlRejection.UNKNOWN_TARGET)
    }
    if (item.databaseFileObservation != requested.databaseFileObservation) {
      return WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
    }
    val resumed =
      WorkRunTarget(
        id,
        item.snapshot.repositoryIdentity,
        root,
        item.snapshot.execution?.executionId,
        item.snapshot.execution?.statusRevision,
        item.snapshot.updatedAt,
        requested.databaseFileObservation,
      )
    return try {
      runs.launch(resumed, agentId)
    } catch (failure: RuntimeException) {
      if (failure is CancellationException) throw failure
      WorkControlResult.LaunchFailed(failure.message ?: failure::class.simpleName.orEmpty())
    }
  }

  private fun settled(attempt: BlockIncidentRecoveryAttempt): WorkControlResult =
    when (attempt.status) {
      BlockIncidentRecoveryAttemptStatus.RESUMED, BlockIncidentRecoveryAttemptStatus.FAILED -> recovered(attempt, null)
      else -> WorkControlResult.Rejected(WorkControlRejection.RECOVERY_IN_PROGRESS)
    }

  private fun recovered(
    attempt: BlockIncidentRecoveryAttempt,
    launch: WorkControlResult?,
  ): WorkControlResult = WorkControlResult.Recovered(attempt.attemptId, attempt.status, attempt.failure, launch)

  private fun rejection(result: BlockIncidentRecoveryResult): WorkControlResult.Rejected =
    when (result) {
      is BlockIncidentRecoveryResult.Refused ->
        when (result.reason) {
          BlockIncidentRecoveryRefusal.INCIDENT_NOT_FOUND -> WorkControlResult.Rejected(WorkControlRejection.NOT_FOUND)
          BlockIncidentRecoveryRefusal.STALE_INCIDENT ->
            WorkControlResult.Rejected(WorkControlRejection.STALE_INCIDENT)
          BlockIncidentRecoveryRefusal.RECOVERY_IN_PROGRESS ->
            WorkControlResult.Rejected(WorkControlRejection.RECOVERY_IN_PROGRESS)
          BlockIncidentRecoveryRefusal.TARGET_MISMATCH ->
            WorkControlResult.Rejected(WorkControlRejection.UNKNOWN_TARGET)
          BlockIncidentRecoveryRefusal.OWNERSHIP_MISMATCH ->
            WorkControlResult.Rejected(WorkControlRejection.OWNERSHIP_MISMATCH)
          BlockIncidentRecoveryRefusal.CHANGED_DATABASE_FILE ->
            WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
          else ->
            WorkControlResult.Rejected(WorkControlRejection.RECOVERY_UNAVAILABLE, result.reason.name.lowercase())
        }
      is BlockIncidentRecoveryResult.TargetUnavailable ->
        WorkControlResult.Rejected(WorkControlRejection.RECOVERY_UNAVAILABLE, result.reason.name.lowercase())
      is BlockIncidentRecoveryResult.Ineligible ->
        WorkControlResult.Rejected(WorkControlRejection.RECOVERY_UNAVAILABLE, result.reason.name.lowercase())
      is BlockIncidentRecoveryResult.PrerequisitesRequired ->
        WorkControlResult.Rejected(
          WorkControlRejection.PREREQUISITE_UNMET,
          result.instructions.joinToString { it.kind.wireValue },
        )
      else -> WorkControlResult.Rejected(WorkControlRejection.RECOVERY_UNAVAILABLE)
    }
}
