package skillbill.application.workflow.decomposition

import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.resolveDecompositionManifest
import skillbill.application.workflow.model.AdvanceCompletedSubtasksRequest
import skillbill.application.workflow.model.CheckoutAndValidateBranchRequest
import skillbill.application.workflow.model.ContinueExistingWorkflowArgs
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.persist.generateWorkflowId
import skillbill.application.workflow.service.ContinuationStepResult
import skillbill.application.workflow.service.blockedGitResult
import skillbill.application.workflow.service.blockedSubtaskResult
import skillbill.application.workflow.service.decompositionRuntimeArtifacts
import skillbill.application.workflow.service.doneDecompositionResult
import skillbill.application.workflow.service.migrateLegacyGoalRunnerControls
import skillbill.application.workflow.service.missingSubtaskWorkflowResult
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.issuekey.normalizeRequiredIssueKey
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.commitPushResultArtifact
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.decomposition.DecompositionContinuationSelector
import skillbill.workflow.decomposition.model.DecompositionContinuationSelection
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.decomposition.runtime.normalizedBlockedReason
import skillbill.workflow.decomposition.withParentStatus
import skillbill.workflow.decomposition.withStartedSubtask
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import java.nio.file.Path
import java.time.Clock
import kotlin.random.Random

class DecompositionWorkflowContinuation(
  private val engine: WorkflowEngine,
  private val gitOperations: WorkflowGitOperations,
  private val validator: DecompositionManifestValidator,
  private val fileStore: DecompositionManifestStore? = null,
  private val repoRoot: Path,
  private val manifestWriter: DecompositionManifestWriter,
  private val clock: Clock,
  private val workflowIdRandom: Random,
) {
  private fun repositoryCheckpointIdentity(): String {
    val resolved = gitOperations.repositoryFingerprint(repoRoot)
    check(resolved is WorkflowGitOperationResult.Ok) { resolved.error }
    return resolved.value.orEmpty()
  }

  internal fun continueDecomposedParentByIssueKey(
    issueKey: String,
    unitOfWork: UnitOfWork,
    requestedSubtaskId: Int? = null,
  ): ContinuationStepResult {
    val diskManifest = findProjectedManifestByIssueKey(issueKey)
    var parentRecord =
      unitOfWork.workflowStates
        .findDecomposedParentOrCorruptFallback(issueKey, diskManifest)
        ?.toSnapshot()
    var manifest = parentRecord?.decompositionRuntimeOrNull()
    if (parentRecord == null || manifest == null) {
      if (diskManifest != null) {
        parentRecord = bootstrapParentWorkflowFromManifest(diskManifest, unitOfWork)
        manifest = parentRecord.decompositionRuntime()
      }
    }
    val result =
      if (parentRecord == null || manifest == null) {
        ContinuationStepResult(
          WorkflowContinueResult.UnknownWorkflow(
            dbPath = unitOfWork.dbPath.toString(),
            workflowId = issueKey,
          ),
        )
      } else {
        unitOfWork.workflowStates.getFeatureTaskWorkflow(parentRecord.workflowId)
          ?.requireRuntimeModeForEngineWrite()
        continueManifest(parentRecord, manifest, unitOfWork, requestedSubtaskId)
      }
    return result
  }

  private fun findProjectedManifestByIssueKey(issueKey: String): DecompositionManifest? {
    val store = fileStore ?: return null
    return resolveDecompositionManifest(
      repoRoot = repoRoot,
      issueKey = issueKey,
      fileStore = store,
      validator = validator,
    )
  }

  private fun bootstrapParentWorkflowFromManifest(
    manifest: DecompositionManifest,
    unitOfWork: UnitOfWork,
  ): WorkflowStateSnapshot {
    val issueKey = normalizeRequiredIssueKey(manifest.issueKey)
    val existingRecord =
      unitOfWork.workflowStates.findDecomposedParentOrCorruptFallback(
        manifest.issueKey,
        manifest,
      )
    existingRecord?.requireRuntimeModeForEngineWrite()
    val existing = existingRecord?.toSnapshot()
    val base =
      existing ?: engine.openRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        generateWorkflowId(WorkflowFamily.TASK_RUNTIME.definition.workflowIdPrefix, clock, workflowIdRandom),
        WorkflowFamily.TASK_RUNTIME.definition.defaultSessionPrefix,
        "plan",
      )
    existing?.let { migrateLegacyGoalRunnerControls(unitOfWork, it) }
    val imported =
      engine.updateRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        base,
        WorkflowUpdateInput(
          workflowStatus = WorkflowStatus.PAUSED,
          currentStepId = "plan",
          stepUpdates =
            if (existing != null) {
              null
            } else {
              WorkflowStepUpdates.from(
                listOf(
                  mapOf(
                    SharedPayloadKeys.STEP_ID to "preplan",
                    SharedPayloadKeys.STATUS to WorkflowStepStatus.COMPLETED.wireValue,
                    "attempt_count" to 1,
                  ),
                  mapOf(
                    SharedPayloadKeys.STEP_ID to "plan",
                    SharedPayloadKeys.STATUS to WorkflowStepStatus.COMPLETED.wireValue,
                    "attempt_count" to 1,
                  ),
                ),
              )
            },
          artifactsPatch = parentProjectionArtifacts(manifest, validator, base.artifacts),
          sessionId = base.sessionId.orEmpty(),
          replaceArtifacts = true,
        ),
      )
    unitOfWork.workflowStates.saveRecord(
      WorkflowFamily.TASK_RUNTIME,
      imported.toRecord().copy(issueKey = issueKey),
    )
    return unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, imported.workflowId) ?: imported
  }

  private fun continueManifest(
    parentRecord: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    unitOfWork: UnitOfWork,
    requestedSubtaskId: Int?,
  ): ContinuationStepResult {
    val reconciledManifest = reconcileCompletedSubtasks(manifest, unitOfWork)
    val advancement =
      if (requestedSubtaskId == null) {
        engine.advanceCompletedSubtasks(
          AdvanceCompletedSubtasksRequest(
            parentRecord = parentRecord,
            manifest = reconciledManifest,
            unitOfWork = unitOfWork,
            validator = validator,
            gitOperations = gitOperations,
            repoRootProvider = { repoRoot },
          ),
        )
      } else {
        AdvancementResult(reconciledManifest)
      }
    if (advancement.error != null) {
      val blocked =
        ContinuationStepResult(
          blockedGitResult(parentRecord.workflowId, manifest.issueKey, unitOfWork.dbPath.toString(), advancement.error),
          advancement.projectionArtifacts,
          projectionOwnerWorkflowId = parentRecord.workflowId.takeIf { advancement.projectionArtifacts != null },
        )
      return blocked
    }
    val advancedManifest = advancement.manifest
    val projectionArtifacts =
      if (advancedManifest != manifest) decompositionRuntimeArtifacts(advancedManifest, validator) else null
    return selectedContinuation(parentRecord, advancedManifest, unitOfWork, requestedSubtaskId)
      .withProjectionArtifactsIfMissing(projectionArtifacts)
      .let { step ->
        if (step.projectionOwnerWorkflowId == null && step.projectionArtifacts != null) {
          step.copy(projectionOwnerWorkflowId = parentRecord.workflowId)
        } else {
          step
        }
      }
  }

  private fun reconcileCompletedSubtasks(
    manifest: DecompositionManifest,
    unitOfWork: UnitOfWork,
  ): DecompositionManifest =
    manifest.copy(
      subtasks = manifest.subtasks.map { subtask -> reconcileSubtask(subtask, unitOfWork) },
    ).withParentStatus()

  private fun reconcileSubtask(
    subtask: DecompositionSubtask,
    unitOfWork: UnitOfWork,
  ): DecompositionSubtask {
    val snapshot = findSubtaskSnapshot(subtask, unitOfWork) ?: return subtask
    val artifacts = DurableWorkflowArtifacts.fromMap(snapshot.artifacts)
    val commitPushResult = artifacts.commitPushResultArtifact()
    val goalContinuation =
      (
        artifacts[
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.value(snapshot.artifacts),
        ] as? Map<*, *>
      )
    val commitPushCompleted =
      snapshot.steps.any { step ->
        step.stepId == "commit_push" && step.status == WorkflowStepStatus.COMPLETED
      }
    return when {
      snapshot.workflowStatus == WorkflowStatus.COMPLETED ||
        goalContinuation?.get("suppress_pr") == true &&
        (snapshot.currentStepId == "pr" || commitPushResult?.commitSha != null) ->
        subtask.copy(
          status = DecompositionStatus.COMPLETE.wireValue,
          blockedReason = null,
          commitSha = commitPushResult?.commitSha ?: subtask.commitSha,
          lastResumableStep = snapshot.currentStepId,
        )
      snapshot.workflowStatus == WorkflowStatus.BLOCKED ->
        subtask.copy(
          status = DecompositionStatus.BLOCKED.wireValue,
          blockedReason =
            normalizedBlockedReason(
              reason = snapshot.artifacts[DecompositionManifestPayloadKeys.BLOCKED_REASON]?.toString(),
              category = "runtime",
              fallback = "Workflow step '${snapshot.currentStepId.ifBlank { "unknown" }}' is blocked.",
            ),
        )
      goalContinuation?.get("suppress_pr") == true &&
        commitPushCompleted &&
        commitPushResult?.commitSha == null ->
        subtask.copy(
          status = DecompositionStatus.BLOCKED.wireValue,
          blockedReason = "git: Goal-continuation commit_push completed without commit_push_result.commit_sha.",
          lastResumableStep = snapshot.currentStepId,
        )
      else -> subtask
    }
  }

  private fun findSubtaskSnapshot(
    subtask: DecompositionSubtask,
    unitOfWork: UnitOfWork,
  ): WorkflowStateSnapshot? =
    subtask.workflowId
      ?.let { workflowId -> unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) }
      ?: sequenceOf(
        unitOfWork.workflowStates.listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME, Int.MAX_VALUE),
        unitOfWork.workflowStates.listFeatureTaskWorkflows(FeatureTaskWorkflowMode.PROSE, Int.MAX_VALUE),
      )
        .flatten()
        .firstOrNull { record ->
          val assessment = record.toSnapshot().artifacts["assessment"] as? Map<*, *>
          assessment?.get(DecompositionPlanningPayloadKeys.SPEC_PATH)?.toString() == subtask.specPath
        }
        ?.toSnapshot()

  private fun selectedContinuation(
    parentRecord: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    unitOfWork: UnitOfWork,
    requestedSubtaskId: Int?,
  ): ContinuationStepResult =
    when (
      val selection = DecompositionContinuationSelector.select(manifest, requestedSubtaskId)
    ) {
      is DecompositionContinuationSelection.Resume -> continueSelectedSubtask(manifest, selection, unitOfWork)
      is DecompositionContinuationSelection.Start -> startSelectedSubtask(parentRecord, manifest, selection, unitOfWork)
      is DecompositionContinuationSelection.Blocked ->
        ContinuationStepResult(blockedSubtaskResult(parentRecord, manifest, selection, unitOfWork.dbPath.toString()))
      is DecompositionContinuationSelection.TerminalSubtask ->
        ContinuationStepResult(terminalSubtaskResult(parentRecord, manifest, selection, unitOfWork.dbPath.toString()))
      is DecompositionContinuationSelection.Done ->
        ContinuationStepResult(doneDecompositionResult(parentRecord, selection.manifest, unitOfWork.dbPath.toString()))
    }

  private fun continueSelectedSubtask(
    manifest: DecompositionManifest,
    selection: DecompositionContinuationSelection.Resume,
    unitOfWork: UnitOfWork,
  ): ContinuationStepResult {
    val record =
      selection.workflowId
        .takeIf(String::isNotBlank)
        ?.let { unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, it) }
    return if (record == null) {
      missingSubtaskWorkflowResult(selection, unitOfWork)
    } else {
      val alignedRecord = engine.alignSubtaskResumeStep(record, selection.resumeStepId, unitOfWork)
      engine.continueExistingWorkflow(
        WorkflowFamily.TASK_RUNTIME,
        alignedRecord,
        unitOfWork,
        ContinueExistingWorkflowArgs(
          validator = validator,
          fileStore = fileStore,
          repoRoot = repoRoot,
          manifestWriter = manifestWriter,
          repositoryCheckpointIdentity = ::repositoryCheckpointIdentity,
        ),
      ).withDecompositionFields(
        issueKey = manifest.issueKey,
        subtaskId = selection.subtask.id,
        specPath = selection.subtask.specPath,
        outcome = selection.subtask.toGoalContinuationOutcome(manifest.issueKey),
      )
    }
  }

  private fun startSelectedSubtask(
    parentRecord: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    selection: DecompositionContinuationSelection.Start,
    unitOfWork: UnitOfWork,
  ): ContinuationStepResult {
    val issueKey = normalizeRequiredIssueKey(manifest.issueKey)
    val branchError =
      engine.checkoutAndValidateBranch(
        CheckoutAndValidateBranchRequest(
          parentRecord = parentRecord,
          manifest = manifest,
          selection = selection,
          unitOfWork = unitOfWork,
          validator = validator,
          gitOperations = gitOperations,
          repoRootProvider = { repoRoot },
        ),
      )
    return if (branchError != null) {
      ContinuationStepResult(branchError)
    } else {
      openSubtaskWorkflow(parentRecord, manifest, selection, issueKey, unitOfWork)
    }
  }

  private fun openSubtaskWorkflow(
    parentRecord: WorkflowStateSnapshot,
    manifest: DecompositionManifest,
    selection: DecompositionContinuationSelection.Start,
    issueKey: String,
    unitOfWork: UnitOfWork,
  ): ContinuationStepResult {
    val workflowId =
      generateWorkflowId(WorkflowFamily.TASK_RUNTIME.definition.workflowIdPrefix, clock, workflowIdRandom)
    val updatedManifest = manifest.withStartedSubtask(selection.subtask.id, workflowId, selection.branchPlan.branch)
    val opened =
      engine.openRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        workflowId,
        parentRecord.sessionId.orEmpty(),
        "preplan",
      )
    val started =
      engine.updateRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        opened,
        WorkflowUpdateInput(
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "preplan",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf(
                  SharedPayloadKeys.STEP_ID to "preplan",
                  SharedPayloadKeys.STATUS to WorkflowStepStatus.RUNNING.wireValue,
                  "attempt_count" to 1,
                ),
              ),
            ),
          artifactsPatch = subtaskStartArtifacts(selection, updatedManifest, validator),
          sessionId = parentRecord.sessionId.orEmpty(),
        ),
      )
    unitOfWork.workflowStates.saveRecord(
      WorkflowFamily.TASK_RUNTIME,
      started.toRecord().copy(issueKey = issueKey),
    )
    engine.persistParentDecompositionRuntime(parentRecord, updatedManifest, unitOfWork, validator)
    val saved = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: started
    return engine.continueExistingWorkflow(
      WorkflowFamily.TASK_RUNTIME,
      saved,
      unitOfWork,
      ContinueExistingWorkflowArgs(
        validator = validator,
        fileStore = fileStore,
        repoRoot = repoRoot,
        manifestWriter = manifestWriter,
        repositoryCheckpointIdentity = ::repositoryCheckpointIdentity,
      ),
    )
      .withProjection(updatedManifest, validator, parentRecord.workflowId)
      .withDecompositionFields(
        issueKey = manifest.issueKey,
        subtaskId = selection.subtask.id,
        specPath = selection.subtask.specPath,
        outcome =
          updatedManifest.subtasks.single { it.id == selection.subtask.id }
            .toGoalContinuationOutcome(manifest.issueKey),
      )
  }
}

private fun WorkflowStateSnapshot.decompositionRuntimeOrNull(): DecompositionManifest? =
  try {
    decompositionRuntime()
  } catch (_: InvalidWorkflowStateSchemaError) {
    null
  }
