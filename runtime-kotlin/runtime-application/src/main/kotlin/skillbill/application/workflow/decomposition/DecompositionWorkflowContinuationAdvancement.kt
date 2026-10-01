package skillbill.application.workflow.decomposition

import skillbill.application.decomposition.baseBranch
import skillbill.application.workflow.model.AdvanceCompletedSubtasksRequest
import skillbill.application.workflow.model.CheckoutAndValidateBranchRequest
import skillbill.application.workflow.model.GoalContinuationOutcome
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.service.decompositionRuntimeArtifacts
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.baseForSubtask
import skillbill.workflow.decomposition.branchForSubtask
import skillbill.workflow.decomposition.model.DecompositionContinuationSelection
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.withBlockedSubtask
import skillbill.workflow.decomposition.withCommittedSubtask
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import java.nio.file.Path

internal data class AdvancementResult(
  val manifest: DecompositionManifest,
  val error: String? = null,
  val projectionArtifacts: DurableWorkflowArtifacts? = null,
)

internal data class CommitAdvanceResult(
  val manifest: DecompositionManifest,
  val error: String? = null,
)

internal fun WorkflowEngine.advanceCompletedSubtasks(request: AdvanceCompletedSubtasksRequest): AdvancementResult {
  var updated = request.manifest
  request.manifest.subtasks
    .filter { it.status.decompositionStatus() == DecompositionStatus.COMPLETE && it.commitSha.isNullOrBlank() }
    .forEach { subtask ->
      val advanced =
        commitCompletedSubtask(
          updated,
          subtask.id,
          subtask.name,
          request.gitOperations,
          request.repoRootProvider,
        )
      if (advanced.error != null) {
        updated = updated.withBlockedSubtask(subtask.id, advanced.error, "commit_push")
        persistParentDecompositionRuntime(request.parentRecord, updated, request.unitOfWork, request.validator)
        return AdvancementResult(updated, advanced.error, decompositionRuntimeArtifacts(updated, request.validator))
      }
      updated = advanced.manifest
    }
  if (updated != request.manifest) {
    persistParentDecompositionRuntime(request.parentRecord, updated, request.unitOfWork, request.validator)
  }
  return AdvancementResult(updated)
}

internal fun commitCompletedSubtask(
  manifest: DecompositionManifest,
  subtaskId: Int,
  subtaskName: String,
  gitOperations: WorkflowGitOperations,
  repoRootProvider: () -> Path,
): CommitAdvanceResult {
  val branch = manifest.branchForSubtask(subtaskId)
  val checkout =
    if (branch.isNotBlank()) {
      gitOperations.checkoutBranch(repoRootProvider(), branch, manifest.baseForSubtask(subtaskId))
    } else {
      null
    }
  return if (checkout is WorkflowGitOperationResult.Failed) {
    CommitAdvanceResult(manifest, checkout.error.ifBlank { "Git branch checkout failed." })
  } else {
    val commitMessage = "${manifest.issueKey} subtask $subtaskId: $subtaskName"
    when (val commit = gitOperations.createCommit(repoRootProvider(), commitMessage)) {
      is WorkflowGitCommitResult.Committed ->
        CommitAdvanceResult(manifest.withCommittedSubtask(subtaskId, commit.commitSha))
      WorkflowGitCommitResult.NothingToCommit ->
        CommitAdvanceResult(manifest.withCommittedSubtask(subtaskId, ""))
      is WorkflowGitCommitResult.Failed ->
        CommitAdvanceResult(manifest, commit.error.ifBlank { "Git commit failed." })
    }
  }
}

internal fun WorkflowEngine.checkoutAndValidateBranch(
  request: CheckoutAndValidateBranchRequest,
): WorkflowContinueResult? {
  val branchPlan = request.selection.branchPlan

  fun blockedBranchStartResult(reason: String): WorkflowContinueResult {
    val blockedManifest = request.manifest.withBlockedSubtask(request.selection.subtask.id, reason, "create_branch")
    persistParentDecompositionRuntime(request.parentRecord, blockedManifest, request.unitOfWork, request.validator)
    return WorkflowContinueResult.DecompositionBlockedBranchStart(
      dbPath = request.unitOfWork.dbPath.toString(),
      workflowId = request.parentRecord.workflowId,
      issueKey = request.manifest.issueKey,
      blockedReason = reason.ifBlank { "Git operation failed." },
    )
  }
  var errorResult: WorkflowContinueResult? = null
  if (branchPlan.branch.isNotBlank()) {
    val checkout =
      request.gitOperations.checkoutBranch(
        request.repoRootProvider(),
        branchPlan.branch,
        branchPlan.baseBranch,
      )
    errorResult =
      checkout.takeUnless { it is WorkflowGitOperationResult.Ok }
        ?.let { blockedBranchStartResult(it.error) }
    if (errorResult == null && branchPlan.validateBase) {
      errorResult =
        request.gitOperations.validateBranchBase(
          request.repoRootProvider(),
          branchPlan.branch,
          branchPlan.baseBranch,
        )
          .takeUnless { it is WorkflowGitOperationResult.Ok }
          ?.let { blockedBranchStartResult(it.error) }
    }
  }
  return errorResult
}

internal fun subtaskStartArtifacts(
  selection: DecompositionContinuationSelection.Start,
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
): WorkflowArtifactPatch =
  WorkflowArtifactPatch.from(
    mapOf(
      "assessment" to
        mapOf(
          DecompositionPlanningPayloadKeys.SPEC_PATH to selection.subtask.specPath,
          SharedPayloadKeys.ISSUE_KEY to manifest.issueKey,
          SharedPayloadKeys.SUBTASK_ID to selection.subtask.id,
          "accepted_without_user_confirmation" to true,
        ),
      DecompositionPlanningPayloadKeys.BRANCH to
        mapOf(
          "branch_name" to selection.branchPlan.branch,
          DecompositionPlanningPayloadKeys.BRANCH to selection.branchPlan.branch,
        ),
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.entry(
        mapOf(
          SharedPayloadKeys.ISSUE_KEY to manifest.issueKey,
          SharedPayloadKeys.SUBTASK_ID to selection.subtask.id,
          "suppress_pr" to true,
          FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.GOAL_BRANCH to selection.branchPlan.branch,
          FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.CODE_REVIEW_MODE to
            CodeReviewExecutionMode.INLINE.wireValue,
        ),
      ),
      DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.entry(
        validator.encodeManifestWireMap(
          manifest,
          DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label(),
        ),
      ),
    ),
  )!!

internal fun parentProjectionArtifacts(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
  existingArtifacts: DurableWorkflowArtifacts,
): WorkflowArtifactPatch =
  WorkflowArtifactPatch.from(
    LinkedHashMap(existingArtifacts).apply {
      DurableWorkflowArtifactFamily.GOAL_REVIEW_POLICY.removeFrom(this)
      DurableWorkflowArtifactFamily.GOAL_OUT_OF_BAND_ACCEPTANCE.removeFrom(this)
      DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.putInto(
        this,
        validator.encodeManifestWireMap(
          manifest,
          DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label(),
        ),
      )
    },
  )!!

internal fun terminalSubtaskResult(
  parentRecord: WorkflowStateSnapshot,
  manifest: DecompositionManifest,
  selection: DecompositionContinuationSelection.TerminalSubtask,
  dbPath: String,
): WorkflowContinueResult =
  WorkflowContinueResult.DecompositionSubtaskOutcome(
    dbPath = dbPath,
    workflowId = parentRecord.workflowId,
    issueKey = manifest.issueKey,
    subtaskId = selection.subtask.id,
    subtaskSpecPath = selection.subtask.specPath,
    outcome = selection.subtask.toGoalContinuationOutcome(manifest.issueKey),
  )

internal fun DecompositionSubtask.toGoalContinuationOutcome(issueKey: String): GoalContinuationOutcome =
  GoalContinuationOutcome(
    issueKey = issueKey,
    subtaskId = id,
    status = status,
    workflowId = workflowId.orEmpty(),
    commitSha = commitSha,
    blockedReason = blockedReason,
    lastResumableStep = lastResumableStep,
  )
