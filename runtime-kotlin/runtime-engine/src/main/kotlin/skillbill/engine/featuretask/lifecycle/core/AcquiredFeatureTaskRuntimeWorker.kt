package skillbill.engine.featuretask.lifecycle.core

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.execution.requireCurrent
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import java.time.Instant

class AcquiredFeatureTaskRuntimeWorker internal constructor(
  private val ownership: FeatureTaskRuntimeWorkerOwnership,
  private val execution: AdmittedFeatureTaskRuntimeExecution,
  private val original: WorkflowStateSnapshot,
  internal val previousOwnerGeneration: Long,
  internal val previousOwnerStopped: Boolean,
  internal val effectiveInputs: EffectiveGatePolicyInputs,
  private val admittedSnapshot: WorkflowStateSnapshot,
) {
  @Volatile private var active = true

  internal val currentStepId: String get() = admittedSnapshot.currentStepId
  val workflowId: String get() = ownership.workflowId
  val generation: Long get() = ownership.generation
  internal val identity: FeatureTaskExecutionIdentity get() = execution.identity
  internal val governedSpecPath: String get() = execution.identity.governedSpecPath
  internal val normalizedIssueKey: String get() = execution.identity.normalizedIssueKey
  internal val repositoryIdentity: String get() = execution.identity.repositoryIdentity
  internal val goalChild: Boolean get() = execution.identity.routeScope == FeatureTaskRouteScope.GOAL_CHILD
  internal val admittedExecution: AdmittedFeatureTaskRuntimeExecution
    get() = execution.withWorkerOwnership(ownership)

  internal fun ownsAuditStep(phaseId: String): Boolean =
    execution.plan.dispatchStrategyByStep[phaseId]?.slot == PhaseSlot.AUDIT

  internal fun admits(
    states: WorkflowStateRepository,
    now: Instant,
  ): Boolean {
    if (!active) return false
    if (states.get(WorkflowFamily.TASK_RUNTIME, workflowId)?.workflowStatus != WorkflowStatus.RUNNING) return false
    execution.requireCurrent(states, workflowId)
    val current = states.getFeatureTaskRuntimeWorkerOwnership(workflowId) ?: return false
    return current.ownerToken == ownership.ownerToken && current.generation == ownership.generation &&
      current.leaseState == FeatureTaskRuntimeWorkerLeaseState.ACTIVE && current.expiresAtInstant.isAfter(now)
  }

  internal fun originalSnapshot(states: WorkflowStateRepository): WorkflowStateSnapshot? {
    val current = states.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return null
    return original.takeIf {
      JsonCodec.valueToJsonString(admittedSnapshot.artifacts) == JsonCodec.valueToJsonString(current.artifacts)
    }
  }

  internal fun close() {
    active = false
  }
}
