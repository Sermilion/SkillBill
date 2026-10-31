package skillbill.engine.featuretask.phase.record

import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.slot.state.FeatureTaskRuntimeImplementationTurnWrite
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry

public interface FeatureTaskRuntimePhaseStatePort {
  public fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite

  public fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest): Boolean

  public fun recordOwnedPhaseState(
    request: FeatureTaskRuntimePhaseStateRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean

  public fun recordOwnedRequiredPhaseStart(
    request: FeatureTaskRuntimePhaseStateRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): RequiredPhaseWrite

  public fun bindOwnedPhaseCoordinates(
    request: FeatureTaskRuntimePhaseStateRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean

  public fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest): Boolean

  public fun recordOwnedCompletedPhase(
    request: FeatureTaskRuntimePhaseStateRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean

  public fun recordIncompleteImplementationAttempt(request: FeatureTaskRuntimePhaseStateRequest): Boolean

  public fun recordOwnedIncompleteImplementationAttempt(
    request: FeatureTaskRuntimePhaseStateRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean
}

public interface FeatureTaskRuntimePhaseStateQueries {
  public fun loadImplementationAttempts(workflowId: String): List<FeatureTaskRuntimeImplementationAttempt>?

  public fun clearBackwardEdgeContext(
    workflowId: String,
    phaseIds: Collection<String>,
  ): Boolean

  public fun loadOperatorBlockRetry(workflowId: String): FeatureTaskRuntimeOperatorBlockRetry?
}

public interface FeatureTaskRuntimeImplementationProgressPort {
  public fun loadImplementationProgress(workflowId: String): FeatureTaskRuntimeImplementationProgress?

  public fun recordImplementationTurn(write: FeatureTaskRuntimeImplementationTurnWrite): RequiredPhaseWrite

  public fun recordOwnedImplementationTurn(
    write: FeatureTaskRuntimeImplementationTurnWrite,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): RequiredPhaseWrite
}
