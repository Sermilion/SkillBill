package skillbill.engine.featuretask.lifecycle.continuation

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.remediation.FeatureTaskRuntimeRemediationBaseReconciler
import skillbill.engine.featuretask.lifecycle.remediation.RemediationDegradationSignal
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
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
public class FeatureTaskRuntimeGoalContinuationRecorder(
  private val database: DatabaseSessionFactory,
  private val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
) {
  private val engine: WorkflowEngine = WorkflowEngine()
  private val patcher = FeatureTaskRuntimeGoalContinuationArtifactPatcher(engine)
  private val runtimeOwnedPersistence = RuntimeOwnedPersistenceBoundary(database, diagnostics)
  internal val reviewStateRecorder: FeatureTaskRuntimeGoalContinuationStateRecorder =
    FeatureTaskRuntimeGoalContinuationStateRecorder(database, engine, clock)
  internal val reviewPassRecorder: FeatureTaskRuntimeGoalReviewPassRecorder =
    FeatureTaskRuntimeGoalReviewPassRecorder(database, patcher, runtimeOwnedPersistence)
  private val inputBuilder =
    FeatureTaskRuntimeGoalReviewInputBuilder(
      database,
      patcher,
      reviewPassRecorder::persistGoalReviewInput,
    )
  internal val remediationReconciler: FeatureTaskRuntimeRemediationBaseReconciler =
    FeatureTaskRuntimeRemediationBaseReconciler(database, patcher, clock)

  internal fun recordGoalContinuationState(
    request: GoalContinuationStateRecordRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): Boolean = reviewStateRecorder.recordGoalContinuationState(request, admitted)

  public fun reserveGoalReviewPass(
    workflowId: String,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): GoalSubtaskReviewPassReservation = reviewPassRecorder.reserveGoalReviewPass(workflowId, admitted)

  public fun persistGoalReviewInput(
    workflowId: String,
    input: GoalSubtaskReviewInput,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): GoalSubtaskReviewState? = reviewPassRecorder.persistGoalReviewInput(workflowId, input, admitted)

  public fun updateReviewState(
    workflowId: String,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
    transform: (GoalSubtaskReviewState) -> GoalSubtaskReviewState,
  ): GoalSubtaskReviewState? = reviewPassRecorder.updateReviewState(workflowId, admitted, transform)

  internal fun completeGoalReviewPass(
    request: GoalReviewPassCompletionRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): GoalSubtaskReviewState? = reviewPassRecorder.completeGoalReviewPass(request, admitted)

  public class GoalReviewInputScope(
    public val scopedUntrackedExclusions: List<String>? = null,
    public val ownedPathspec: List<String> = emptyList(),
  )

  public fun buildGoalReviewInput(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    scope: GoalReviewInputScope = GoalReviewInputScope(),
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): GoalSubtaskReviewInputPreparation =
    inputBuilder.buildGoalReviewInput(workflowId, gitOperations, repoRoot, scope, admitted)
}

public data class GoalContinuationStateRecordRequest(
  public val workflowId: String,
  public val continuation: FeatureTaskRuntimeGoalContinuationArtifact? = null,
  public val reviewBaseline: GoalSubtaskReviewBaseline? = null,
  public val outcome: FeatureTaskRuntimeGoalContinuationOutcome? = null,
  internal val fieldAdoption: FeatureTaskRuntimeGoalContinuationFieldAdoption? = null,
  public val workflowStatus: String? = null,
)

public data class GoalReviewPassCompletionRequest(
  public val workflowId: String,
  public val verdict: FeatureTaskRuntimeVerdict,
  public val unresolvedFindingCount: Int,
  public val findings: List<GoalSubtaskReviewCompactFinding>,
  public val rawReviewResult: String,
  public val normalizedOutput: Map<String, Any?>,
  public val blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
  public val commitFocusedAccounting: GoalSubtaskCommitFocusedAccounting? = null,
)

public fun FeatureTaskRuntimeGoalContinuationRecorder.reviewState(workflowId: String): GoalSubtaskReviewState? =
  reviewStateRecorder.reviewState(workflowId)

public fun FeatureTaskRuntimeGoalContinuationRecorder.continuation(
  workflowId: String,
): FeatureTaskRuntimeGoalContinuationArtifact? = reviewStateRecorder.continuation(workflowId)

public fun FeatureTaskRuntimeGoalContinuationRecorder.lastGoalReviewResult(workflowId: String): String? =
  reviewPassRecorder.lastGoalReviewResult(workflowId)

public fun FeatureTaskRuntimeGoalContinuationRecorder.appendRemediationRollbackDegradationEvidence(
  workflowId: String,
  signal: RemediationDegradationSignal,
  admitted: AdmittedFeatureTaskRuntimeExecution? = null,
): Unit = remediationReconciler.appendRemediationRollbackDegradationEvidence(workflowId, signal, admitted)

public fun FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence(
  workflowId: String,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  admitted: AdmittedFeatureTaskRuntimeExecution? = null,
): RemediationBaseCoherenceResult =
  remediationReconciler.reconcileRemediationBaseCoherence(workflowId, gitOperations, repoRoot, admitted)
