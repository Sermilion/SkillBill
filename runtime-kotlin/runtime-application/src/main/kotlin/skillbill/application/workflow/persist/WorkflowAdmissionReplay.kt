package skillbill.application.workflow.persist

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.model.PersistOpenedWorkflowArgs
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.contracts.JsonCodec
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.workflow.WorkflowAgentAssignmentsFailureCode
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.assignment.WorkflowAgentAssignmentsValidator
import skillbill.ports.taskruntime.executionplan.FeatureTaskRuntimeExecutionPlanValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.isTerminalStatus

@Inject
class WorkflowAdmissionReplay(
  private val executionPlanValidator: FeatureTaskRuntimeExecutionPlanValidator,
  private val agentAssignmentsValidator: WorkflowAgentAssignmentsValidator,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun replay(
    args: PersistOpenedWorkflowArgs,
    unitOfWork: UnitOfWork,
    engine: WorkflowEngine,
    family: WorkflowFamily,
  ): WorkflowOpenResult? {
    val existing = unitOfWork.workflowStates.get(family, args.workflowId) ?: return null
    val conflict =
      when {
        unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(args.workflowId) != args.executionIdentity ->
          "execution identity differs; preserve the existing workflow and use a new workflow id"
        existing.sessionId != args.effectiveSessionId ->
          "session identity differs; preserve the existing workflow and use a new workflow id"
        family.definition.isTerminalStatus(existing.workflowStatus) -> "the existing workflow is terminal"
        unitOfWork.workflowStates.getFeatureTaskRuntimeWorkerOwnership(args.workflowId)?.leaseState in
          setOf(FeatureTaskRuntimeWorkerLeaseState.ACTIVE, FeatureTaskRuntimeWorkerLeaseState.TAKEOVER_RESERVED) ->
          "a worker currently owns the existing workflow"
        else -> null
      }
    val refusal = conflict ?: planConflict(args, existing) ?: routingConflict(args, existing)
    if (refusal != null) return refuse(args.workflowId, refusal)
    val view = engine.snapshotView(family.definition, existing)
    val currentStep = view.steps.firstOrNull { it.stepId == args.stepId }
    val launchProjection =
      launchProjectionIfReady(
        engine,
        family.definition,
        view,
        ProjectionLaunchRequest(
          stepId = args.stepId,
          producerIteration = currentStep?.attemptCount ?: 0,
          repositoryCheckpointIdentity = args.repositoryCheckpointIdentity,
        ),
      )
    catchingNonCancellation { diagnostics.info("seam=workflow_open replay_reused workflow_id=${args.workflowId}") }
    return WorkflowOpenResult.Ok(args.workflowId, unitOfWork.dbPath.toString(), view, launchProjection)
  }

  private fun refuse(
    workflowId: String,
    reason: String,
  ): WorkflowOpenResult.Error {
    catchingNonCancellation { diagnostics.warning("seam=workflow_open replay_refused reason=$reason") }
    return WorkflowOpenResult.Error(workflowId, "Workflow '$workflowId' cannot reuse the existing admission: $reason.")
  }

  private fun planConflict(
    args: PersistOpenedWorkflowArgs,
    existing: WorkflowStateSnapshot,
  ): String? {
    val expectedPlan =
      args.executionPlan?.encoded()
        ?: return "the new admission has no execution plan"
    val retainedPlan =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(existing.artifacts)
        ?: return "the existing execution plan is missing"
    val retainedPlanBytes =
      executionPlanValidator.canonicalize(
        JsonCodec.valueToJsonString(retainedPlan).toByteArray(Charsets.UTF_8),
        "workflow ${args.workflowId} retained execution plan",
      )
    return if (expectedPlan.contentEquals(retainedPlanBytes)) {
      null
    } else {
      "execution plan differs; preserve the existing workflow and use a new workflow id"
    }
  }

  private fun routingConflict(
    args: PersistOpenedWorkflowArgs,
    existing: WorkflowStateSnapshot,
  ): String? {
    val routeKey = DurableWorkflowArtifactFamily.WORKFLOW_AGENT_ASSIGNMENTS.entry(null).first
    val candidateRouting =
      if (routeKey in args.initialArtifacts) {
        args.initialArtifacts[routeKey] as? String
          ?: invalidRetainedRouting(args.workflowId, "new routing evidence is not JSON text")
      } else {
        null
      }
    val hasRetainedRouting = DurableWorkflowArtifactFamily.WORKFLOW_AGENT_ASSIGNMENTS.contains(existing.artifacts)
    val retainedRouting =
      if (hasRetainedRouting) {
        DurableWorkflowArtifactFamily.WORKFLOW_AGENT_ASSIGNMENTS.value(existing.artifacts) as? String
          ?: invalidRetainedRouting(args.workflowId, "retained routing evidence is not JSON text")
      } else {
        null
      }
    val candidateBytes =
      candidateRouting?.let {
        agentAssignmentsValidator.canonicalize(
          it.toByteArray(Charsets.UTF_8),
          "workflow ${args.workflowId} admission routing",
        )
      }
    val retainedBytes =
      retainedRouting?.let {
        agentAssignmentsValidator.canonicalize(
          it.toByteArray(Charsets.UTF_8),
          "workflow ${args.workflowId} retained routing",
        )
      }
    return if (candidateBytes != null && retainedBytes != null && !candidateBytes.contentEquals(retainedBytes)) {
      "routing evidence differs; preserve the existing workflow and use a new workflow id"
    } else {
      null
    }
  }

  private fun invalidRetainedRouting(
    workflowId: String,
    reason: String,
  ): Nothing =
    throw SkillBillRuntimeException(
      WorkflowAgentAssignmentsFailureCode.INVALID_RECORD,
      "Workflow '$workflowId' has invalid routing evidence: $reason. " +
        "Keep the source record and repair it before retrying.",
    )
}
