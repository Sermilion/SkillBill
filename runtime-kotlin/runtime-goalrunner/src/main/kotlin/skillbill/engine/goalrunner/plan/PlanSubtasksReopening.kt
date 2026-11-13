package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.checkpoint.completedUpstreamRepairRetryEntry
import skillbill.engine.featuretask.lifecycle.checkpoint.completedUpstreamRepairWorkflowUpdate
import skillbill.engine.featuretask.lifecycle.checkpoint.phasesToReopenForCompletedUpstreamRepair
import skillbill.engine.featuretask.lifecycle.checkpoint.settledPhaseOutputs
import skillbill.engine.featuretask.lifecycle.remediation.buildCompletedUpstreamMissingOutputRepair
import skillbill.engine.featuretask.lifecycle.remediation.featureSizeFromArtifacts
import skillbill.engine.featuretask.model.subtask.CompletedUpstreamRepairRequest
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibility
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibilitySnapshot
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibilityStateCodec
import skillbill.engine.featuretask.phase.planning.PlanBundleSnapshots
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.engine.featuretask.phase.record.asPendingForOperatorResume
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodePlanBundleFromArtifact
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanFanOutHistory
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.PLAN_BUNDLE_FEASIBILITY_FULL_V2_REVIEW_ADOPTION_REASON
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchKind
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityMode
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityReopening
import skillbill.workflow.taskruntime.model.planning.isPlanBundleFeasibilityApprovalCandidateEligible
import skillbill.workflow.taskruntime.model.planning.reopenApprovedPlanBundleFeasibilityCycle
import skillbill.workflow.taskruntime.model.planning.reopenBlockedPlanBundleFeasibilityCycle
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import java.time.Clock

internal data class PlanSubtasksReplan(
  val issueKey: String,
  val planWorkflowId: String,
  val repoRoot: Path,
  val sourceRoot: Path = repoRoot,
  val settledSubtaskIds: Set<Int>,
  val agentId: String? = null,
  val forcedSubtaskIds: Set<Int> = emptySet(),
  val feasibilityAdoption: Boolean = false,
  val bundleRoot: Path = sourceRoot,
  val parentWorkflowId: String? = null,
)

@Inject
public class PlanSubtasksReopening(
  private val database: DatabaseSessionFactory,
  private val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner,
  private val planBundles: FeatureTaskRuntimeDecomposeTerminalRecorder,
  private val clock: Clock,
  private val feasibilityCodec: PlanBundleFeasibilityStateCodec,
  private val diagnostics: RuntimeDiagnostics,
) {
  private val engine = WorkflowEngine()

  internal fun hasReopenableBlockedFeasibility(planWorkflowId: String): Boolean =
    database.read { unitOfWork ->
      val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY
      val artifacts =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, planWorkflowId)?.artifacts
          ?.takeIf(family::contains)
      val state = artifacts?.let { feasibilityCodec.decode(family.value(it), "$planWorkflowId/${family.label()}") }
      state?.mode == PlanBundleFeasibilityMode.BLOCKED && state.activeLaunch == null
    }

  internal fun reopenWithRebasedBundle(replan: PlanSubtasksReplan): Boolean =
    database.transaction { unit ->
      if (!unit.matchesGoalSelection(replan)) return@transaction false
      val snapshot =
        unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, replan.planWorkflowId) ?: return@transaction false
      if (!snapshot.hasPlanSubtasksStep()) return@transaction false
      if (unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(replan.planWorkflowId) != null) {
        return@transaction false
      }
      when {
        snapshot.needsPlanningInitialization() && replan.forcedSubtaskIds.isEmpty() -> {
          if (snapshot.workflowStatus == WorkflowStatus.RUNNING) {
            unit.workflowStates.saveAcceptedSnapshotMutation(
              WorkflowFamily.TASK_RUNTIME,
              snapshot.copy(workflowStatus = WorkflowStatus.PENDING, finishedAt = null),
            )
          }
          true
        }
        replan.feasibilityAdoption -> reopenForFeasibility(replan)
        !reopenAtPlanSubtasks(replan.planWorkflowId) -> false
        else -> {
          rebasePlanBundle(replan)
          true
        }
      }
    }

  private fun WorkflowStateSnapshot.needsPlanningInitialization(): Boolean {
    val phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS
    val incomplete =
      workflowStatus !in WorkflowStatus.terminalStatuses &&
        step(phaseId)?.status != WorkflowStepStatus.COMPLETED &&
        decodePhaseRecords(artifacts)[phaseId]?.status != WorkflowStepStatus.COMPLETED
    val missingEvidence =
      !DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.contains(artifacts) ||
        !DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY.contains(artifacts)
    return incomplete && missingEvidence
  }

  private fun UnitOfWork.matchesGoalSelection(replan: PlanSubtasksReplan): Boolean {
    val parentId = replan.parentWorkflowId ?: return true
    val owner = goalPlanningOwnership.findByWorkflow(replan.planWorkflowId) ?: return false
    val control = goalRunnerControls.controlState(parentId)
    val selected =
      workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentId)
        ?.step(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN)?.planWorkflowId
    val path = replan.repoRoot.toAbsolutePath().normalize()
    val matchesPath =
      owner.ownedPath.toAbsolutePath().normalize() == path &&
        control.executionWorktreePath?.let(Path::of)?.toAbsolutePath()?.normalize() == path
    return selected == replan.planWorkflowId && matchesPath && owner.parentWorkflowId == parentId &&
      owner.state == GoalPlanningOwnership.State.TRANSFERRED &&
      owner.repositoryIdentity == control.repositoryIdentity &&
      owner.normalizedIssueKey == FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(replan.issueKey) &&
      goalPlanningOwnership.findByIssue(owner.repositoryIdentity, owner.normalizedIssueKey) == owner &&
      goalPlanningOwnership.checkoutRecovery(owner) == null &&
      workflowStates.getFeatureTaskRuntimeWorkerOwnership(replan.planWorkflowId) == null
  }

  private fun reopenForFeasibility(replan: PlanSubtasksReplan): Boolean {
    val captured = PlanBundleFeasibility.capture(decompositionPlanner, replan.bundleRoot, replan.issueKey)
    val outcome =
      database.transaction<Pair<Boolean, Int?>?> { unitOfWork ->
        val snapshot =
          unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, replan.planWorkflowId)
            ?: return@transaction null
        if (!snapshot.hasPlanSubtasksStep()) return@transaction null
        val owned = unitOfWork.workflowStates.getFeatureTaskRuntimeWorkerOwnership(replan.planWorkflowId) != null
        if (owned) return@transaction null
        val phaseRecords = decodePhaseRecords(snapshot.artifacts)
        val phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS
        val original = phaseRecords[phaseId] ?: return@transaction null
        if (requiresStandaloneSettlement(snapshot, original.status, replan)) {
          return@transaction null
        }
        val rechecked = PlanBundleFeasibility.capture(decompositionPlanner, replan.bundleRoot, replan.issueKey)
        if (rechecked.identity != captured.identity) return@transaction null
        val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY
        val state = feasibilityStateFor(replan, captured, original, snapshot.artifacts)
        if (snapshot.workflowStatus == WorkflowStatus.RUNNING && state.activeLaunch != null) return@transaction null
        adoptedPendingOutcome(state, original, captured)?.let { outcome ->
          return@transaction if (outcome.first) {
            restorePendingAdoption(replan, snapshot, unitOfWork.workflowStates, original.attemptCount)
          } else {
            outcome
          }
        }
        val ledger = decodePhaseLedger(snapshot.artifacts)
        val repair =
          CompletedUpstreamRepairRequest(
            phaseRecords,
            ledger,
            featureSizeFromArtifacts(snapshot.artifacts),
            phaseId,
            "Bundle feasibility compatibility adoption schedules a full v2 review.",
            clock,
          )
        val retry = completedUpstreamRepairRetryEntry(repair)
        val phasesToReopen = phasesToReopenForCompletedUpstreamRepair(repair, settledPhaseOutputs(phaseRecords))
        val reopenedRecords = reopenedPhaseRecords(phaseRecords, phasesToReopen, phaseId)
        val nextState =
          state.reopenedForReview(
            replan,
            captured,
            original,
            retry.sequenceNumber,
            family.contains(snapshot.artifacts),
          ) ?: return@transaction null
        val rebasedBundle = rebasedFeasibilityBundle(replan, snapshot.artifacts) ?: return@transaction null
        val update = completedUpstreamRepairWorkflowUpdate(repair, phasesToReopen, reopenedRecords, retry)
        val patch = update.artifactsPatch.orEmpty() + feasibilityReopeningPatch(nextState, rebasedBundle)
        val updated =
          engine.updateRecord(
            WorkflowFamily.TASK_RUNTIME.definition,
            snapshot,
            update.copy(workflowStatus = WorkflowStatus.PENDING, artifactsPatch = WorkflowArtifactPatch.from(patch)),
          )
        unitOfWork.workflowStates.saveAcceptedSnapshotMutation(WorkflowFamily.TASK_RUNTIME, updated)
        true to original.attemptCount
      }
    reportFeasibilityAdoption(replan, outcome)
    return outcome?.first == true
  }

  private fun requiresStandaloneSettlement(
    snapshot: WorkflowStateSnapshot,
    status: WorkflowStepStatus,
    replan: PlanSubtasksReplan,
  ): Boolean =
    snapshot.workflowStatus == WorkflowStatus.RUNNING && status != WorkflowStepStatus.PENDING &&
      replan.parentWorkflowId == null

  private fun restorePendingAdoption(
    replan: PlanSubtasksReplan,
    snapshot: WorkflowStateSnapshot,
    states: WorkflowStateRepository,
    attempt: Int,
  ): Pair<Boolean, Int?>? {
    val rebased = rebasedFeasibilityBundle(replan, snapshot.artifacts) ?: return null
    val updated =
      snapshot.copy(
        workflowStatus = WorkflowStatus.PENDING,
        finishedAt = null,
        artifacts =
          DurableWorkflowArtifacts.fromMap(
            snapshot.artifacts +
              DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.entry(
                rebased.asWorkflowArtifactEntry(),
              ),
          ),
      )
    if (updated == snapshot) return true to null
    states.saveAcceptedSnapshotMutation(WorkflowFamily.TASK_RUNTIME, updated)
    return true to attempt
  }

  private fun feasibilityReopeningPatch(
    nextState: FeatureTaskRuntimePlanBundleFeasibilityState,
    bundle: FeatureTaskRuntimePlanBundle?,
  ): Map<String, Any?> =
    buildMap {
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY.putInto(
        this,
        feasibilityCodec.encode(nextState),
      )
      bundle?.let {
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.putInto(
          this,
          it.asWorkflowArtifactEntry(),
        )
      }
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL.putInto(this, null)
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_FAN_OUT_HISTORY.putInto(
        this,
        FeatureTaskRuntimePlanFanOutHistory(emptyList()).asWorkflowArtifactEntry(),
      )
    }

  private fun FeatureTaskRuntimePlanBundleFeasibilityState.reopenedForReview(
    replan: PlanSubtasksReplan,
    captured: PlanBundleFeasibilitySnapshot,
    original: FeatureTaskRuntimePhaseRecord,
    retrySequence: Int,
    retainedArtifact: Boolean,
  ): FeatureTaskRuntimePlanBundleFeasibilityState? {
    val cycleId = "${replan.planWorkflowId}:plan_subtasks:retry-$retrySequence"
    return when (mode) {
      PlanBundleFeasibilityMode.APPROVED ->
        reopenApprovedPlanBundleFeasibilityCycle(this, captured.identity, cycleId, retrySequence, original)
          ?: return null
      PlanBundleFeasibilityMode.BLOCKED ->
        reopenBlockedPlanBundleFeasibilityCycle(this, cycleId, retrySequence, original)
          ?: adoptionReopening(original, retrySequence, retainedArtifact)
      else -> adoptionReopening(original, retrySequence, retainedArtifact)
    }
  }

  private fun FeatureTaskRuntimePlanBundleFeasibilityState.adoptionReopening(
    original: FeatureTaskRuntimePhaseRecord,
    retrySequence: Int,
    retainedArtifact: Boolean,
  ): FeatureTaskRuntimePlanBundleFeasibilityState =
    copy(
      revision = revision + if (retainedArtifact) 1 else 0,
      reopenings =
        reopenings +
          PlanBundleFeasibilityReopening(
            original.attemptCount,
            revision.takeIf { retainedArtifact },
            retrySequence,
            PLAN_BUNDLE_FEASIBILITY_FULL_V2_REVIEW_ADOPTION_REASON,
            original,
          ),
    )

  private fun feasibilityStateFor(
    replan: PlanSubtasksReplan,
    captured: PlanBundleFeasibilitySnapshot,
    original: FeatureTaskRuntimePhaseRecord,
    artifacts: DurableWorkflowArtifacts,
  ): FeatureTaskRuntimePlanBundleFeasibilityState {
    val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY
    return if (family.contains(artifacts)) {
      feasibilityCodec.decode(family.value(artifacts), "${replan.planWorkflowId}/${family.label()}")
    } else {
      FeatureTaskRuntimePlanBundleFeasibilityState(
        revision = 0,
        mode = PlanBundleFeasibilityMode.REVIEW,
        identity = captured.identity,
        acceptedLines = emptyList(),
        conflicts = emptyList(),
        changedSubtaskIds = emptyList(),
        requiredRefs = captured.criterionRefs,
        carriedRefs = emptyList(),
        reviewRoundsStarted = 0,
        repairLaunchesStarted = 0,
        activeLaunch = null,
        settledLaunches = emptyList(),
        lastReviewValue = null,
        approval = null,
        legacyOrigin = original.outputArtifact?.contains(PlanBundleFeasibility.HISTORICAL_PREFIX) == true,
        currentCycleId = "${replan.planWorkflowId}:plan_subtasks:cycle-1",
      )
    }
  }

  private fun adoptedPendingOutcome(
    state: FeatureTaskRuntimePlanBundleFeasibilityState,
    original: FeatureTaskRuntimePhaseRecord,
    captured: PlanBundleFeasibilitySnapshot,
  ): Pair<Boolean, Int?>? {
    val pendingAdoption =
      state.mode != PlanBundleFeasibilityMode.BLOCKED && original.status == WorkflowStepStatus.PENDING
    if (pendingAdoption &&
      state.reopenings.any {
        it.sourceAttempt == original.attemptCount &&
          it.originalPhaseRecord.outputArtifact == original.outputArtifact
      }
    ) {
      val candidate = state.approvalCandidate
      if (candidate != null &&
        !isPlanBundleFeasibilityApprovalCandidateEligible(
          state,
          original.phaseId,
          candidate.attempt,
          state.revision,
          captured.fingerprint,
        )
      ) {
        val activeReview =
          state.activeLaunch?.let {
            it.kind == PlanBundleFeasibilityLaunchKind.REVIEW &&
              it.cycleId == state.currentCycleId
          } == true
        val reviewUnavailable = state.activeLaunch == null && state.reviewRoundsStarted >= MAX_REVIEW_ROUNDS
        val activeUnavailable = state.activeLaunch != null && !activeReview
        if (state.mode != PlanBundleFeasibilityMode.REVIEW || activeUnavailable || reviewUnavailable) {
          return false to original.attemptCount
        }
        return true to original.attemptCount
      }
      return true to null
    }
    return null
  }

  private fun reopenedPhaseRecords(
    phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
    phasesToReopen: List<String>,
    phaseId: String,
  ): Map<String, FeatureTaskRuntimePhaseRecord> {
    val reopenedRecords = LinkedHashMap(phaseRecords)
    phasesToReopen.forEach { selected ->
      val record = requireNotNull(reopenedRecords[selected])
      reopenedRecords[selected] =
        if (selected == phaseId) {
          record.copy(
            status = WorkflowStepStatus.PENDING,
            finishedAt = null,
            durationMillis = null,
            blockedReason = null,
            failureDisposition = null,
          )
        } else {
          record.asPendingForOperatorResume()
        }
    }
    return reopenedRecords
  }

  private fun reportFeasibilityAdoption(
    replan: PlanSubtasksReplan,
    outcome: Pair<Boolean, Int?>?,
  ) {
    val adoptedAttempt = outcome?.second
    if (adoptedAttempt != null) {
      val diagnostic =
        if (outcome.first) {
          "Plan workflow '${replan.planWorkflowId}' adopted for a full v2 feasibility review from attempt " +
            "$adoptedAttempt; historical reviewer output remains retained."
        } else {
          "Plan workflow '${replan.planWorkflowId}' cannot publish its retained feasibility review from attempt " +
            "$adoptedAttempt because its revision chain is unproven or the review budget is exhausted; " +
            "historical reviewer output remains retained."
        }
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        diagnostic,
      )
    }
  }

  private fun rebasedFeasibilityBundle(
    replan: PlanSubtasksReplan,
    artifacts: DurableWorkflowArtifacts,
  ): FeatureTaskRuntimePlanBundle? {
    val bundle =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.value(artifacts)
        ?.let(::decodePlanBundleFromArtifact) ?: return null
    return PlanBundleSnapshots(decompositionPlanner)
      .rebase(replan.bundleRoot, replan.issueKey, bundle, replan.settledSubtaskIds)
      ?.withoutPlannedUnits(replan.forcedSubtaskIds)
      ?: return null
  }

  private fun rebasePlanBundle(replan: PlanSubtasksReplan) {
    val bundle = planBundles.loadPlanBundle(replan.planWorkflowId) ?: return
    PlanBundleSnapshots(decompositionPlanner)
      .rebase(replan.bundleRoot, replan.issueKey, bundle, replan.settledSubtaskIds)
      ?.let { rebased ->
        planBundles.replacePlanBundle(replan.planWorkflowId, rebased.withoutPlannedUnits(replan.forcedSubtaskIds))
      }
  }

  internal fun reopenAtPlanSubtasks(planWorkflowId: String): Boolean =
    database.transaction { unitOfWork ->
      val snapshot = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, planWorkflowId)
      if (snapshot == null || !snapshot.hasPlanSubtasksStep()) {
        false
      } else {
        val update =
          buildCompletedUpstreamMissingOutputRepair(
            CompletedUpstreamRepairRequest(
              phaseRecords = decodePhaseRecords(snapshot.artifacts),
              ledger = decodePhaseLedger(snapshot.artifacts),
              featureSize = featureSizeFromArtifacts(snapshot.artifacts),
              resumePhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
              reason = "Spec drift recovery re-plans the subtask implementation details.",
              clock = clock,
            ),
          )
        val updated =
          engine.updateRecord(
            WorkflowFamily.TASK_RUNTIME.definition,
            snapshot,
            update.copy(
              workflowStatus = WorkflowStatus.PENDING,
              artifactsPatch =
                WorkflowArtifactPatch.from(
                  update.artifactsPatch.orEmpty() +
                    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL.entry(null) +
                    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_FAN_OUT_HISTORY.entry(
                      FeatureTaskRuntimePlanFanOutHistory(emptyList()).asWorkflowArtifactEntry(),
                    ),
                ),
            ),
          )
        unitOfWork.workflowStates.saveAcceptedSnapshotMutation(WorkflowFamily.TASK_RUNTIME, updated)
        true
      }
    }
}

internal fun WorkflowStateSnapshot.hasPlanSubtasksStep(): Boolean =
  steps.any { it.stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS }

private const val MAX_REVIEW_ROUNDS = 3
