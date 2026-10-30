package skillbill.engine.work.control

import me.tatarka.inject.annotations.Inject
import skillbill.engine.work.IdeStatusService
import skillbill.engine.work.control.model.WorkControlRejection
import skillbill.engine.work.control.model.WorkControlResult
import skillbill.engine.work.control.model.WorkRunTarget
import skillbill.engine.work.recovery.BlockIncidentRecoveryRefusal
import skillbill.engine.work.recovery.BlockIncidentRecoveryRequest
import skillbill.engine.work.recovery.BlockIncidentRecoveryResult
import skillbill.engine.work.recovery.BlockIncidentRecoveryResumeClaim
import skillbill.engine.work.recovery.BlockIncidentRecoveryService
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.work.model.WorkIncidentRecoveryOutcome
import skillbill.ports.work.model.WorkRecoveryPreviewResult
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
    workId: String,
    incidentToken: String,
    confirmedPrerequisites: Set<BlockIncidentPrerequisiteKind>,
  ): WorkRecoveryPreviewResult {
    val target =
      runTarget(workId)
        ?: return WorkRecoveryPreviewResult.Rejected(WorkControlRejection.UNKNOWN_TARGET.name.lowercase())
    return preview(target, incidentToken, confirmedPrerequisites)
  }

  fun recover(
    workId: String,
    incidentToken: String,
    incidentRevision: Long,
    confirmedPrerequisites: Set<BlockIncidentPrerequisiteKind>,
    agentId: String?,
  ): WorkIncidentRecoveryOutcome {
    val target =
      runTarget(workId)
        ?: return WorkIncidentRecoveryOutcome.Rejected(WorkControlRejection.UNKNOWN_TARGET.name.lowercase(), null)
    return commandOutcome(recover(target, incidentToken, incidentRevision, confirmedPrerequisites, agentId))
  }

  private fun runTarget(id: String): WorkRunTarget? {
    val item = status.detail(WorkDetailRequest(id)).item ?: return null
    val observation = item.databaseFileObservation ?: return null
    return WorkRunTarget(
      id,
      item.snapshot.repositoryIdentity,
      Path.of(item.repositoryRoot),
      item.snapshot.execution?.executionId,
      item.snapshot.execution?.statusRevision,
      item.snapshot.updatedAt,
      observation,
    )
  }

  private fun commandOutcome(result: WorkControlResult): WorkIncidentRecoveryOutcome =
    when (result) {
      is WorkControlResult.Recovered ->
        WorkIncidentRecoveryOutcome.Recovered(
          result.attemptId,
          result.status,
          result.failure,
          result.launch?.let(::commandOutcome),
        )
      is WorkControlResult.Rejected ->
        WorkIncidentRecoveryOutcome.Rejected(
          result.reason.name.lowercase(),
          result.detail,
        )
      is WorkControlResult.LaunchFailed -> WorkIncidentRecoveryOutcome.LaunchFailed(result.reason)
      else -> WorkIncidentRecoveryOutcome.Accepted
    }

  fun preview(
    target: WorkRunTarget,
    incidentToken: String,
    confirmedPrerequisites: Set<BlockIncidentPrerequisiteKind>,
  ): WorkRecoveryPreviewResult {
    val basis =
      when (val fenced = recovery.requestBasis(incidentToken, target.databaseFileObservation)) {
        FencedTransactionResult.ChangedTarget ->
          return WorkRecoveryPreviewResult.Rejected(WorkControlRejection.CHANGED_TARGET.name.lowercase())
        is FencedTransactionResult.Committed ->
          fenced.value
            ?: return WorkRecoveryPreviewResult.Rejected(WorkControlRejection.NOT_FOUND.name.lowercase())
      }
    val result =
      recovery.preview(request(target, incidentToken, basis.incidentRevision, basis.action, confirmedPrerequisites))
    return when (result) {
      is BlockIncidentRecoveryResult.Previewed -> WorkRecoveryPreviewResult.Available(result.preview)
      is BlockIncidentRecoveryResult.PrerequisitesRequired ->
        WorkRecoveryPreviewResult.NeedsPrerequisites(result.instructions, result.preview)
      is BlockIncidentRecoveryResult.Existing ->
        result.attempt.let { WorkRecoveryPreviewResult.ExistingAttempt(it.attemptId, it.status, it.failure) }
      else -> rejection(result).let { WorkRecoveryPreviewResult.Rejected(it.reason.name.lowercase(), it.detail) }
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
      when (val fenced = recovery.requestBasis(incidentToken, target.databaseFileObservation)) {
        FencedTransactionResult.ChangedTarget -> return WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
        is FencedTransactionResult.Committed ->
          fenced.value
            ?: return WorkControlResult.Rejected(WorkControlRejection.NOT_FOUND)
      }
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
      when (
        val result =
          recovery.fencedRead(requested.databaseFileObservation) {
            status.detail(WorkDetailRequest(id)).item
          }
      ) {
        FencedTransactionResult.ChangedTarget -> return WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
        is FencedTransactionResult.Committed ->
          result.value
            ?: return WorkControlResult.Rejected(WorkControlRejection.UNKNOWN_TARGET)
      }
    val root = Path.of(item.repositoryRoot).toAbsolutePath().normalize()
    val rejection =
      when {
        item.snapshot.repositoryIdentity != requested.repositoryIdentity ||
          root != requested.repositoryRoot.toAbsolutePath().normalize() -> WorkControlRejection.UNKNOWN_TARGET
        item.databaseFileObservation != requested.databaseFileObservation -> WorkControlRejection.CHANGED_TARGET
        else -> null
      }
    if (rejection != null) return WorkControlResult.Rejected(rejection)
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
    return runCatching { runs.launch(resumed, agentId) }.getOrElse { failure ->
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
