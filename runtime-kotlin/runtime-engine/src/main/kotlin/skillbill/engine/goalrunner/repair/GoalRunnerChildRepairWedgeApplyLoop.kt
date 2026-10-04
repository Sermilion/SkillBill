package skillbill.engine.goalrunner.repair

import skillbill.application.workflow.decomposition.updateGoalParentForBlockedPhaseRetry
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.lifecycle.remediation.buildCompletedUpstreamMissingOutputRepair
import skillbill.engine.featuretask.lifecycle.remediation.diagnoseUnsettledCompletedUpstreamPhaseId
import skillbill.engine.featuretask.lifecycle.remediation.featureSizeFromArtifacts
import skillbill.engine.featuretask.model.subtask.CompletedUpstreamRepairRequest
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowArtifactPatches
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.goalrunner.model.GoalRunnerAppliedRepair
import skillbill.engine.goalrunner.model.GoalRunnerChildRepairApplyRequest
import skillbill.engine.goalrunner.model.GoalRunnerChildRepairApplyResult
import skillbill.engine.goalrunner.model.GoalRunnerWedgeClass
import skillbill.engine.goalrunner.model.GoalRunnerWedgeFinding
import skillbill.goalrunner.derivedTerminalOutcomeFor
import skillbill.goalrunner.goalContinuationOutcome
import skillbill.goalrunner.model.GoalContinuation
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.nonCompleteStoredOutcomeIsCorroborated
import skillbill.goalrunner.toPersistenceWire
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import java.nio.file.Path
import java.time.Clock

class GoalRunnerChildRepairWedgeApplyLoop(
  private val engine: WorkflowEngine,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  private val gitOperations: WorkflowGitOperations,
  private val wedgeDiagnosis: GoalRunnerChildRepairWedgeDiagnosis,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val clock: Clock,
) {
  fun apply(request: GoalRunnerChildRepairApplyRequest): GoalRunnerChildRepairApplyResult {
    if (request.wedgeClasses.isEmpty()) return GoalRunnerChildRepairApplyResult()
    val workflowStates = request.unitOfWork.workflowStates
    var record =
      workflowStates.get(WorkflowFamily.TASK_RUNTIME, request.workflowId)
        ?: return GoalRunnerChildRepairApplyResult()
    var artifacts = record.artifacts
    val state =
      ApplyState(
        request = request,
        record = record,
        artifacts = artifacts,
        workingContinuation = continuationArtifactFromMap(artifacts),
        workingReview = GoalSubtaskReviewArtifactDecoder.decode(artifacts)?.state,
        clock = clock,
      )
    for (wedgeClass in request.wedgeClasses.distinct()) {
      applyWedgeClass(wedgeClass, state, workflowStates)
      record = state.record
      artifacts = state.artifacts
    }
    if (state.applied.isEmpty()) return GoalRunnerChildRepairApplyResult()
    val priorEvidence = (artifacts[GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY] as? List<*>).orEmpty()
    state.patch.putAll(
      FeatureTaskRuntimeWorkflowArtifactPatches.goalChildRepairEvidence(priorEvidence + state.evidenceEntries),
    )
    workflowPersistence.persistArtifactsPatch(workflowStates, record, state.patch)
    val updated = workflowStates.get(WorkflowFamily.TASK_RUNTIME, request.workflowId) ?: record
    return GoalRunnerChildRepairApplyResult(
      repairs = state.applied,
      manifestProjectionArtifacts = state.manifestProjectionArtifacts,
    )
  }

  private fun applyWedgeClass(
    wedgeClass: GoalRunnerWedgeClass,
    state: ApplyState,
    workflowStates: WorkflowStateRepository,
  ) {
    when (wedgeClass) {
      GoalRunnerWedgeClass.PHASE_OUTPUT_CONTRACT_INCOMPATIBLE -> Unit
      GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH -> applyMissingValidationDepth(wedgeClass, state)
      GoalRunnerWedgeClass.MISSING_QUALITY_GATE_SELECTION -> applyMissingQualityGateSelection(wedgeClass, state)
      GoalRunnerWedgeClass.UNREACHABLE_REVIEW_BASE,
      GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE,
      -> applyUnreachableReviewBase(wedgeClass, state)
      GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME ->
        applyStaleBlockedChildRepairWedge(wedgeClass, state)
      GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT ->
        applyCompletedUpstreamChildRepairWedge(
          wedgeClass = wedgeClass,
          state = state,
          workflowStates = workflowStates,
          engine = engine,
          decompositionManifestValidator = decompositionManifestValidator,
        )
      GoalRunnerWedgeClass.STALE_CHILD_WORKER_LEASE ->
        applyStaleChildWorkerLease(wedgeClass, state, workflowStates)
      GoalRunnerWedgeClass.STALE_EXECUTION_LEASE,
      GoalRunnerWedgeClass.STALE_RUNNER_INTERRUPTED_PAUSE,
      -> Unit
    }
  }

  private fun applyStaleChildWorkerLease(
    wedgeClass: GoalRunnerWedgeClass,
    state: ApplyState,
    workflowStates: WorkflowStateRepository,
  ) {
    val ownership = workflowStates.getFeatureTaskRuntimeWorkerOwnership(state.request.workflowId) ?: return
    if (
      !workflowStates.releaseFeatureTaskRuntimeWorkerIfExpired(
        state.request.workflowId,
        ownership.ownerToken,
        ownership.generation,
        state.clock.instant().toString(),
      )
    ) {
      return
    }
    val priorValue = state.request.wedgeFindings.firstOrNull { it.wedgeClass == wedgeClass }?.currentValue
    recordChildRepairWedge(state, wedgeClass, priorValue = priorValue, newValue = null)
  }

  private fun applyMissingValidationDepth(
    wedgeClass: GoalRunnerWedgeClass,
    state: ApplyState,
  ) {
    val continuation = state.workingContinuation ?: return
    if (continuation.validationDepth != null) return
    val depth = ValidationDepth.FULL
    val healed = continuation.copy(validationDepth = depth)
    state.workingContinuation = healed
    state.patch.putAll(
      FeatureTaskRuntimeWorkflowArtifactPatches.goalContinuationArtifact(healed.asWorkflowArtifactEntry()),
    )
    recordChildRepairWedge(state, wedgeClass, priorValue = null, newValue = depth.wireValue)
  }

  private fun applyMissingQualityGateSelection(
    wedgeClass: GoalRunnerWedgeClass,
    state: ApplyState,
  ) {
    val continuation = state.workingContinuation ?: return
    if (continuation.qualityGateSelection != null) return
    val selection = FeatureTaskRuntimeQualityGateSelection.VALIDATE
    val healed = continuation.copy(qualityGateSelection = selection)
    state.workingContinuation = healed
    state.patch.putAll(
      FeatureTaskRuntimeWorkflowArtifactPatches.goalContinuationArtifact(healed.asWorkflowArtifactEntry()),
    )
    recordChildRepairWedge(state, wedgeClass, priorValue = null, newValue = selection.wireValue)
  }

  private fun applyUnreachableReviewBase(
    wedgeClass: GoalRunnerWedgeClass,
    state: ApplyState,
  ) {
    val context =
      unreachableReviewRepairContext(
        UnreachableReviewRepairLookup(
          wedgeClass = wedgeClass,
          wedgeDiagnosis = wedgeDiagnosis,
          wedgeFinding = state.request.wedgeFindings.firstOrNull { it.wedgeClass == wedgeClass },
          repoRoot = state.request.repoRoot,
          gitOperations = gitOperations,
          review = state.workingReview,
          continuation = state.workingContinuation,
        ),
      ) ?: return
    applyUnreachableReviewRepairToState(wedgeClass, state, context)
  }

  class ApplyState(
    val request: GoalRunnerChildRepairApplyRequest,
    record: WorkflowStateSnapshot,
    artifacts: DurableWorkflowArtifacts,
    workingContinuation: FeatureTaskRuntimeGoalContinuationArtifact?,
    workingReview: GoalSubtaskReviewState?,
    val clock: Clock,
  ) {
    var record: WorkflowStateSnapshot = record
    var artifacts: DurableWorkflowArtifacts = artifacts
    val patch: LinkedHashMap<String, Any?> = linkedMapOf()
    val applied: MutableList<GoalRunnerAppliedRepair> = mutableListOf()
    val evidenceEntries: MutableList<Map<String, Any?>> = mutableListOf()
    var workingContinuation: FeatureTaskRuntimeGoalContinuationArtifact? =
      workingContinuation
    var workingReview: GoalSubtaskReviewState? = workingReview
    var manifestProjectionArtifacts: DurableWorkflowArtifacts? = null
  }
}

internal data class UnreachableReviewRepairContext(
  val review: GoalSubtaskReviewState,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  val failedSha: String,
  val replacement: String,
  val baselineUntrackedPaths: List<String>,
)

fun unreachableReviewFailedSha(
  wedgeClass: GoalRunnerWedgeClass,
  review: GoalSubtaskReviewState,
): String? =
  when (wedgeClass) {
    GoalRunnerWedgeClass.UNREACHABLE_REVIEW_BASE -> review.reviewBaseSha
    GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE -> review.remediationBaseSha
    GoalRunnerWedgeClass.PHASE_OUTPUT_CONTRACT_INCOMPATIBLE,
    GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH,
    GoalRunnerWedgeClass.MISSING_QUALITY_GATE_SELECTION,
    GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME,
    GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT,
    GoalRunnerWedgeClass.STALE_CHILD_WORKER_LEASE,
    GoalRunnerWedgeClass.STALE_EXECUTION_LEASE,
    GoalRunnerWedgeClass.STALE_RUNNER_INTERRUPTED_PAUSE,
    -> null
  }

internal data class UnreachableReviewRepairLookup(
  val wedgeClass: GoalRunnerWedgeClass,
  val wedgeDiagnosis: GoalRunnerChildRepairWedgeDiagnosis,
  val wedgeFinding: GoalRunnerWedgeFinding?,
  val repoRoot: Path,
  val gitOperations: WorkflowGitOperations,
  val review: GoalSubtaskReviewState?,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact?,
)

internal fun unreachableReviewRepairContext(lookup: UnreachableReviewRepairLookup): UnreachableReviewRepairContext? {
  val wedgeClass = lookup.wedgeClass
  val review = lookup.review
  val continuation = lookup.continuation
  val failedSha =
    lookup.wedgeFinding?.currentValue
      ?: review?.let { unreachableReviewFailedSha(wedgeClass, it) }
  if (review == null || continuation == null || failedSha == null) return null
  if (!lookup.wedgeDiagnosis.isUnreachable(lookup.repoRoot, failedSha)) return null
  val recovered =
    lookup.gitOperations.recoverGoalSubtaskReviewBaseline(
      lookup.repoRoot,
      GoalSubtaskReviewBaselineRecoveryRequest(
        unreachableSha = failedSha,
        failureReason = GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
        baselineUntrackedPaths = review.baselineUntrackedPaths,
      ),
      continuation.goalBranch,
    )
  val recoveredBaseline = recovered.baseline
  if (recovered.status != WorkflowGitOperationStatus.OK || recoveredBaseline == null) return null
  return UnreachableReviewRepairContext(
    review = review,
    continuation = continuation,
    failedSha = failedSha,
    replacement = recoveredBaseline.reviewBaseSha,
    baselineUntrackedPaths = recoveredBaseline.baselineUntrackedPaths,
  )
}

internal fun healedUnreachableReviewState(
  wedgeClass: GoalRunnerWedgeClass,
  context: UnreachableReviewRepairContext,
): GoalSubtaskReviewState? =
  when (wedgeClass) {
    GoalRunnerWedgeClass.UNREACHABLE_REVIEW_BASE ->
      context.review.copy(
        reviewBaseSha = context.replacement,
        baselineUntrackedPaths = context.baselineUntrackedPaths,
      )
    GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE -> context.review.copy(remediationBaseSha = context.replacement)
    GoalRunnerWedgeClass.PHASE_OUTPUT_CONTRACT_INCOMPATIBLE,
    GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH,
    GoalRunnerWedgeClass.MISSING_QUALITY_GATE_SELECTION,
    GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME,
    GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT,
    GoalRunnerWedgeClass.STALE_CHILD_WORKER_LEASE,
    GoalRunnerWedgeClass.STALE_EXECUTION_LEASE,
    GoalRunnerWedgeClass.STALE_RUNNER_INTERRUPTED_PAUSE,
    -> null
  }

internal fun applyUnreachableReviewRepairToState(
  wedgeClass: GoalRunnerWedgeClass,
  state: GoalRunnerChildRepairWedgeApplyLoop.ApplyState,
  context: UnreachableReviewRepairContext,
) {
  val healed = healedUnreachableReviewState(wedgeClass, context) ?: return
  state.workingReview = healed
  state.patch.putAll(FeatureTaskRuntimeWorkflowArtifactPatches.goalSubtaskReviewState(healed.toPersistenceWire()))
  val recoveryEvidence =
    linkedMapOf<String, Any?>(
      "original_sha" to context.failedSha,
      "replacement_sha" to context.replacement,
      "repointed_field" to wedgeClass.durableField,
      "failure_reason" to "base_not_ancestor",
      "failure_message" to "Operator goal repair repointed unreachable ${wedgeClass.durableField}.",
      "goal_branch" to context.continuation.goalBranch,
    )
  val priorRecoveries =
    (DurableWorkflowArtifactFamily.GOAL_REVIEW_BASE_RECOVERIES.value(state.artifacts) as? List<*>).orEmpty()
  val existingRecoveries =
    (DurableWorkflowArtifactFamily.GOAL_REVIEW_BASE_RECOVERIES.value(state.patch) as? List<*>) ?: priorRecoveries
  state.patch.putAll(
    FeatureTaskRuntimeWorkflowArtifactPatches.goalReviewBaseRecoveries(existingRecoveries + recoveryEvidence),
  )
  recordChildRepairWedge(state, wedgeClass, priorValue = context.failedSha, newValue = context.replacement)
}

internal fun applyStaleBlockedChildRepairWedge(
  wedgeClass: GoalRunnerWedgeClass,
  state: GoalRunnerChildRepairWedgeApplyLoop.ApplyState,
) {
  val continuation = state.workingContinuation ?: return
  val identity =
    GoalContinuation(
      issueKey = continuation.issueKey,
      subtaskId = continuation.subtaskId,
      suppressPr = continuation.suppressPr,
      goalBranch = continuation.goalBranch,
    )
  val stored =
    goalContinuationOutcome(
      state.artifacts,
      state.request.issueKey,
      state.request.subtaskId,
      continuation.suppressPr,
    )?.takeIf { it.status == GoalRunnerTerminalStatus.BLOCKED } ?: return
  val derived = derivedTerminalOutcomeFor(state.record, state.artifacts, identity) { null }
  if (
    nonCompleteStoredOutcomeIsCorroborated(
      stored.copy(workflowId = state.request.workflowId),
      derived,
      state.record,
    )
  ) {
    return
  }
  state.patch.putAll(FeatureTaskRuntimeWorkflowArtifactPatches.clearGoalContinuationOutcome())
  recordChildRepairWedge(state, wedgeClass, priorValue = stored.blockedReason, newValue = null)
}

internal fun applyCompletedUpstreamChildRepairWedge(
  wedgeClass: GoalRunnerWedgeClass,
  state: GoalRunnerChildRepairWedgeApplyLoop.ApplyState,
  workflowStates: WorkflowStateRepository,
  engine: WorkflowEngine,
  decompositionManifestValidator: DecompositionManifestValidator,
) {
  val phaseRecords = decodePhaseRecords(state.artifacts)
  val featureSize = featureSizeFromArtifacts(state.artifacts)
  val qualityGateSelection =
    state.workingContinuation?.qualityGateSelection
      ?: FeatureTaskRuntimeQualityGateSelection.VALIDATE
  val resumePhaseId =
    diagnoseUnsettledCompletedUpstreamPhaseId(
      phaseRecords,
      featureSize,
      qualityGateSelection,
    ) ?: return
  val input =
    buildCompletedUpstreamMissingOutputRepair(
      CompletedUpstreamRepairRequest(
        phaseRecords = phaseRecords,
        ledger = decodePhaseLedger(state.artifacts),
        featureSize = featureSize,
        resumePhaseId = resumePhaseId,
        reason =
          "Operator goal repair reopened '$resumePhaseId' because a completed upstream phase " +
            "record had no settled output for a blocked consumer.",
        clock = state.clock,
        qualityGateSelection = qualityGateSelection,
      ),
    )
  val updated = engine.updateRecord(WorkflowFamily.TASK_RUNTIME.definition, state.record, input)
  workflowStates.save(WorkflowFamily.TASK_RUNTIME, updated)
  state.record = updated
  state.artifacts = updated.artifacts
  state.workingContinuation = continuationArtifactFromMap(state.artifacts)
  state.workingReview = GoalSubtaskReviewArtifactDecoder.decode(state.artifacts)?.state
  state.manifestProjectionArtifacts =
    engine.updateGoalParentForBlockedPhaseRetry(
      unitOfWork = state.request.unitOfWork,
      childWorkflowId = state.request.workflowId,
      childArtifacts = state.artifacts,
      phaseId = resumePhaseId,
      validator = decompositionManifestValidator,
    )
  val repair =
    GoalRunnerAppliedRepair(
      subtaskId = state.request.subtaskId,
      workflowId = state.request.workflowId,
      wedgeClass = wedgeClass,
      field = resumePhaseId,
      priorValue = "completed_without_output",
      newValue = "pending",
    )
  state.applied += repair
  state.evidenceEntries += childRepairWedgeEvidenceMap(repair, state.clock)
}

internal fun recordChildRepairWedge(
  state: GoalRunnerChildRepairWedgeApplyLoop.ApplyState,
  wedgeClass: GoalRunnerWedgeClass,
  priorValue: String?,
  newValue: String?,
) {
  val repair =
    GoalRunnerAppliedRepair(
      subtaskId = state.request.subtaskId,
      workflowId = state.request.workflowId,
      wedgeClass = wedgeClass,
      field = wedgeClass.durableField,
      priorValue = priorValue,
      newValue = newValue,
    )
  state.applied += repair
  state.evidenceEntries += childRepairWedgeEvidenceMap(repair, state.clock)
}

fun childRepairWedgeEvidenceMap(
  repair: GoalRunnerAppliedRepair,
  clock: Clock,
): Map<String, Any?> =
  linkedMapOf(
    "wedge_class" to repair.wedgeClass.wireValue,
    "field" to repair.field,
    "prior_value" to repair.priorValue,
    "new_value" to repair.newValue,
    SharedPayloadKeys.SUBTASK_ID to repair.subtaskId,
    SharedPayloadKeys.WORKFLOW_ID to repair.workflowId,
    "repaired_at" to clock.instant().toString(),
  )

fun continuationArtifactFromMap(artifacts: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationArtifact? {
  return DurableWorkflowArtifacts.fromMap(artifacts).goalContinuationArtifact()
}
