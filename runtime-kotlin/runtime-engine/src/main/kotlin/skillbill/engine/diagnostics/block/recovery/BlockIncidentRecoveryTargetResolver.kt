package skillbill.engine.diagnostics.block.recovery

import skillbill.contracts.JsonCodec
import skillbill.engine.diagnostics.block.BlockIncidentAcceptedReferenceResolver
import skillbill.engine.diagnostics.block.BlockIncidentIdentity
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.goalContinuationOutcomeArtifact
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.taskruntime.FeatureTaskRuntimeExecutionPlanValidator
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
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
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact

internal object BlockIncidentRecoveryTargetResolver {
  fun resolve(
    unit: UnitOfWork,
    incident: BlockIncident,
    executionPlanValidator: FeatureTaskRuntimeExecutionPlanValidator,
  ): BlockIncidentRecoveryTargetResolution {
    val occurrence = incident.occurrence
    val workflowId = occurrence.workflowId ?: return unavailable(BlockIncidentRecoveryTargetFailure.MISSING_WORKFLOW)
    val phaseId = occurrence.phaseId ?: return unavailable(BlockIncidentRecoveryTargetFailure.MISSING_PHASE)
    val generation = occurrence.generation
      ?: return unavailable(BlockIncidentRecoveryTargetFailure.MISSING_GENERATION_OBSERVATION)
    if (occurrence.origin != BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK &&
      occurrence.origin != BlockIncidentOrigin.ACCEPTED_GOAL_BLOCK
    ) {
      return unavailable(BlockIncidentRecoveryTargetFailure.UNVERIFIED_PARENT_LINK)
    }
    val workflow = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
      ?: return unavailable(BlockIncidentRecoveryTargetFailure.MISSING_WORKFLOW)
    if (workflow.workflowStatus != WorkflowStatus.BLOCKED) {
      return unavailable(BlockIncidentRecoveryTargetFailure.WORKFLOW_NOT_BLOCKED)
    }
    val identity = unit.workflowStates.getFeatureTaskExecutionIdentity(workflowId)
      ?: return unavailable(BlockIncidentRecoveryTargetFailure.MISSING_IDENTITY)
    FeatureTaskExecutionIdentityPolicy.validate(identity)
    if (identity.workflowId != workflowId || identity.mode != FeatureTaskWorkflowMode.RUNTIME) {
      throw invalidWorkflowStateSchemaError("Recovery target has an incompatible feature-task identity.")
    }
    val route = identity.routeScope
    val blockIdentity = BlockIncidentIdentity.fromArtifacts(workflow.artifacts)
      ?: return unavailable(BlockIncidentRecoveryTargetFailure.MISSING_BLOCK_IDENTITY)
    if (blockIdentity.incidentToken != occurrence.occurrenceToken || blockIdentity.alias !in incident.aliases ||
      blockIdentity.childWorkflowId != null || blockIdentity.childIncidentToken != null ||
      blockIdentity.sourceAlias?.let { it !in incident.aliases } == true ||
      blockIdentity.failureCode != occurrence.failureCode ||
      blockIdentity.failureDisposition?.wireValue != occurrence.failureDisposition
    ) {
      return unavailable(BlockIncidentRecoveryTargetFailure.INCIDENT_IDENTITY_MISMATCH)
    }
    val identityAliasOwner = unit.blockIncidents.findByAlias(blockIdentity.alias)
    val sourceAliasOwner = blockIdentity.sourceAlias?.let(unit.blockIncidents::findByAlias)
    if (identityAliasOwner?.occurrence?.occurrenceToken != occurrence.occurrenceToken ||
      blockIdentity.sourceAlias != null && sourceAliasOwner?.occurrence?.occurrenceToken != occurrence.occurrenceToken
    ) {
      return unavailable(BlockIncidentRecoveryTargetFailure.INCIDENT_IDENTITY_MISMATCH)
    }
    val phase = decodePhaseRecords(workflow.artifacts)[phaseId]
      ?: return unavailable(BlockIncidentRecoveryTargetFailure.MISSING_PHASE_RECORD)
    val step = workflow.steps.firstOrNull { it.stepId == phaseId }
      ?: return unavailable(BlockIncidentRecoveryTargetFailure.MISSING_WORKFLOW_STEP)
    if (workflow.currentStepId != phaseId || phase.status != WorkflowStepStatus.BLOCKED ||
      step.status != WorkflowStepStatus.BLOCKED ||
      phase.attemptCount != step.attemptCount || blockIdentity.stepId != phaseId ||
      blockIdentity.attempt != occurrence.attempt || blockIdentity.generation != generation ||
      phase.attemptCount != occurrence.attempt
    ) {
      return unavailable(BlockIncidentRecoveryTargetFailure.OCCURRENCE_CHANGED)
    }
    val parentWorkflowId = occurrence.parentWorkflowId
    if (route == FeatureTaskRouteScope.GOAL_CHILD) {
      if (parentWorkflowId.isNullOrBlank() ||
        !hasCurrentAcceptedParentBinding(unit, incident, parentWorkflowId, workflowId, blockIdentity)
      ) {
        return unavailable(BlockIncidentRecoveryTargetFailure.UNVERIFIED_PARENT_LINK)
      }
    } else if (route != FeatureTaskRouteScope.STANDALONE ||
      occurrence.origin != BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK || parentWorkflowId != null
    ) {
      return unavailable(BlockIncidentRecoveryTargetFailure.UNVERIFIED_PARENT_LINK)
    }
    val workerOwnership = unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId)
    val generationObservation = unit.workflowStates.getLatestFeatureTaskRuntimeWorkerGeneration(workflowId)
      ?: return unavailable(BlockIncidentRecoveryTargetFailure.MISSING_GENERATION_OBSERVATION)
    if (generationObservation.generation != generation || generationObservation.phaseId != phaseId ||
      generationObservation.phaseAttempt != occurrence.attempt
    ) {
      return unavailable(BlockIncidentRecoveryTargetFailure.OCCURRENCE_CHANGED)
    }
    if (workerOwnership != null) {
      if (workerOwnership.generation != generation || workerOwnership.phaseId != phaseId ||
        workerOwnership.phaseAttempt != occurrence.attempt
      ) {
        return unavailable(BlockIncidentRecoveryTargetFailure.OCCURRENCE_CHANGED)
      }
    }
    val storedExecutionPlan =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(workflow.artifacts)
        ?: return unavailable(BlockIncidentRecoveryTargetFailure.MISSING_EXECUTION_PLAN)
    val expectedExecutionPlan =
      ValidatedFeatureTaskRuntimeExecutionPlan.read(
        JsonCodec.valueToJsonString(storedExecutionPlan).toByteArray(Charsets.UTF_8),
        executionPlanValidator,
      )
    return when (route) {
      FeatureTaskRouteScope.STANDALONE -> {
        val invocationBinding = standaloneInvocationBinding(unit, incident, workflowId, phaseId)
          ?: return unavailable(BlockIncidentRecoveryTargetFailure.MISSING_INVOCATION)
        val invocationId = invocationBinding.invocationId
        val invocation = invocationBinding.record
        if (invocation.workflowId != workflowId || invocation.phaseId != phaseId ||
          invocation.launchScope != IdeStatusLaunchScope.STANDALONE ||
          invocation.repositoryIdentity != identity.repositoryIdentity ||
          invocation.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey) != identity.normalizedIssueKey ||
          (occurrence.executionId != null && invocation.executionId != occurrence.executionId) ||
          (occurrence.executionId == null && !invocationBinding.linkedFromAlias)
        ) {
          return unavailable(BlockIncidentRecoveryTargetFailure.INVOCATION_MISMATCH)
        }
        BlockIncidentRecoveryTargetResolution.Resolved(
          BlockIncidentRecoveryTarget(workflowId, invocationId, phaseId, identity, expectedExecutionPlan, workflowId),
        )
      }
      FeatureTaskRouteScope.GOAL_CHILD -> BlockIncidentRecoveryTargetResolution.Resolved(
        BlockIncidentRecoveryTarget(
          workflowId = workflowId,
          invocationId = null,
          phaseId = phaseId,
          executionIdentity = identity,
          expectedExecutionPlan = expectedExecutionPlan,
          mutationWorkflowId = workflowId,
          launchWorkflowId = parentWorkflowId.orEmpty(),
        ),
      )
    }
  }

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
    val linked = incident.aliases.asSequence()
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
    incident: BlockIncident,
    parentWorkflowId: String,
    childWorkflowId: String,
    childIdentity: BlockIncidentIdentity,
  ): Boolean {
    val parent = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId) ?: return false
    if (parent.workflowStatus != WorkflowStatus.BLOCKED) return false
    val child = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, childWorkflowId) ?: return false
    val continuation = child.artifacts.goalContinuationArtifact() ?: return false
    val outcome = child.artifacts.goalContinuationOutcomeArtifact() ?: return false
    val manifest = parent.artifacts.decompositionRuntime() ?: return false
    val subtask = manifest.subtasks.singleOrNull { it.id == continuation.subtaskId } ?: return false
    if (continuation.parentWorkflowId != parentWorkflowId || continuation.issueKey != manifest.issueKey ||
      FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(continuation.issueKey) != childIdentity.normalizedIssueKey ||
      outcome.status != GoalRunnerTerminalStatus.BLOCKED || outcome.workflowId != childWorkflowId ||
      outcome.issueKey != continuation.issueKey || outcome.subtaskId != continuation.subtaskId ||
      manifest.currentSubtaskIntent.subtaskId != continuation.subtaskId || subtask.workflowId != childWorkflowId ||
      subtask.branch != continuation.goalBranch || subtask.status != DecompositionStatus.BLOCKED.wireValue
    ) {
      return false
    }
    val reference = outcome.acceptedBlockReference ?: return false
    if (reference.sourceWorkflowId != childWorkflowId || reference.incidentToken != childIdentity.incidentToken ||
      reference.blockedStepId != childIdentity.stepId || reference.attempt != childIdentity.attempt ||
      reference.executionGeneration != childIdentity.generation || reference.aliasKind != childIdentity.alias.kind.wireValue ||
      reference.aliasValue != childIdentity.alias.value || reference.childIncidentToken != childIdentity.childIncidentToken
    ) {
      return false
    }
    val referenceAliasMatches = incident.aliases.any {
      it.kind.wireValue == reference.aliasKind && it.value == reference.aliasValue
    }
    val referenceSourceAliasMatches =
      reference.sourceAliasKind == null && reference.sourceAliasValue == null || incident.aliases.any {
        it.kind.wireValue == reference.sourceAliasKind && it.value == reference.sourceAliasValue
      }
    if (reference.incidentToken != incident.occurrence.occurrenceToken ||
      !referenceAliasMatches || !referenceSourceAliasMatches
    ) {
      return false
    }
    BlockIncidentAcceptedReferenceResolver.validate(reference, unit.blockIncidents)
    return true
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
