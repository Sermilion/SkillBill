package skillbill.engine.goalrunner.persist

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.engine.diagnostics.block.BlockIncidentIdentity
import skillbill.engine.diagnostics.block.boundTo
import skillbill.engine.goalrunner.model.CrashReconcileExpiredWorkerRequest
import skillbill.engine.goalrunner.model.GoalSubtaskIdentity
import skillbill.engine.workflow.support.workflowFamilyFor
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.commitShaFrom
import skillbill.goalrunner.goalContinuationOutcome
import skillbill.goalrunner.missingResultPrefixTerminalOutcomeArtifact
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.terminalOutcomeFor
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.engine.model.isTerminalStatus
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.goalContinuation
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import java.nio.file.Path
import java.time.Clock

internal class WorkflowGoalRunnerOutcomeTerminalPersistence(
  private val engine: WorkflowEngine,
  private val gitOperations: WorkflowGitOperations,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val progressRecording: WorkflowGoalRunnerProgressRecording,
  private val clock: Clock,
) {
  private val staleBlockedDisplacement = WorkflowGoalRunnerStaleBlockedOutcomeDisplacement(engine, clock)

  fun resolveTerminalOutcome(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    measuredCommitSha: () -> String?,
  ): GoalRunnerStoredOutcome? {
    val candidate =
      workflowStates.readGoalStatusSnapshot(workflowId)?.let { snapshot -> WorkflowFamily.TASK_RUNTIME to snapshot }
    return candidate?.let { (family, snapshot) ->
      engine.snapshotView(family.definition, snapshot)
      val artifacts = snapshot.artifacts
      DurableWorkflowArtifacts.fromMap(artifacts).goalContinuation()
        ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
        ?.let { continuation ->
          terminalOutcomeFor(snapshot, artifacts, continuation, measuredCommitSha)
            ?.withAcceptedBlockReference(snapshot)
        }
    }
  }

  fun recoverResolvedCommitPushBlock(
    workflowStates: WorkflowStateRepository,
    identity: GoalSubtaskIdentity,
    repoRoot: Path,
    outcome: GoalRunnerStoredOutcome,
  ): GoalRunnerStoredOutcome? =
    outcome
      .takeIf { it.status == GoalRunnerTerminalStatus.BLOCKED && it.lastResumableStep == "commit_push" }
      ?.let { workflowFamilyFor(workflowStates, identity.workflowId) }
      ?.let { family -> workflowStates.get(family, identity.workflowId) }
      ?.let { record -> DurableWorkflowArtifacts.fromMap(record.artifacts).goalContinuation() }
      ?.takeIf { continuation ->
        continuation.issueKey == identity.issueKey && continuation.subtaskId == identity.subtaskId
      }
      ?.goalBranch
      ?.takeIf(String::isNotBlank)
      ?.takeIf { branch ->
        gitOperations.validateBranchBase(repoRoot, "origin/$branch", "HEAD") is WorkflowGitOperationResult.Ok
      }
      ?.let { gitOperations.headCommitSha(repoRoot).measuredCommitSha() }
      ?.let { commitSha ->
        GoalRunnerStoredOutcome(
          status = GoalRunnerTerminalStatus.COMPLETE,
          workflowId = identity.workflowId,
          commitSha = commitSha,
          blockedReason = null,
          lastResumableStep = "commit_push",
          suppressPr = outcome.suppressPr,
        )
      }

  fun crashReconcileToResumable(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ): GoalRunnerStoredOutcome? {
    val ownership = workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId) ?: return null
    val row =
      workflowStates.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME) ?: return null
    val continuation =
      DurableWorkflowArtifacts.fromMap(row.toSnapshot().artifacts).goalContinuation()
        ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
        ?: return null
    return crashReconcileExpiredWorkerToResumable(
      CrashReconcileExpiredWorkerRequest(
        workflowStates = workflowStates,
        workerSupervisor = workerSupervisor,
        workflowId = workflowId,
        continuation = continuation,
        ownership = ownership,
        row = row,
      ),
      clock,
    )
  }

  fun persistMeasuredCompletion(
    unitOfWork: UnitOfWork,
    parentWorkflowId: String,
    identity: GoalSubtaskIdentity,
    outcome: GoalRunnerStoredOutcome,
  ) {
    val (workflowId, issueKey, subtaskId) = identity
    val workflowStates = unitOfWork.workflowStates
    if (outcome.status == GoalRunnerTerminalStatus.COMPLETE) {
      requireHistoryDraft(unitOfWork, parentWorkflowId, workflowId, subtaskId)
    }
    val recordContext =
      workflowFamilyFor(workflowStates, workflowId)
        ?.let { family -> workflowStates.get(family, workflowId)?.let { record -> family to record } }
        ?.takeIf { outcome.status == GoalRunnerTerminalStatus.COMPLETE && !outcome.commitSha.isNullOrBlank() }
    recordContext?.let { (family, record) ->
      val artifacts = record.artifacts
      val existingOutcome = goalContinuationOutcome(artifacts, issueKey, subtaskId, outcome.suppressPr)
      val needsBackfill =
        (existingOutcome == null || existingOutcome.commitSha.isNullOrBlank()) &&
          commitShaFrom(artifacts).isNullOrBlank()
      if (needsBackfill) {
        engine.saveRunnerArtifactPatch(
          workflowStates,
          family,
          record,
          mapOf(
            WorkflowWirePayloadKeys.GOAL_CONTINUATION_OUTCOME to
              mapOf(
                SharedPayloadKeys.ISSUE_KEY to issueKey,
                SharedPayloadKeys.SUBTASK_ID to subtaskId,
                SharedPayloadKeys.STATUS to "complete",
                SharedPayloadKeys.WORKFLOW_ID to workflowId,
                "commit_sha" to outcome.commitSha,
                "last_resumable_step" to (outcome.lastResumableStep ?: "commit_push"),
              ),
          ),
        )
      }
    }
  }

  fun displaceStaleBlockedContinuationOutcomeIfPresent(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ) {
    staleBlockedDisplacement.displaceIfPresent(workflowStates, workflowId, issueKey, subtaskId)
  }

  fun recoverMissingResultPrefixTerminalOutcome(
    args: RecoverMissingResultPrefixTerminalOutcomeArgs,
  ): GoalRunnerStoredOutcome? {
    val workflowStates = args.workflowStates
    val family = args.family
    val record = args.record
    val output = args.output.payload
    val issueKey = args.issueKey
    val subtaskId = args.subtaskId
    val workflowId = args.workflowId
    val existingArtifacts = record.artifacts
    val terminalArtifact = recoveredTerminalArtifact(record, output, issueKey, subtaskId, workflowId)
    val existingOutcome = goalContinuationOutcome(existingArtifacts, issueKey, subtaskId, suppressPr = true)
    if (existingOutcome?.status == GoalRunnerTerminalStatus.COMPLETE && existingOutcome.workflowId != workflowId) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
        "Stored completion identity ${existingOutcome.workflowId} does not match recovering child $workflowId.",
      )
    }
    if (existingOutcome?.status == GoalRunnerTerminalStatus.COMPLETE ||
      (terminalArtifact?.get(SharedPayloadKeys.STATUS) as? String) == GoalRunnerTerminalStatus.COMPLETE.wireValue
    ) {
      requireHistoryDraft(args.unitOfWork, args.parentWorkflowId, workflowId, subtaskId)
    }
    val artifactsPatch =
      linkedMapOf<String, Any?>(
        "goal_runner_missing_result_prefix_recovery" to
          linkedMapOf(
            SharedPayloadKeys.ISSUE_KEY to issueKey,
            SharedPayloadKeys.SUBTASK_ID to subtaskId,
            SharedPayloadKeys.WORKFLOW_ID to workflowId,
            "output" to output,
          ),
      )
    if (terminalArtifact != null &&
      goalContinuationOutcome(existingArtifacts, issueKey, subtaskId, suppressPr = true) == null
    ) {
      artifactsPatch[WorkflowWirePayloadKeys.GOAL_CONTINUATION_OUTCOME] = terminalArtifact
    }
    val updated =
      engine.updateRecord(
        family.definition,
        record,
        WorkflowUpdateInput(
          workflowStatus = record.workflowStatus,
          currentStepId = record.currentStepId,
          stepUpdates = null,
          artifactsPatch = WorkflowArtifactPatch.from(artifactsPatch),
          sessionId = record.sessionId.orEmpty(),
        ),
      )
    workflowStates.save(family, updated)
    val recoveredArtifacts = existingArtifacts + artifactsPatch
    val recoveredContinuation =
      DurableWorkflowArtifacts.fromMap(recoveredArtifacts).goalContinuation()
        ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
    val recovered =
      recoveredContinuation?.let {
        goalContinuationOutcome(recoveredArtifacts, issueKey, subtaskId, it.suppressPr)
      }?.copy(workflowId = workflowId)
    val updatedSnapshot = workflowStates.get(family, workflowId) ?: updated
    return recovered?.withAcceptedBlockReference(updatedSnapshot)
      ?: resolveTerminalOutcome(workflowStates, workflowId, issueKey, subtaskId) { null }
  }

  private fun requireContinuationIdentity(
    unitOfWork: UnitOfWork,
    expectedParentWorkflowId: String,
    workflowId: String,
    subtaskId: Int,
  ) {
    val record =
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
        ?: throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
          "Goal child $workflowId is missing while recovering completion.",
        )
    val continuation = DurableWorkflowArtifacts.fromMap(record.artifacts).goalContinuationArtifact()
    val parentMismatch = continuation?.parentWorkflowId?.let { it != expectedParentWorkflowId } == true
    if (continuation == null || continuation.subtaskId != subtaskId || parentMismatch) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
        "Goal child $workflowId has an invalid parent or subtask identity during completion recovery.",
      )
    }
  }

  private fun requireHistoryDraft(
    unitOfWork: UnitOfWork,
    expectedParentWorkflowId: String,
    workflowId: String,
    subtaskId: Int,
  ) {
    requireContinuationIdentity(unitOfWork, expectedParentWorkflowId, workflowId, subtaskId)
    if (!progressRecording.isCurrentGoalChild(unitOfWork, expectedParentWorkflowId, subtaskId, workflowId)) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
        "Goal child $workflowId is no longer the current child for subtask $subtaskId.",
      )
    }
    if (!progressRecording.hasRequiredGoalChildHistoryDraft(
        unitOfWork,
        expectedParentWorkflowId,
        subtaskId,
        workflowId,
      )
    ) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
        "Goal child $workflowId cannot be recovered as complete without its accepted history draft or skip.",
      )
    }
  }

  private fun recoveredTerminalArtifact(
    record: WorkflowStateSnapshot,
    output: Any,
    issueKey: String,
    subtaskId: Int,
    workflowId: String,
  ): FeatureTaskRuntimeWorkflowArtifactMap? {
    val raw = missingResultPrefixTerminalOutcomeArtifact(output, issueKey, subtaskId, workflowId) ?: return null
    val blockedStep =
      (raw[DecompositionManifestPayloadKeys.LAST_RESUMABLE_STEP] as? String)
        .takeIf { raw[SharedPayloadKeys.STATUS] == GoalRunnerTerminalStatus.BLOCKED.wireValue }
    val acceptedReference =
      BlockIncidentIdentity.acceptedFor(
        record.artifacts,
        record.steps,
        blockedStep,
        workflowId,
      )
    return missingResultPrefixTerminalOutcomeArtifact(output, issueKey, subtaskId, workflowId, acceptedReference)
  }
}

internal fun GoalRunnerStoredOutcome.withAcceptedBlockReference(
  snapshot: WorkflowStateSnapshot,
): GoalRunnerStoredOutcome {
  if (status != GoalRunnerTerminalStatus.BLOCKED) return copy(acceptedBlockReference = null)
  return copy(
    acceptedBlockReference =
      acceptedBlockReference?.boundTo(workflowId, lastResumableStep)
        ?: BlockIncidentIdentity.acceptedFor(
          snapshot.artifacts,
          snapshot.steps,
          lastResumableStep,
          snapshot.workflowId,
        ),
  )
}

internal fun WorkflowGitOperationResult.measuredCommitSha(): String? =
  when (this) {
    is WorkflowGitOperationResult.Ok -> value.trim().takeIf(String::isNotBlank)
    is WorkflowGitOperationResult.Failed -> null
  }

internal fun WorkflowEngine.saveRunnerArtifactPatch(
  workflowStates: WorkflowStateRepository,
  family: WorkflowFamily,
  record: WorkflowStateSnapshot,
  patch: Map<String, Any?>,
): WorkflowStateSnapshot {
  val terminal = family.definition.isTerminalStatus(record.workflowStatus)
  val updated =
    updateRecord(
      family.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = record.workflowStatus,
        currentStepId = record.currentStepId,
        stepUpdates = null,
        artifactsPatch = WorkflowArtifactPatch.from(patch),
        sessionId = record.sessionId,
        acceptedSnapshotMutation = terminal,
      ),
    )
  if (terminal) {
    workflowStates.saveAcceptedSnapshotMutation(family, updated)
  } else {
    workflowStates.save(family, updated)
  }
  return updated
}
