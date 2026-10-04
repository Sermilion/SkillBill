package skillbill.engine.goalrunner.persist

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.engine.goalrunner.execution.support.workflowFamilyFor
import skillbill.engine.goalrunner.model.CrashReconcileExpiredWorkerRequest
import skillbill.engine.goalrunner.model.GoalSubtaskIdentity
import skillbill.goalrunner.commitShaFrom
import skillbill.goalrunner.goalContinuationOutcome
import skillbill.goalrunner.missingResultPrefixTerminalOutcomeArtifact
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.terminalOutcomeFor
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.taskruntime.model.persistence.goalContinuation
import java.nio.file.Path
import java.time.Clock

internal class WorkflowGoalRunnerOutcomeTerminalPersistence(
  private val engine: WorkflowEngine,
  private val gitOperations: WorkflowGitOperations,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
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
      workflowFamilyFor(workflowStates, workflowId)
        ?.let { family -> workflowStates.get(family, workflowId)?.let { snapshot -> family to snapshot } }
    return candidate?.let { (family, snapshot) ->
      engine.snapshotView(family.definition, snapshot)
      val artifacts = snapshot.artifacts
      DurableWorkflowArtifacts.fromMap(artifacts).goalContinuation()
        ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
        ?.let { continuation -> terminalOutcomeFor(snapshot, artifacts, continuation, measuredCommitSha) }
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
    val ownership = workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId)
    val row =
      ownership?.let { workflowStates.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME) }
    val continuation =
      row
        ?.takeIf { it.workflowStatus.workflowStatus() == WorkflowStatus.RUNNING }
        ?.let { DurableWorkflowArtifacts.fromMap(it.toSnapshot().artifacts).goalContinuation() }
        ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
    if (ownership == null || row == null || continuation == null) return null
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
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    outcome: GoalRunnerStoredOutcome,
  ) {
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
        val updated =
          engine.updateRecord(
            family.definition,
            record,
            WorkflowUpdateInput(
              workflowStatus = record.workflowStatus,
              currentStepId = record.currentStepId,
              stepUpdates = null,
              artifactsPatch =
                WorkflowArtifactPatch.from(
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
                ),
              sessionId = record.sessionId.orEmpty(),
            ),
          )
        workflowStates.save(family, updated)
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
    val terminalArtifact = missingResultPrefixTerminalOutcomeArtifact(output, issueKey, subtaskId, workflowId)
    val existingArtifacts = record.artifacts
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
    return recovered ?: resolveTerminalOutcome(workflowStates, workflowId, issueKey, subtaskId) { null }
  }
}

internal fun WorkflowGitOperationResult.measuredCommitSha(): String? =
  when (this) {
    is WorkflowGitOperationResult.Ok -> value.trim().takeIf(String::isNotBlank)
    is WorkflowGitOperationResult.Failed -> null
  }
