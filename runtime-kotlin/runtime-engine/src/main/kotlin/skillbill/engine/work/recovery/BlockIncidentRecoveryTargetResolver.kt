package skillbill.engine.work.recovery

import skillbill.contracts.JsonCodec
import skillbill.engine.diagnostics.block.BlockIncidentAcceptedReferenceResolver
import skillbill.engine.diagnostics.block.BlockIncidentIdentity
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.goalrunner.goalContinuationOutcomeArtifact
import skillbill.goalrunner.model.FeatureTaskRuntimeGoalContinuationOutcome
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.FeatureTaskRuntimeExecutionPlanValidator
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

internal object BlockIncidentRecoveryTargetResolver {
  fun resolve(
    unit: UnitOfWork,
    incident: BlockIncident,
    executionPlanValidator: FeatureTaskRuntimeExecutionPlanValidator,
  ): BlockIncidentRecoveryTargetResolution {
    val missing = coordinateFailure(incident)
    if (missing != null) return unavailable(missing)
    val workflowId = requireNotNull(incident.occurrence.workflowId)
    val workflow =
      unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
        ?: return unavailable(BlockIncidentRecoveryTargetFailure.MISSING_WORKFLOW)
    return if (workflow.workflowStatus != WorkflowStatus.BLOCKED) {
      unavailable(BlockIncidentRecoveryTargetFailure.WORKFLOW_NOT_BLOCKED)
    } else {
      resolveWorkflow(unit, incident, workflow, executionPlanValidator)
    }
  }

  private fun coordinateFailure(incident: BlockIncident): BlockIncidentRecoveryTargetFailure? =
    when {
      incident.occurrence.workflowId == null -> BlockIncidentRecoveryTargetFailure.MISSING_WORKFLOW
      incident.occurrence.phaseId == null -> BlockIncidentRecoveryTargetFailure.MISSING_PHASE
      incident.occurrence.generation == null -> BlockIncidentRecoveryTargetFailure.MISSING_GENERATION_OBSERVATION
      incident.occurrence.origin != BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK &&
        incident.occurrence.origin != BlockIncidentOrigin.ACCEPTED_GOAL_BLOCK ->
        BlockIncidentRecoveryTargetFailure.UNVERIFIED_PARENT_LINK
      else -> null
    }

  private fun resolveWorkflow(
    unit: UnitOfWork,
    incident: BlockIncident,
    workflow: WorkflowStateSnapshot,
    validator: FeatureTaskRuntimeExecutionPlanValidator,
  ): BlockIncidentRecoveryTargetResolution {
    val identity =
      unit.workflowStates.getFeatureTaskExecutionIdentity(workflow.workflowId)
        ?: return unavailable(BlockIncidentRecoveryTargetFailure.MISSING_IDENTITY)
    FeatureTaskExecutionIdentityPolicy.validate(identity)
    if (identity.workflowId != workflow.workflowId || identity.mode != FeatureTaskWorkflowMode.RUNTIME) {
      throw invalidWorkflowStateSchemaError("Recovery target has an incompatible feature-task identity.")
    }
    val blockIdentity =
      BlockIncidentIdentity.fromArtifacts(workflow.artifacts)
        ?: return unavailable(BlockIncidentRecoveryTargetFailure.MISSING_BLOCK_IDENTITY)
    val evidence = WorkflowEvidence(incident, workflow, identity, blockIdentity)
    val failure = acceptedPhaseFailure(unit, evidence)
    return if (failure != null) unavailable(failure) else resolvePlanAndTarget(unit, evidence, validator)
  }

  private fun acceptedPhaseFailure(
    unit: UnitOfWork,
    evidence: WorkflowEvidence,
  ): BlockIncidentRecoveryTargetFailure? {
    val workflow = evidence.workflow
    val occurrence = evidence.incident.occurrence
    val phase =
      decodePhaseRecords(workflow.artifacts)[occurrence.phaseId]
        ?: return BlockIncidentRecoveryTargetFailure.MISSING_PHASE_RECORD
    val step =
      occurrence.phaseId?.let(workflow::step)
        ?: return BlockIncidentRecoveryTargetFailure.MISSING_WORKFLOW_STEP
    return when {
      !matchesIncident(evidence) || !aliasesOwned(unit, evidence) ->
        BlockIncidentRecoveryTargetFailure.INCIDENT_IDENTITY_MISMATCH
      !matchesPhase(evidence, phase, step) -> BlockIncidentRecoveryTargetFailure.OCCURRENCE_CHANGED
      else -> null
    }
  }

  private fun matchesIncident(evidence: WorkflowEvidence): Boolean {
    val block = evidence.blockIdentity
    val incident = evidence.incident
    val linked =
      block.childWorkflowId == null && block.childIncidentToken == null &&
        block.sourceAlias?.let { it !in incident.aliases } != true
    return linked &&
      block.incidentToken == incident.occurrence.occurrenceToken &&
      block.alias in incident.aliases &&
      block.failureCode == incident.occurrence.failureCode &&
      block.failureDisposition?.wireValue == incident.occurrence.failureDisposition
  }

  private fun aliasesOwned(
    unit: UnitOfWork,
    evidence: WorkflowEvidence,
  ): Boolean {
    val block = evidence.blockIdentity
    val token = evidence.incident.occurrence.occurrenceToken
    val primary = unit.blockIncidents.findByAlias(block.alias)?.occurrence?.occurrenceToken == token
    val source = block.sourceAlias?.let { unit.blockIncidents.findByAlias(it)?.occurrence?.occurrenceToken == token }
    return primary && source != false
  }

  private fun matchesPhase(
    evidence: WorkflowEvidence,
    phase: FeatureTaskRuntimePhaseRecord,
    step: WorkflowStepState,
  ): Boolean {
    val occurrence = evidence.incident.occurrence
    val block = evidence.blockIdentity
    val statusMatches =
      evidence.workflow.currentStepId == occurrence.phaseId &&
        phase.status == WorkflowStepStatus.BLOCKED && step.status == WorkflowStepStatus.BLOCKED
    val attemptMatches =
      phase.attemptCount == step.attemptCount &&
        phase.attemptCount == occurrence.attempt &&
        block.attempt == occurrence.attempt
    return statusMatches && attemptMatches &&
      block.stepId == occurrence.phaseId && block.generation == occurrence.generation
  }

  private fun resolvePlanAndTarget(
    unit: UnitOfWork,
    evidence: WorkflowEvidence,
    validator: FeatureTaskRuntimeExecutionPlanValidator,
  ): BlockIncidentRecoveryTargetResolution {
    if (!parentBindingMatches(
        unit,
        evidence,
      )
    ) {
      return unavailable(BlockIncidentRecoveryTargetFailure.UNVERIFIED_PARENT_LINK)
    }
    if (!workerCoordinatesMatch(
        unit,
        evidence,
      )
    ) {
      return unavailable(BlockIncidentRecoveryTargetFailure.OCCURRENCE_CHANGED)
    }
    val stored =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(evidence.workflow.artifacts)
        ?: return unavailable(BlockIncidentRecoveryTargetFailure.MISSING_EXECUTION_PLAN)
    val plan =
      ValidatedFeatureTaskRuntimeExecutionPlan.read(
        JsonCodec.valueToJsonString(stored).toByteArray(Charsets.UTF_8),
        validator,
      )
    return when (evidence.identity.routeScope) {
      FeatureTaskRouteScope.STANDALONE -> resolveStandalone(unit, evidence, plan)
      FeatureTaskRouteScope.GOAL_CHILD ->
        BlockIncidentRecoveryTargetResolution.Resolved(
          BlockIncidentRecoveryTarget(
            evidence.workflow.workflowId,
            null,
            requireNotNull(evidence.incident.occurrence.phaseId),
            evidence.identity,
            plan,
            evidence.workflow.workflowId,
            requireNotNull(evidence.incident.occurrence.parentWorkflowId),
          ),
        )
    }
  }

  private fun parentBindingMatches(
    unit: UnitOfWork,
    evidence: WorkflowEvidence,
  ): Boolean =
    when (evidence.identity.routeScope) {
      FeatureTaskRouteScope.GOAL_CHILD ->
        !evidence.incident.occurrence.parentWorkflowId.isNullOrBlank() &&
          hasCurrentAcceptedParentBinding(unit, evidence)
      FeatureTaskRouteScope.STANDALONE ->
        evidence.incident.occurrence.origin == BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK &&
          evidence.incident.occurrence.parentWorkflowId == null
    }

  private fun workerCoordinatesMatch(
    unit: UnitOfWork,
    evidence: WorkflowEvidence,
  ): Boolean {
    val id = evidence.workflow.workflowId
    val occurrence = evidence.incident.occurrence
    val observed = unit.workflowStates.getLatestFeatureTaskRuntimeWorkerGeneration(id) ?: return false
    val worker = unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(id)
    val observedMatches =
      observed.generation == occurrence.generation &&
        observed.phaseId == occurrence.phaseId &&
        observed.phaseAttempt == occurrence.attempt
    val workerMatches =
      worker == null || worker.generation == occurrence.generation &&
        worker.phaseId == occurrence.phaseId && worker.phaseAttempt == occurrence.attempt
    return observedMatches && workerMatches
  }

  private fun resolveStandalone(
    unit: UnitOfWork,
    evidence: WorkflowEvidence,
    plan: ValidatedFeatureTaskRuntimeExecutionPlan,
  ): BlockIncidentRecoveryTargetResolution {
    val phaseId = requireNotNull(evidence.incident.occurrence.phaseId)
    val binding =
      standaloneInvocationBinding(unit, evidence.incident, evidence.workflow.workflowId, phaseId)
        ?: return unavailable(BlockIncidentRecoveryTargetFailure.MISSING_INVOCATION)
    return if (!invocationMatches(binding, evidence)) {
      unavailable(BlockIncidentRecoveryTargetFailure.INVOCATION_MISMATCH)
    } else {
      BlockIncidentRecoveryTargetResolution.Resolved(
        BlockIncidentRecoveryTarget(
          evidence.workflow.workflowId,
          binding.invocationId,
          phaseId,
          evidence.identity,
          plan,
          evidence.workflow.workflowId,
        ),
      )
    }
  }

  private fun invocationMatches(
    binding: StandaloneInvocationBinding,
    evidence: WorkflowEvidence,
  ): Boolean {
    val invocation = binding.record
    val occurrence = evidence.incident.occurrence
    val targetMatches =
      invocation.workflowId == evidence.workflow.workflowId &&
        invocation.phaseId == occurrence.phaseId &&
        invocation.launchScope == IdeStatusLaunchScope.STANDALONE
    val identityMatches =
      invocation.repositoryIdentity == evidence.identity.repositoryIdentity &&
        invocation.issueKey?.let(
          FeatureTaskExecutionIdentityPolicy::canonicalIssueKey,
        ) == evidence.identity.normalizedIssueKey
    val executionMatches =
      if (occurrence.executionId != null) {
        invocation.executionId == occurrence.executionId
      } else {
        binding.linkedFromAlias
      }
    return targetMatches && identityMatches && executionMatches
  }

  private data class WorkflowEvidence(
    val incident: BlockIncident,
    val workflow: WorkflowStateSnapshot,
    val identity: FeatureTaskExecutionIdentity,
    val blockIdentity: BlockIncidentIdentity,
  )

  private fun standaloneInvocationBinding(
    unit: UnitOfWork,
    incident: BlockIncident,
    workflowId: String,
    phaseId: String,
  ): StandaloneInvocationBinding? {
    val recordedId = incident.occurrence.invocationId?.takeIf(String::isNotBlank)
    if (recordedId != null) {
      val record = unit.standalonePhaseStatuses.readByInvocation(recordedId) ?: return null
      return StandaloneInvocationBinding(recordedId, record, linkedFromAlias = false)
    }
    val suffix = ":$phaseId"
    return incident.aliases.asSequence()
      .filter { it.kind == BlockIncidentAliasKind.PHASE_TRANSITION }
      .filter { alias ->
        unit.blockIncidents.findByAlias(alias)?.occurrence?.occurrenceToken == incident.occurrence.occurrenceToken
      }
      .mapNotNull { alias ->
        alias.value.takeIf { it.endsWith(suffix) }
          ?.removeSuffix(suffix)
          ?.takeIf(String::isNotBlank)
      }
      .distinct()
      .mapNotNull { invocationId ->
        unit.standalonePhaseStatuses.readByInvocation(invocationId)
          ?.takeIf { it.workflowId == workflowId && it.phaseId == phaseId }
          ?.let { invocationId to it }
      }
      .toList()
      .singleOrNull()
      ?.let { (invocationId, record) -> StandaloneInvocationBinding(invocationId, record, linkedFromAlias = true) }
  }

  private data class StandaloneInvocationBinding(
    val invocationId: String,
    val record: StandalonePhaseStatusRecord,
    val linkedFromAlias: Boolean,
  )

  private fun hasCurrentAcceptedParentBinding(
    unit: UnitOfWork,
    evidence: WorkflowEvidence,
  ): Boolean {
    val parent =
      unit.workflowStates.get(
        WorkflowFamily.TASK_RUNTIME,
        requireNotNull(evidence.incident.occurrence.parentWorkflowId),
      )
        ?.takeIf { it.workflowStatus == WorkflowStatus.BLOCKED } ?: return false
    val continuation = evidence.workflow.artifacts.goalContinuationArtifact() ?: return false
    val outcome = evidence.workflow.artifacts.goalContinuationOutcomeArtifact() ?: return false
    return parentManifestMatches(parent, evidence, continuation, outcome) &&
      referenceMatches(unit, evidence, outcome)
  }

  private fun parentManifestMatches(
    parent: WorkflowStateSnapshot,
    evidence: WorkflowEvidence,
    continuation: FeatureTaskRuntimeGoalContinuationArtifact,
    outcome: FeatureTaskRuntimeGoalContinuationOutcome,
  ): Boolean {
    val manifest = parent.artifacts.decompositionRuntime() ?: return false
    val subtask = manifest.subtasks.singleOrNull { it.id == continuation.subtaskId } ?: return false
    val continuationMatches =
      continuation.parentWorkflowId == parent.workflowId &&
        continuation.issueKey == manifest.issueKey &&
        FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(
          continuation.issueKey,
        ) == evidence.identity.normalizedIssueKey
    val outcomeMatches =
      outcome.status == GoalRunnerTerminalStatus.BLOCKED &&
        outcome.workflowId == evidence.workflow.workflowId && outcome.issueKey == continuation.issueKey &&
        outcome.subtaskId == continuation.subtaskId
    val subtaskMatches =
      manifest.currentSubtaskIntent.subtaskId == continuation.subtaskId &&
        subtask.workflowId == evidence.workflow.workflowId && subtask.branch == continuation.goalBranch &&
        subtask.status == DecompositionStatus.BLOCKED.wireValue
    return continuationMatches && outcomeMatches && subtaskMatches
  }

  private fun referenceMatches(
    unit: UnitOfWork,
    evidence: WorkflowEvidence,
    outcome: FeatureTaskRuntimeGoalContinuationOutcome,
  ): Boolean {
    val reference = outcome.acceptedBlockReference ?: return false
    if (!referenceIdentityMatches(reference, evidence) ||
      !referenceAliasesMatch(reference, evidence.incident)
    ) {
      return false
    }
    BlockIncidentAcceptedReferenceResolver.validate(reference, unit.blockIncidents)
    return true
  }

  private fun referenceIdentityMatches(
    reference: GoalRunnerAcceptedBlockReference,
    evidence: WorkflowEvidence,
  ): Boolean {
    val identity = evidence.blockIdentity
    return reference.sourceWorkflowId == evidence.workflow.workflowId &&
      reference.incidentToken == identity.incidentToken &&
      reference.blockedStepId == identity.stepId && reference.attempt == identity.attempt &&
      reference.executionGeneration == identity.generation &&
      reference.aliasKind == identity.alias.kind.wireValue &&
      reference.aliasValue == identity.alias.value &&
      reference.childIncidentToken == identity.childIncidentToken
  }

  private fun referenceAliasesMatch(
    reference: GoalRunnerAcceptedBlockReference,
    incident: BlockIncident,
  ): Boolean {
    val aliasMatches =
      incident.aliases.any {
        it.kind.wireValue == reference.aliasKind && it.value == reference.aliasValue
      }
    val sourceMatches =
      reference.sourceAliasKind == null && reference.sourceAliasValue == null ||
        incident.aliases.any {
          it.kind.wireValue == reference.sourceAliasKind && it.value == reference.sourceAliasValue
        }
    return reference.incidentToken == incident.occurrence.occurrenceToken && aliasMatches && sourceMatches
  }

  private fun unavailable(failure: BlockIncidentRecoveryTargetFailure) =
    BlockIncidentRecoveryTargetResolution.Unavailable(failure)
}

internal data class BlockIncidentRecoveryTarget(
  val workflowId: String,
  val invocationId: String?,
  val phaseId: String,
  val executionIdentity: FeatureTaskExecutionIdentity,
  val expectedExecutionPlan: ValidatedFeatureTaskRuntimeExecutionPlan,
  val mutationWorkflowId: String,
  val launchWorkflowId: String = workflowId,
)

internal sealed interface BlockIncidentRecoveryTargetResolution {
  data class Resolved(val target: BlockIncidentRecoveryTarget) : BlockIncidentRecoveryTargetResolution

  data class Unavailable(val failure: BlockIncidentRecoveryTargetFailure) : BlockIncidentRecoveryTargetResolution
}

internal enum class BlockIncidentRecoveryTargetFailure {
  MISSING_WORKFLOW,
  MISSING_PHASE,
  MISSING_INVOCATION,
  MISSING_IDENTITY,
  MISSING_BLOCK_IDENTITY,
  MISSING_PHASE_RECORD,
  MISSING_WORKFLOW_STEP,
  MISSING_EXECUTION_PLAN,
  MISSING_GENERATION_OBSERVATION,
  WORKER_NOT_ACTIVE,
  WORKFLOW_NOT_BLOCKED,
  OCCURRENCE_CHANGED,
  INCIDENT_IDENTITY_MISMATCH,
  INVOCATION_MISMATCH,
  UNVERIFIED_PARENT_LINK,
}
