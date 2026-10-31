package skillbill.engine.goalrunner.model

import skillbill.goalrunner.model.GoalContinuation
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowStateRecord

internal data class CrashReconcileExpiredWorkerRequest(
  val workflowStates: WorkflowStateRepository,
  val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  val workflowId: String,
  val continuation: GoalContinuation,
  val ownership: FeatureTaskRuntimeWorkerOwnership,
  val row: WorkflowStateRecord,
)

internal data class StaleRunningCandidatesBlockRequest(
  val unitOfWork: UnitOfWork,
  val normalizedIssueKey: String,
  val candidates: List<GoalContinuationCandidate>,
  val initialAuthoritative: Map<Int, GoalRunnerStoredOutcome>,
  val activeSet: Set<String>,
  val gate: GoalRunnerReconcileGate,
)

public data class GoalRunnerManifestReconciliationInputs(
  internal val outcomes: Map<Int, GoalRunnerStoredOutcome>,
  internal val restoredChildren: Map<Int, GoalRunnerRestoredChildReference>,
)
