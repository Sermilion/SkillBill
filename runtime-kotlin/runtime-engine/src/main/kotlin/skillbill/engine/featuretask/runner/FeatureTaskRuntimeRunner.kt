package skillbill.engine.featuretask.runner

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePreparation
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunPreparation
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope

@Inject
class FeatureTaskRuntimeRunner(
  private val startup: FeatureTaskRuntimeRunStartup,
  private val preparation: FeatureTaskRuntimeRunPreparation,
  private val execute: FeatureTaskRuntimeRunnerExecute,
) {
  fun run(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeRunReport {
    execute.foreignModeWorkflowBlock(request)?.let { return it }
    execute.terminalWorkflowBlock(request)?.let { return it }
    val admittedRequest = request.copy(admittedExecution = startup.admit(request))
    validateAdmittedRequest(admittedRequest)
    val reconciliation = startup.reconcile()
    return when (val prepared = this.preparation.prepare(admittedRequest)) {
      is FeatureTaskRuntimePreparation.PreparationBlocked -> prepared.report
      is FeatureTaskRuntimePreparation.Prepared -> execute.executePreparedRun(prepared.request, reconciliation)
    }
  }

  private fun validateAdmittedRequest(request: FeatureTaskRuntimeRunRequest) {
    val admitted = requireNotNull(request.admittedExecution)
    val identity = admitted.identity
    if (identity.workflowId != request.workflowId ||
      identity.normalizedIssueKey != FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(request.issueKey) ||
      (identity.routeScope == FeatureTaskRouteScope.GOAL_CHILD) != (request.goalContinuation != null)
    ) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(request.workflowId, "admission does not match run request")
    }
    if (request.transitionsOverride != null && request.transitionsOverride != admitted.plan.traversal) {
      throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
    }
  }
}
