package skillbill.engine.featuretask.lifecycle.continuation

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.remediation.FeatureTaskRuntimeRemediationBaseReconciler
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.lifecycle.remediation.RemediationDegradationSignal
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewInputPreparation
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassReservation
import skillbill.engine.featuretask.model.subtask.RemediationBaseCoherenceResult
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.review.goal.FeatureTaskRuntimeGoalReviewInputBuilder
import skillbill.engine.featuretask.review.goal.FeatureTaskRuntimeGoalReviewPassRecorder
import skillbill.goalrunner.model.FeatureTaskRuntimeGoalContinuationOutcome
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.model.goalreview.GoalSubtaskBlockerDisposition
import skillbill.workflow.model.goalreview.GoalSubtaskCommitFocusedAccounting
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationFieldAdoption
import java.nio.file.Path
import java.time.Clock

@Inject
class FeatureTaskRuntimeGoalContinuationRecorder(
  private val database: DatabaseSessionFactory,
  private val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
) {
  private val engine: WorkflowEngine = WorkflowEngine()
  private val patcher = FeatureTaskRuntimeGoalContinuationArtifactPatcher(engine)
  private val runtimeOwnedPersistence = RuntimeOwnedPersistenceBoundary(database, diagnostics)
  val reviewStateRecorder = FeatureTaskRuntimeGoalContinuationStateRecorder(database, engine, clock)
  val reviewPassRecorder =
    FeatureTaskRuntimeGoalReviewPassRecorder(database, patcher, runtimeOwnedPersistence, clock)
  private val inputBuilder =
    FeatureTaskRuntimeGoalReviewInputBuilder(
      database,
      patcher,
      clock,
      reviewPassRecorder::persistGoalReviewInput,
    )
  val remediationReconciler = FeatureTaskRuntimeRemediationBaseReconciler(database, patcher, clock)

  internal fun recordGoalContinuationState(
    request: GoalContinuationStateRecordRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): Boolean = reviewStateRecorder.recordGoalContinuationState(request, admitted)

  fun reserveGoalReviewPass(
    workflowId: String,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): GoalSubtaskReviewPassReservation = reviewPassRecorder.reserveGoalReviewPass(workflowId, admitted)

  fun persistGoalReviewInput(
    workflowId: String,
    input: GoalSubtaskReviewInput,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): GoalSubtaskReviewState? = reviewPassRecorder.persistGoalReviewInput(workflowId, input, admitted)

  fun updateReviewState(
    workflowId: String,
    transform: (GoalSubtaskReviewState) -> GoalSubtaskReviewState,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): GoalSubtaskReviewState? = reviewPassRecorder.updateReviewState(workflowId, transform, admitted)

  internal fun completeGoalReviewPass(
    request: GoalReviewPassCompletionRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): GoalSubtaskReviewState? = reviewPassRecorder.completeGoalReviewPass(request, admitted)

  class GoalReviewInputScope(
    val scopedUntrackedExclusions: List<String>? = null,
    val ownedPathspec: List<String> = emptyList(),
  )

  fun buildGoalReviewInput(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    scope: GoalReviewInputScope = GoalReviewInputScope(),
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): GoalSubtaskReviewInputPreparation =
    inputBuilder.buildGoalReviewInput(workflowId, gitOperations, repoRoot, scope, admitted)
}

internal data class GoalContinuationStateRecordRequest(
  val workflowId: String,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact? = null,
  val reviewBaseline: GoalSubtaskReviewBaseline? = null,
  val outcome: FeatureTaskRuntimeGoalContinuationOutcome? = null,
  val fieldAdoption: FeatureTaskRuntimeGoalContinuationFieldAdoption? = null,
  val workflowStatus: String? = null,
)

internal data class GoalReviewPassCompletionRequest(
  val workflowId: String,
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
  val findings: List<GoalSubtaskReviewCompactFinding>,
  val rawReviewResult: String,
  val normalizedOutput: Map<String, Any?>,
  val blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
  val commitFocusedAccounting: GoalSubtaskCommitFocusedAccounting? = null,
)

fun FeatureTaskRuntimeGoalContinuationRecorder.reviewState(workflowId: String): GoalSubtaskReviewState? =
  reviewStateRecorder.reviewState(workflowId)

fun FeatureTaskRuntimeGoalContinuationRecorder.continuation(
  workflowId: String,
): FeatureTaskRuntimeGoalContinuationArtifact? = reviewStateRecorder.continuation(workflowId)

fun FeatureTaskRuntimeGoalContinuationRecorder.lastGoalReviewResult(workflowId: String): String? =
  reviewPassRecorder.lastGoalReviewResult(workflowId)

internal fun FeatureTaskRuntimeGoalContinuationRecorder.appendRemediationRollbackDegradationEvidence(
  workflowId: String,
  signal: RemediationDegradationSignal,
  admitted: AdmittedFeatureTaskRuntimeExecution? = null,
) = remediationReconciler.appendRemediationRollbackDegradationEvidence(workflowId, signal, admitted)

fun FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence(
  workflowId: String,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  admitted: AdmittedFeatureTaskRuntimeExecution? = null,
): RemediationBaseCoherenceResult =
  remediationReconciler.reconcileRemediationBaseCoherence(workflowId, gitOperations, repoRoot, admitted)
