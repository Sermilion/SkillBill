package skillbill.engine.featuretask.phase.record

import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry

internal interface FeatureTaskRuntimePhaseStatePort {
  fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite

  fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest): Boolean

  fun recordOwnedPhaseState(
    request: FeatureTaskRuntimePhaseStateRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean

  fun recordOwnedRequiredPhaseStart(
    request: FeatureTaskRuntimePhaseStateRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): RequiredPhaseWrite

  fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest): Boolean

  fun recordOwnedCompletedPhase(
    request: FeatureTaskRuntimePhaseStateRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean

  fun recordIncompleteImplementationAttempt(request: FeatureTaskRuntimePhaseStateRequest): Boolean

  fun recordOwnedIncompleteImplementationAttempt(
    request: FeatureTaskRuntimePhaseStateRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean
}

internal interface FeatureTaskRuntimePhaseStateQueries {
  fun loadImplementationAttempts(workflowId: String): List<FeatureTaskRuntimeImplementationAttempt>?

  fun clearBackwardEdgeContext(
    workflowId: String,
    phaseIds: Collection<String>,
  ): Boolean

  fun loadOperatorBlockRetry(workflowId: String): FeatureTaskRuntimeOperatorBlockRetry?
}
