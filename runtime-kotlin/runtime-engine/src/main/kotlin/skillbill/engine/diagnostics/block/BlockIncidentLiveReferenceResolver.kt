package skillbill.engine.diagnostics.block

import skillbill.goalrunner.goalContinuationOutcomeArtifact
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.StandalonePhaseIncidentAlias
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact

internal sealed interface BlockIncidentLiveReferenceResolution {
  data class Verified(val incidentToken: String) : BlockIncidentLiveReferenceResolution

  data object None : BlockIncidentLiveReferenceResolution

  data class Unavailable(val reason: Reason) : BlockIncidentLiveReferenceResolution

  enum class Reason {
    WORKFLOW_NOT_FOUND,
    BLOCKED_STEP_UNAVAILABLE,
    ACCEPTED_REFERENCE_UNAVAILABLE,
    ACCEPTED_REFERENCE_MISMATCH,
    CHILD_BINDING_UNAVAILABLE,
    INVOCATION_NOT_FOUND,
    INVOCATION_NOT_BLOCKED,
    INVOCATION_IDENTITY_MISMATCH,
    ACCEPTED_INCIDENT_NOT_FOUND,
  }
}

internal object BlockIncidentLiveReferenceResolver {
  fun resolve(
    unit: UnitOfWork,
    workflowId: String?,
    invocationId: String?,
    blocked: Boolean,
  ): BlockIncidentLiveReferenceResolution {
    return when {
      !blocked -> BlockIncidentLiveReferenceResolution.None
      invocationId != null -> resolveLinkedInvocation(unit, workflowId, invocationId)
      workflowId != null -> resolveWorkflow(unit, workflowId)
      else -> BlockIncidentLiveReferenceResolution.None
    }
  }

  private fun resolveLinkedInvocation(
    unit: UnitOfWork,
    workflowId: String?,
    invocationId: String,
  ): BlockIncidentLiveReferenceResolution {
    val invocation =
      unit.standalonePhaseStatuses.readByInvocation(invocationId)
        ?: return unavailable(BlockIncidentLiveReferenceResolution.Reason.INVOCATION_NOT_FOUND)
    val standalone = resolveInvocation(unit, invocation)
    if (standalone !is BlockIncidentLiveReferenceResolution.Verified) return standalone
    if (workflowId != null && invocation.workflowId != workflowId) {
      return unavailable(BlockIncidentLiveReferenceResolution.Reason.INVOCATION_IDENTITY_MISMATCH)
    }
    return invocation.workflowId?.let { linkedWorkflowId ->
      when (val workflow = resolveWorkflow(unit, linkedWorkflowId)) {
        is BlockIncidentLiveReferenceResolution.Verified ->
          if (workflow.incidentToken == standalone.incidentToken) {
            standalone
          } else {
            unavailable(BlockIncidentLiveReferenceResolution.Reason.ACCEPTED_REFERENCE_MISMATCH)
          }
        BlockIncidentLiveReferenceResolution.None ->
          unavailable(BlockIncidentLiveReferenceResolution.Reason.ACCEPTED_REFERENCE_MISMATCH)
        is BlockIncidentLiveReferenceResolution.Unavailable -> workflow
      }
    } ?: standalone
  }

  private fun resolveInvocation(
    unit: UnitOfWork,
    record: StandalonePhaseStatusRecord,
  ): BlockIncidentLiveReferenceResolution {
    if (record.lifecycleState != "blocked") {
      return unavailable(BlockIncidentLiveReferenceResolution.Reason.INVOCATION_NOT_BLOCKED)
    }
    val alias = StandalonePhaseIncidentAlias.create(record.invocationId, record.currentStep)
    val incident =
      unit.blockIncidents.findByAlias(alias)
        ?: return unavailable(BlockIncidentLiveReferenceResolution.Reason.ACCEPTED_INCIDENT_NOT_FOUND)
    if (!invocationIdentityMatches(record, alias, incident)) {
      return unavailable(BlockIncidentLiveReferenceResolution.Reason.INVOCATION_IDENTITY_MISMATCH)
    }
    val owner = unit.blockIncidents.findByAlias(alias)
    return if (owner?.occurrence?.occurrenceToken == incident.occurrence.occurrenceToken) {
      BlockIncidentLiveReferenceResolution.Verified(incident.occurrence.occurrenceToken)
    } else {
      unavailable(BlockIncidentLiveReferenceResolution.Reason.ACCEPTED_REFERENCE_MISMATCH)
    }
  }

  private fun invocationIdentityMatches(
    record: StandalonePhaseStatusRecord,
    alias: BlockIncidentAlias,
    incident: BlockIncident,
  ): Boolean {
    val occurrence = incident.occurrence
    val aliasMatches =
      alias in incident.aliases &&
        StandalonePhaseIncidentAlias.belongsToInvocation(alias, record.invocationId)
    val coordinatesMatch =
      if (occurrence.workflowId != null) {
        occurrence.workflowId == record.workflowId
      } else {
        (occurrence.invocationId == null || occurrence.invocationId == record.invocationId) &&
          executionIdentityMatches(record, incident)
      }
    return aliasMatches && coordinatesMatch
  }

  private fun executionIdentityMatches(
    record: StandalonePhaseStatusRecord,
    incident: BlockIncident,
  ): Boolean {
    val occurrence = incident.occurrence
    return (occurrence.executionId == null || occurrence.executionId == record.executionId) &&
      (occurrence.phaseId == null || occurrence.phaseId == record.currentStep)
  }

  private fun resolveWorkflow(
    unit: UnitOfWork,
    workflowId: String,
  ): BlockIncidentLiveReferenceResolution {
    val workflow =
      unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
        ?: return unavailable(BlockIncidentLiveReferenceResolution.Reason.WORKFLOW_NOT_FOUND)
    if (workflow.workflowStatus != WorkflowStatus.BLOCKED) return BlockIncidentLiveReferenceResolution.None
    val step =
      workflow.steps.firstOrNull { it.stepId == workflow.currentStepId }
        ?.takeIf { it.status == WorkflowStepStatus.BLOCKED }
        ?: return unavailable(BlockIncidentLiveReferenceResolution.Reason.BLOCKED_STEP_UNAVAILABLE)
    return acceptedWorkflow(unit, workflow, step.attemptCount)
  }

  private fun acceptedWorkflow(
    unit: UnitOfWork,
    workflow: WorkflowStateSnapshot,
    attemptCount: Int,
  ): BlockIncidentLiveReferenceResolution {
    val reference =
      BlockIncidentIdentity.acceptedFor(
        workflow.artifacts,
        workflow.steps,
        workflow.currentStepId,
        workflow.workflowId,
      ) ?: workflow.artifacts.goalContinuationOutcomeArtifact()?.acceptedBlockReference
        ?.takeIf { it.sourceWorkflowId == workflow.workflowId && it.blockedStepId == workflow.currentStepId }
        ?: return unavailable(BlockIncidentLiveReferenceResolution.Reason.ACCEPTED_REFERENCE_UNAVAILABLE)
    val attemptMatches =
      reference.attempt == null ||
        BlockIncidentIdentity.matchesBlockedAttempt(
          workflow.artifacts,
          reference.blockedStepId,
          reference.attempt,
          attemptCount,
        )
    if (!attemptMatches || !workerGenerationMatches(unit, workflow, reference)) {
      return unavailable(BlockIncidentLiveReferenceResolution.Reason.ACCEPTED_REFERENCE_MISMATCH)
    }
    val incident =
      unit.blockIncidents.find(reference.incidentToken)
        ?: return unavailable(BlockIncidentLiveReferenceResolution.Reason.ACCEPTED_INCIDENT_NOT_FOUND)
    BlockIncidentAcceptedReferenceResolver.validate(reference, unit.blockIncidents)
    return resolveBindings(unit, workflow, reference, incident)
  }

  private fun workerGenerationMatches(
    unit: UnitOfWork,
    workflow: WorkflowStateSnapshot,
    reference: GoalRunnerAcceptedBlockReference,
  ): Boolean {
    val observed =
      unit.workflowStates.getLatestFeatureTaskRuntimeWorkerGeneration(workflow.workflowId)
        ?.takeIf { it.phaseId == workflow.currentStepId && it.phaseAttempt == reference.attempt }
    return observed == null || observed.generation == reference.executionGeneration
  }

  private fun resolveBindings(
    unit: UnitOfWork,
    workflow: WorkflowStateSnapshot,
    reference: GoalRunnerAcceptedBlockReference,
    incident: BlockIncident,
  ): BlockIncidentLiveReferenceResolution {
    val identity = BlockIncidentIdentity.fromArtifacts(workflow.artifacts)
    return when {
      identity != null && !acceptedIdentityMatches(identity, reference, incident) ->
        unavailable(BlockIncidentLiveReferenceResolution.Reason.ACCEPTED_REFERENCE_MISMATCH)
      reference.childIncidentToken != null && !childBindingMatches(unit, workflow, reference, incident) ->
        unavailable(BlockIncidentLiveReferenceResolution.Reason.CHILD_BINDING_UNAVAILABLE)
      reference.childIncidentToken == null && !directOccurrenceMatches(workflow, reference, incident) ->
        unavailable(BlockIncidentLiveReferenceResolution.Reason.ACCEPTED_REFERENCE_MISMATCH)
      incident.occurrence.parentWorkflowId?.let { !parentBindingMatches(unit, it, workflow, incident) } == true ->
        unavailable(BlockIncidentLiveReferenceResolution.Reason.CHILD_BINDING_UNAVAILABLE)
      else -> BlockIncidentLiveReferenceResolution.Verified(incident.occurrence.occurrenceToken)
    }
  }

  private fun acceptedIdentityMatches(
    identity: BlockIncidentIdentity,
    reference: GoalRunnerAcceptedBlockReference,
    incident: BlockIncident,
  ): Boolean {
    val stepMatches =
      identity.incidentToken == reference.incidentToken &&
        identity.stepId == reference.blockedStepId && identity.attempt == reference.attempt
    val generationMatches =
      identity.generation == reference.executionGeneration &&
        identity.alias.value == reference.aliasValue
    val childMatches =
      reference.childIncidentToken == null ||
        identity.childWorkflowId == incident.occurrence.workflowId
    return stepMatches && generationMatches && childMatches
  }

  private fun directOccurrenceMatches(
    workflow: WorkflowStateSnapshot,
    reference: GoalRunnerAcceptedBlockReference,
    incident: BlockIncident,
  ): Boolean {
    val occurrence = incident.occurrence
    val stepMatches =
      occurrence.workflowId == workflow.workflowId &&
        occurrence.phaseId == workflow.currentStepId && occurrence.attempt == reference.attempt
    val generationMatches =
      reference.executionGeneration == null ||
        occurrence.generation == reference.executionGeneration
    return stepMatches && generationMatches
  }

  private fun parentBindingMatches(
    unit: UnitOfWork,
    parentWorkflowId: String,
    child: WorkflowStateSnapshot,
    incident: BlockIncident,
  ): Boolean {
    val parent =
      unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)
        ?.takeIf { it.workflowStatus == WorkflowStatus.BLOCKED }
        ?: return false
    val parentIdentity = BlockIncidentIdentity.fromArtifacts(parent.artifacts) ?: return false
    val parentReference =
      BlockIncidentIdentity.acceptedFor(
        parent.artifacts,
        parent.steps,
        parent.currentStepId,
        parent.workflowId,
      ) ?: return false
    BlockIncidentAcceptedReferenceResolver.validate(parentReference, unit.blockIncidents)
    return parentIdentity.childWorkflowId == child.workflowId &&
      parentReference.childIncidentToken == incident.occurrence.occurrenceToken &&
      childBindingMatches(unit, parent, parentReference, incident) &&
      child.workflowId == incident.occurrence.workflowId
  }

  private fun childBindingMatches(
    unit: UnitOfWork,
    parent: WorkflowStateSnapshot,
    parentReference: GoalRunnerAcceptedBlockReference,
    incident: BlockIncident,
  ): Boolean {
    val childWorkflowId = incident.occurrence.workflowId ?: return false
    if (parentReference.childIncidentToken != incident.occurrence.occurrenceToken ||
      incident.occurrence.parentWorkflowId != parent.workflowId
    ) {
      return false
    }
    val child =
      unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, childWorkflowId)
        ?.takeIf { it.workflowStatus == WorkflowStatus.BLOCKED && it.currentStepId == incident.occurrence.phaseId }
        ?: return false
    return childReferenceMatches(unit, child, incident) && continuationMatches(parent, child, incident)
  }

  private fun childReferenceMatches(
    unit: UnitOfWork,
    child: WorkflowStateSnapshot,
    incident: BlockIncident,
  ): Boolean {
    val reference =
      BlockIncidentIdentity.acceptedFor(
        child.artifacts,
        child.steps,
        child.currentStepId,
        child.workflowId,
      ) ?: return false
    BlockIncidentAcceptedReferenceResolver.validate(reference, unit.blockIncidents)
    val occurrence = incident.occurrence
    val identityMatches =
      reference.incidentToken == occurrence.occurrenceToken &&
        reference.attempt == occurrence.attempt &&
        reference.executionGeneration == occurrence.generation
    return identityMatches && workerGenerationMatches(unit, child, reference)
  }

  private fun continuationMatches(
    parent: WorkflowStateSnapshot,
    child: WorkflowStateSnapshot,
    incident: BlockIncident,
  ): Boolean {
    val continuation = child.artifacts.goalContinuationArtifact() ?: return false
    val outcome = child.artifacts.goalContinuationOutcomeArtifact() ?: return false
    val manifest = parent.artifacts.decompositionRuntime() ?: return false
    val subtask = manifest.subtasks.singleOrNull { it.id == continuation.subtaskId }
    val outcomeMatches =
      outcome.workflowId == child.workflowId && outcome.status == GoalRunnerTerminalStatus.BLOCKED &&
        outcome.acceptedBlockReference?.incidentToken == incident.occurrence.occurrenceToken
    val manifestMatches =
      manifest.currentSubtaskIntent.subtaskId == continuation.subtaskId &&
        subtask?.workflowId == child.workflowId && subtask.status == DecompositionStatus.BLOCKED.wireValue
    return continuation.parentWorkflowId == parent.workflowId && outcomeMatches && manifestMatches
  }

  private fun unavailable(reason: BlockIncidentLiveReferenceResolution.Reason) =
    BlockIncidentLiveReferenceResolution.Unavailable(reason)
}
