package skillbill.engine.featuretask.lifecycle.remediation

import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskRuntimeGoalContinuationArtifactPatcher
import skillbill.engine.featuretask.lifecycle.continuation.continuationFromArtifacts
import skillbill.engine.featuretask.lifecycle.continuation.reviewStateFromArtifacts
import skillbill.engine.featuretask.model.subtask.PersistHealedRemediationBaseRequest
import skillbill.engine.featuretask.model.subtask.RemediationBaseBlocked
import skillbill.engine.featuretask.model.subtask.RemediationBaseCoherenceResult
import skillbill.engine.featuretask.model.subtask.RemediationBaseCoherent
import skillbill.engine.featuretask.model.subtask.RemediationBaseHealRequest
import skillbill.engine.featuretask.model.subtask.RemediationReconcileSnapshot
import skillbill.engine.featuretask.model.subtask.RemediationReconciliationApplyRequest
import skillbill.engine.featuretask.model.subtask.RemediationReconciliationBlocked
import skillbill.engine.featuretask.model.subtask.RemediationReconciliationCoherent
import skillbill.engine.featuretask.model.subtask.RemediationReconciliationHeal
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeCheckpointIdentityVersionError
import skillbill.error.shellcontent.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.artifact.decodeCheckpointIdentitiesFromArtifact
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import java.time.Clock

private sealed interface RemediationSnapshotRead {
  data class Available(val snapshot: RemediationReconcileSnapshot) : RemediationSnapshotRead

  data class Refused(val reason: String) : RemediationSnapshotRead

  data object Absent : RemediationSnapshotRead
}

class FeatureTaskRuntimeRemediationBaseReconciler(
  val database: DatabaseSessionFactory,
  val patcher: FeatureTaskRuntimeGoalContinuationArtifactPatcher,
  val clock: Clock,
) {
  fun reconcileRemediationBaseCoherence(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
  ): RemediationBaseCoherenceResult {
    val snapshot =
      when (val read = readRemediationSnapshot(workflowId)) {
        is RemediationSnapshotRead.Available -> read.snapshot
        is RemediationSnapshotRead.Refused -> return RemediationBaseBlocked(read.reason)
        RemediationSnapshotRead.Absent -> return RemediationBaseCoherent(null)
      }
    return reconcileFromSnapshot(
      snapshot = snapshot,
      workflowId = workflowId,
      gitOperations = gitOperations,
      repoRoot = repoRoot,
    )
  }

  private fun readRemediationSnapshot(workflowId: String): RemediationSnapshotRead =
    database.read { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@read RemediationSnapshotRead.Absent
      if (record.workflowStatus in WorkflowStatus.terminalStatuses) {
        return@read RemediationSnapshotRead.Refused("Terminal workflows cannot enter remediation recovery.")
      }
      val artifacts = record.artifacts
      try {
        val state = reviewStateFromArtifacts(artifacts) ?: return@read RemediationSnapshotRead.Absent
        val continuation = continuationFromArtifacts(artifacts) ?: return@read RemediationSnapshotRead.Absent
        val checkpoints =
          decodeCheckpointIdentitiesFromArtifact(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES.value(artifacts),
          )
        RemediationSnapshotRead.Available(RemediationReconcileSnapshot(state, continuation, checkpoints))
      } catch (_: InvalidFeatureTaskRuntimeCheckpointIdentityVersionError) {
        RemediationSnapshotRead.Refused(
          "Checkpoint identity semantics are unsupported. Retain the workflow and its checkpoint evidence; " +
            "inspect status with a compatible runtime or a separately reviewed semantic mapping before recovery.",
        )
      } catch (_: InvalidGoalSubtaskReviewStateSchemaError) {
        RemediationSnapshotRead.Absent
      }
    }

  internal fun appendRemediationRollbackDegradationEvidence(
    workflowId: String,
    signal: RemediationDegradationSignal,
  ) {
    database.transaction { unitOfWork ->
      val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@transaction
      val artifacts = record.artifacts
      val goalBranch = continuationFromArtifacts(artifacts)?.goalBranch.orEmpty()
      val evidenceEntry =
        remediationBaseRecoveryEvidenceEntry(
          RemediationBaseRecovery(
            originalSha = null,
            replacementSha = null,
            reason = "rollback_degradation",
            goalBranch = goalBranch,
            failureMessageOverride = "Remediation rollback degradation at ${signal.seam}.",
          ),
          signal,
        )
      val priorEvidence =
        (DurableWorkflowArtifactFamily.GOAL_REVIEW_BASE_RECOVERIES.value(artifacts) as? List<*>).orEmpty()
      patcher.save(
        record,
        unitOfWork.workflowStates,
        mapOf(
          DurableWorkflowArtifactFamily.GOAL_REVIEW_BASE_RECOVERIES.entry(priorEvidence + evidenceEntry),
        ),
      )
    }
  }
}

internal data class RemediationBaseRecovery(
  val originalSha: String?,
  val replacementSha: String?,
  val reason: String,
  val goalBranch: String,
  val headSha: String? = null,
  val failureMessageOverride: String? = null,
)

internal data class RemediationDegradationSignal(
  val seam: String? = null,
  val valueUsed: String? = null,
  val valueExpected: String? = null,
  val cause: String? = null,
)

internal fun FeatureTaskRuntimeRemediationBaseReconciler.reconcileFromSnapshot(
  snapshot: RemediationReconcileSnapshot,
  workflowId: String,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
): RemediationBaseCoherenceResult {
  if (snapshot.state.remediationBaseSha == null &&
    snapshot.checkpoints.none { it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID }
  ) {
    return RemediationBaseCoherent(snapshot.state)
  }
  val latestRemediationResolved =
    latestResolvedReviewFixCheckpointCommit(
      checkpoints = snapshot.checkpoints,
      gitOperations = gitOperations,
      repoRoot = repoRoot,
    )
  val reconciliation =
    decideRemediationReconciliation(
      snapshot = snapshot,
      latestRemediationResolved = latestRemediationResolved,
      gitOperations = gitOperations,
      repoRoot = repoRoot,
    )
  return applyRemediationReconciliation(
    RemediationReconciliationApplyRequest(
      reconciliation = reconciliation,
      snapshot = snapshot,
      workflowId = workflowId,
      gitOperations = gitOperations,
      repoRoot = repoRoot,
      latestRemediationResolved = latestRemediationResolved,
    ),
  )
}

internal fun FeatureTaskRuntimeRemediationBaseReconciler.applyRemediationReconciliation(
  request: RemediationReconciliationApplyRequest,
): RemediationBaseCoherenceResult {
  val state = request.snapshot.state
  val continuation = request.snapshot.continuation
  val stored = state.remediationBaseSha
  val storedResolves = stored?.let { resolvesCommit(request.gitOperations, request.repoRoot, it) } == true
  return when (request.reconciliation) {
    RemediationReconciliationCoherent -> RemediationBaseCoherent(state)
    RemediationReconciliationBlocked -> {
      val recovered =
        recoveredRemediationBaseSha(
          stored = stored,
          state = state,
          continuation = continuation,
          gitOperations = request.gitOperations,
          repoRoot = request.repoRoot,
        )
      if (recovered != null) {
        healRemediationBaseFromRecoveredBaseline(request, recovered)
      } else {
        blockRemediationBaseReconciliation(request, storedResolves)
      }
    }
    is RemediationReconciliationHeal ->
      healRemediationBase(
        RemediationBaseHealRequest(
          target = request.reconciliation.sha,
          stored = stored,
          storedResolves = storedResolves,
          state = state,
          continuation = continuation,
          workflowId = request.workflowId,
          gitOperations = request.gitOperations,
          repoRoot = request.repoRoot,
          latestRemediationResolved = request.latestRemediationResolved,
        ),
      )
  }
}

private fun FeatureTaskRuntimeRemediationBaseReconciler.healRemediationBaseFromRecoveredBaseline(
  request: RemediationReconciliationApplyRequest,
  recovered: String,
): RemediationBaseCoherenceResult {
  val state = request.snapshot.state
  val continuation = request.snapshot.continuation
  val stored = state.remediationBaseSha
  if (recovered == stored) return RemediationBaseCoherent(state)
  appendRemediationBaseReconciliationEvidence(
    workflowId = request.workflowId,
    recovery =
      RemediationBaseRecovery(
        originalSha = stored,
        replacementSha = recovered,
        reason = "base_not_ancestor",
        goalBranch = continuation.goalBranch,
        failureMessageOverride =
          "Resume recovered remediation_base_sha from the goal branch after the stored base left the branch.",
      ),
    signal =
      RemediationDegradationSignal(
        seam = "FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence",
        valueUsed = stored.orEmpty(),
        valueExpected = "remediation_base_sha reachable from the goal branch",
        cause = "no review_fix checkpoint ref resolved; recovered the nearest reachable base from the goal branch",
      ),
  )
  val healed =
    persistHealedRemediationBaseState(
      PersistHealedRemediationBaseRequest(
        workflowId = request.workflowId,
        target = recovered,
        stored = stored,
        reason = "base_not_ancestor",
        continuation = continuation,
        gitOperations = request.gitOperations,
        repoRoot = request.repoRoot,
      ),
    )
  return RemediationBaseCoherent(healed ?: state)
}

private fun FeatureTaskRuntimeRemediationBaseReconciler.blockRemediationBaseReconciliation(
  request: RemediationReconciliationApplyRequest,
  storedResolves: Boolean,
): RemediationBaseCoherenceResult {
  val continuation = request.snapshot.continuation
  val stored = request.snapshot.state.remediationBaseSha
  val failedRef = latestReviewFixCheckpointRef(request.snapshot.checkpoints)
  val guidance =
    remediationBaseReconciliationBlockedGuidance(
      workflowId = request.workflowId,
      continuation = continuation,
      failedRef = failedRef,
      storedSha = stored,
      storedResolves = storedResolves,
    )
  appendRemediationBaseReconciliationEvidence(
    workflowId = request.workflowId,
    recovery =
      RemediationBaseRecovery(
        originalSha = stored,
        replacementSha = null,
        reason = "reconciliation_blocked",
        goalBranch = continuation.goalBranch,
        failureMessageOverride = guidance,
      ),
    signal =
      RemediationDegradationSignal(
        seam = "FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence",
        valueUsed = failedRef ?: stored.orEmpty(),
        valueExpected = "resolvable review_fix checkpoint ref commit",
        cause = remediationBlockedCause(stored, storedResolves, failedRef),
      ),
  )
  return RemediationBaseBlocked(guidance)
}

private fun FeatureTaskRuntimeRemediationBaseReconciler.healRemediationBase(
  request: RemediationBaseHealRequest,
): RemediationBaseCoherenceResult {
  if (request.target == request.stored) return RemediationBaseCoherent(request.state)
  val reason = remediationBaseHealReason(request.stored, request.target, request.latestRemediationResolved)
  if (!request.storedResolves && request.stored != null) {
    appendRemediationBaseReconciliationEvidence(
      workflowId = request.workflowId,
      recovery =
        RemediationBaseRecovery(
          originalSha = request.stored,
          replacementSha = request.target,
          reason = reason,
          goalBranch = request.continuation.goalBranch,
          failureMessageOverride =
            "Resume reconciled remediation_base_sha ($reason) through checkpoint ref after stored base missed.",
        ),
      signal =
        RemediationDegradationSignal(
          seam = "FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence",
          valueUsed = request.stored,
          valueExpected = "resolvable remediation_base_sha commit",
          cause = "stored remediation base did not resolve; reconciled through checkpoint ref",
        ),
    )
  }
  val healed =
    persistHealedRemediationBaseState(
      PersistHealedRemediationBaseRequest(
        workflowId = request.workflowId,
        target = request.target,
        stored = request.stored,
        reason = reason,
        continuation = request.continuation,
        gitOperations = request.gitOperations,
        repoRoot = request.repoRoot,
      ),
    )
  return RemediationBaseCoherent(healed ?: request.state)
}

internal fun FeatureTaskRuntimeRemediationBaseReconciler.remediationBaseReconciliationBlockedGuidance(
  workflowId: String,
  continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  failedRef: String?,
  storedSha: String?,
  storedResolves: Boolean,
): String =
  "Remediation base reconciliation blocked for workflow '$workflowId' on branch " +
    "'${continuation.goalBranch}': ${remediationBlockedDetail(failedRef, storedSha, storedResolves)}. " +
    "Run `skill-bill goal repair ${continuation.issueKey} --subtask ${continuation.subtaskId} " +
    "--apply` to repoint or clear the unreachable remediation base, then resume the goal child."

private fun remediationBlockedDetail(
  failedRef: String?,
  storedSha: String?,
  storedResolves: Boolean,
): String {
  val storedDetail =
    when {
      storedSha == null -> ""
      storedResolves -> " (stored sha '$storedSha' resolves but is not reachable from the branch)"
      else -> " (stored sha '$storedSha' also failed to resolve)"
    }
  if (failedRef != null) return "checkpoint ref '$failedRef' could not be resolved to a commit$storedDetail"
  return when {
    storedSha == null -> "no remediation base is recorded and no review_fix checkpoint ref resolved to a commit"
    storedResolves ->
      "stored remediation base '$storedSha' is not reachable from the branch and no review_fix " +
        "checkpoint ref resolved to a commit"
    else -> "stored remediation base '$storedSha' could not be resolved to a commit"
  }
}

internal fun FeatureTaskRuntimeRemediationBaseReconciler.appendRemediationBaseReconciliationEvidence(
  workflowId: String,
  recovery: RemediationBaseRecovery,
  signal: RemediationDegradationSignal,
) {
  database.transaction { unitOfWork ->
    val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return@transaction
    val artifacts = record.artifacts
    val evidenceEntry = remediationBaseRecoveryEvidenceEntry(recovery, signal)
    val priorEvidence =
      (DurableWorkflowArtifactFamily.GOAL_REVIEW_BASE_RECOVERIES.value(artifacts) as? List<*>).orEmpty()
    patcher.save(
      record,
      unitOfWork.workflowStates,
      mapOf(
        DurableWorkflowArtifactFamily.GOAL_REVIEW_BASE_RECOVERIES.entry(priorEvidence + evidenceEntry),
      ),
    )
  }
}
