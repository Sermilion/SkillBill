package skillbill.workflow.engine

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.session.WorkflowContinueSessionSummary
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.RUNTIME_REPOSITORY_EVIDENCE_ARTIFACT_KEY
import skillbill.workflow.engine.model.WorkflowContinueDecision
import skillbill.workflow.engine.model.WorkflowContinueDecisionOverrides
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowInputProjection
import skillbill.workflow.engine.model.WorkflowResumeView
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowSummaryView
import skillbill.workflow.engine.model.WorkflowUpdateAcknowledgementView
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.engine.model.isTerminalStatus
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowResumeMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus

class WorkflowEngine {
  fun openRecord(
    definition: WorkflowDefinition,
    workflowId: String,
    sessionId: String,
    currentStepId: String,
  ): WorkflowStateSnapshot {
    validateWorkflowOpen(definition, currentStepId)?.let { throw InvalidWorkflowStateSchemaError(it) }
    val snapshot =
      WorkflowStateSnapshot(
        workflowId = workflowId,
        sessionId = sessionId.trim(),
        workflowName = definition.workflowName,
        contractVersion = definition.contractVersion,
        workflowStatus = WorkflowStatus.RUNNING,
        currentStepId = currentStepId,
        steps = defaultSteps(definition, currentStepId),
        artifacts = DurableWorkflowArtifacts.EMPTY,
        startedAt = null,
        updatedAt = null,
        finishedAt = null,
        mode = definition.workflowMode?.let(FeatureTaskWorkflowMode::fromWireValue),
      )
    return snapshot
  }

  fun updateRecord(
    definition: WorkflowDefinition,
    existing: WorkflowStateSnapshot,
    input: WorkflowUpdateInput,
  ): WorkflowStateSnapshot {
    validateWorkflowUpdate(definition, input)?.let { throw InvalidWorkflowStateSchemaError(it) }
    val mergedArtifacts =
      if (input.replaceArtifacts) {
        LinkedHashMap<String, Any?>()
      } else {
        existing.artifacts.toMutableMap()
      }
    input.artifactsPatch?.let { patch -> mergedArtifacts.putAll(patch) }
    val terminal = definition.isTerminalStatus(input.workflowStatus)
    val updated =
      existing.copy(
        sessionId = input.sessionId.trim().ifBlank { existing.sessionId.orEmpty() },
        workflowStatus = input.workflowStatus,
        currentStepId = input.currentStepId.trim().ifBlank { existing.currentStepId.orEmpty() },
        steps = mergeStepUpdates(definition, existing.steps, input.stepUpdates?.asEntries()),
        artifacts = DurableWorkflowArtifacts.fromMap(mergedArtifacts),
        finishedAt =
          if (terminal) {
            existing.finishedAt ?: input.terminalInstant
              ?: throw InvalidWorkflowStateSchemaError("A terminal workflow update requires a terminal instant.")
          } else {
            null
          },
      )
    return updated
  }

  fun snapshotView(
    definition: WorkflowDefinition,
    record: WorkflowStateSnapshot,
  ): WorkflowSnapshotView {
    check(record.workflowName == definition.workflowName) {
      "Workflow snapshot '${record.workflowId}' belongs to '${record.workflowName}', not '${definition.workflowName}'."
    }
    return snapshotViewFrom(record)
  }

  fun summaryView(
    definition: WorkflowDefinition,
    record: WorkflowStateSnapshot,
  ): WorkflowSummaryView {
    check(record.workflowName == definition.workflowName) {
      "Workflow summary '${record.workflowId}' belongs to '${record.workflowName}', not '${definition.workflowName}'."
    }
    return WorkflowSummaryView(
      workflowId = record.workflowId,
      sessionId = record.sessionId,
      workflowName = record.workflowName,
      mode = record.mode?.wireValue,
      contractVersion = record.contractVersion,
      workflowStatus = record.workflowStatus,
      currentStepId = record.currentStepId,
      startedAt = record.startedAt?.toString().orEmpty(),
      updatedAt = record.updatedAt?.toString().orEmpty(),
      finishedAt = record.finishedAt?.toString().orEmpty(),
    )
  }

  fun updateAcknowledgementView(
    snapshot: WorkflowSnapshotView,
    input: WorkflowUpdateInput,
  ): WorkflowUpdateAcknowledgementView =
    WorkflowUpdateAcknowledgementView(
      status = "ok",
      workflowId = snapshot.workflowId,
      workflowName = snapshot.workflowName,
      workflowStatus = snapshot.workflowStatus,
      currentStepId = snapshot.currentStepId,
      updatedStepIds = input.stepUpdates?.asEntries().orEmpty().mapNotNull { it[SharedPayloadKeys.STEP_ID] as? String },
      updatedArtifactKeys = input.artifactsPatch?.keys?.sorted().orEmpty(),
      readOnlyFullStateGuidance =
        "Update returns a compact acknowledgement. Use explicit read-only workflow get/show for full state, " +
          "including steps and the complete durable artifacts map.",
    )

  fun resumeView(
    definition: WorkflowDefinition,
    record: WorkflowStateSnapshot,
  ): WorkflowResumeView {
    val snapshot = snapshotView(definition, record)
    val stepsById = snapshot.steps.associateBy { it.stepId }
    val lastCompletedStepId =
      definition.stepIds
        .lastOrNull { stepId -> stepsById[stepId]?.status == WorkflowStepStatus.COMPLETED }
        .orEmpty()

    var resumeStepId = snapshot.currentStepId
    val resumeMode =
      when {
        snapshot.workflowStatus == WorkflowStatus.COMPLETED -> WorkflowResumeMode.DONE
        definition.isTerminalStatus(snapshot.workflowStatus) -> WorkflowResumeMode.RECOVER
        else -> WorkflowResumeMode.RESUME
      }
    val currentStepCompleted =
      stepsById[snapshot.currentStepId]?.status == WorkflowStepStatus.COMPLETED
    if (resumeMode == WorkflowResumeMode.RESUME && currentStepCompleted) {
      resumeStepId =
        definition.stepIds.firstOrNull { stepId ->
          stepsById[stepId]?.status in workflowResumableStepStatuses
        }
          ?: snapshot.currentStepId
    }
    val availableArtifacts = snapshot.artifacts.keys.sorted()
    val requiredArtifacts = definition.requiredArtifactsByStep[resumeStepId].orEmpty()
    val missingArtifacts =
      definition.requiredArtifactPresenceResolver.missingRequiredArtifacts(snapshot, resumeStepId, requiredArtifacts)
        .filterNot { it == RUNTIME_REPOSITORY_EVIDENCE_ARTIFACT_KEY }
    val canResume = resumeMode != WorkflowResumeMode.DONE && missingArtifacts.isEmpty()
    val nextAction =
      if (resumeMode == WorkflowResumeMode.DONE) {
        "Workflow already completed. Inspect ${definition.completedTerminalSummaryArtifact} or telemetry for a summary."
      } else {
        definition.resumeActions[resumeStepId]
          ?: "Inspect workflow state, refresh missing artifacts, and continue from the current step."
      }
    return WorkflowResumeView(
      snapshot = snapshot,
      resumeMode = resumeMode,
      resumeStepId = resumeStepId,
      lastCompletedStepId = lastCompletedStepId,
      availableArtifacts = availableArtifacts,
      requiredArtifacts = requiredArtifacts,
      missingArtifacts = missingArtifacts,
      canResume = canResume,
      nextAction = nextAction,
    )
  }

  fun continueDecision(
    definition: WorkflowDefinition,
    record: WorkflowStateSnapshot,
    sessionSummary: WorkflowContinueSessionSummary = WorkflowContinueSessionSummary.EMPTY,
    overrides: WorkflowContinueDecisionOverrides = WorkflowContinueDecisionOverrides(),
  ): WorkflowContinueDecision {
    val resume = resumeView(definition, record)
    val snapshot = resume.snapshot
    val currentStep = snapshot.steps.firstOrNull { it.stepId == resume.resumeStepId }
    val attemptCount = currentStep?.attemptCount ?: 0
    val nextAttemptCount = maxOf(attemptCount + 1, 1)
    val actualContinueStatus = continueStatusFor(snapshot, resume, currentStep)
    val continueStatus = overrides.continueStatus ?: actualContinueStatus
    val workflowStatusBeforeContinue =
      overrides.workflowStatusBeforeContinue ?: snapshot.workflowStatus
    return buildContinueDecision(
      BuildContinueDecisionRequest(
        context =
          ContinueAssemblyContext(
            definition = definition,
            record = record,
            resume = resume,
            snapshot = snapshot,
            declaredProjection =
              launchProjection(
                definition,
                snapshot,
                resume.resumeStepId,
                attemptCount,
                overrides.repositoryCheckpointIdentity,
              ),
          ),
        continueStatus = continueStatus,
        workflowStatusBeforeContinue = workflowStatusBeforeContinue,
        actualContinueStatus = actualContinueStatus,
        nextAttemptCount = nextAttemptCount,
        sessionSummary = sessionSummary,
      ),
    )
  }

  fun launchProjection(
    definition: WorkflowDefinition,
    snapshot: WorkflowSnapshotView,
    stepId: String,
    producerIteration: Int,
    resolvedRepositoryCheckpointIdentity: String = "",
  ): WorkflowInputProjection? =
    definition.inputProjectionsByStep[stepId]?.let {
      WorkflowInputProjectionSelector.select(
        definition,
        snapshot,
        stepId,
        producerIteration,
        resolvedRepositoryCheckpointIdentity,
      )
    }

  fun freshLaunchProjection(
    definition: WorkflowDefinition,
    record: WorkflowStateSnapshot,
    stepId: String,
    producerIteration: Int,
  ): WorkflowInputProjection? =
    launchProjection(definition, snapshotView(definition, record), stepId, producerIteration)
}
