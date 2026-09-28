package skillbill.engine.featuretask.lifecycle.execution

import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanAdmissionError
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.model.skeleton.RuntimeReviewSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

@Inject
class FeatureTaskRuntimeExecutionAdmission(
  private val compatibility: FeatureTaskRuntimeExecutionPlanCompatibility,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun admit(
    states: WorkflowStateRepository,
    workflowId: String,
    inputs: EffectiveGatePolicyInputs,
    expectedIdentity: FeatureTaskExecutionIdentity? = null,
    requestedReviewSelection: RuntimeReviewSelection? = null,
  ): AdmittedFeatureTaskRuntimeExecution = try {
    val identity = states.getFeatureTaskExecutionIdentity(workflowId)
      ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "missing immutable execution identity")
    FeatureTaskExecutionIdentityPolicy.validate(identity)
    val row = states.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
      ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "missing workflow")
    if (WorkflowStatus.fromWire(row.workflowStatus)?.let { it in WorkflowStatus.terminalStatuses } == true) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "terminal workflow cannot be admitted")
    }
    if (identity.workflowId != workflowId || identity.mode != FeatureTaskWorkflowMode.RUNTIME ||
      identity.normalizedIssueKey != row.issueKey?.trim()?.uppercase() ||
      expectedIdentity != null && identity != expectedIdentity
    ) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "execution identity changed")
    }
    val checkedInputs = inputs.frozen()
    val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(row.toSnapshot().artifacts)
    val plan = compatibility.requireSupportedExecution(
      descriptor?.let { JsonCodec.valueToJsonString(it).toByteArray(Charsets.UTF_8) },
      checkedInputs,
    )
    if (requestedReviewSelection != null && plan.reviewSelection != requestedReviewSelection) {
      throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
    }
    if (plan.definitionId != SkeletonDefinition.forRun(identity.routeScope == FeatureTaskRouteScope.GOAL_CHILD).id) {
      throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
    }
    AdmittedFeatureTaskRuntimeExecution(identity, plan, checkedInputs, requireNotNull(descriptor))
  } catch (error: FeatureTaskRuntimeExecutionPlanAdmissionError) {
    warn(workflowId, error.reasonCode)
    throw error
  } catch (error: InvalidFeatureTaskExecutionIdentitySchemaError) {
    warn(workflowId, "invalid_route_identity")
    throw error
  }

  fun requireCompatibleDescriptor(
    states: WorkflowStateRepository,
    workflowId: String,
    expected: ValidatedFeatureTaskRuntimeExecutionPlan?,
  ): ResolvedPhaseExecutionPlan = try {
    val row = states.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
    val descriptor = row?.toSnapshot()?.artifacts?.let {
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(it)
    }
    compatibility.requireCompatibleExecution(
      descriptor?.let { JsonCodec.valueToJsonString(it).toByteArray(Charsets.UTF_8) },
      expected,
    )
  } catch (error: FeatureTaskRuntimeExecutionPlanAdmissionError) {
    warn(workflowId, error.reasonCode)
    throw error
  }

  private fun warn(workflowId: String, reason: String) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Execution admission refused workflow=${workflowId.take(128)} reason=$reason",
    )
  }
}
