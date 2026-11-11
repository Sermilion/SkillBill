package skillbill.workflow.taskruntime.model.planning

import skillbill.text.sha256HexUtf8
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

public fun settlePlanBundleFeasibilityReview(
  state: FeatureTaskRuntimePlanBundleFeasibilityState,
  transitionId: String,
  acceptedLines: List<PlanBundleFeasibilityAcceptedLine>,
  conflicts: List<PlanBundleFeasibilityConflict>,
  originalValue: String,
): FeatureTaskRuntimePlanBundleFeasibilityState? {
  val launch = state.activeLaunch?.takeIf { it.transitionId == transitionId } ?: return null
  if (launch.kind != PlanBundleFeasibilityLaunchKind.REVIEW || !state.matchesRetainedContext(launch)) return null
  val completed =
    PlanBundleFeasibilitySettledLaunch(
      transitionId = launch.transitionId,
      phaseId = launch.phaseId,
      attempt = launch.attempt,
      kind = launch.kind,
      baselineIdentity = launch.baselineIdentity,
      repairDirectory = launch.repairDirectory,
      intendedAfterIdentity = launch.intendedAfterIdentity,
      outcome = PlanBundleFeasibilityLaunchOutcome.COMPLETED,
      value = originalValue,
      cycleId = launch.cycleId,
      sequence = launch.sequence,
      repositoryBaseline = launch.repositoryBaseline,
      repairBeforeIdentity = launch.repairBeforeIdentity,
    )
  return state.copy(
    revision = state.revision + 1,
    mode =
      if (conflicts.isNotEmpty()) {
        if (state.hasRepairAndReviewSlots()) {
          PlanBundleFeasibilityMode.REPAIR
        } else {
          PlanBundleFeasibilityMode.BLOCKED
        }
      } else {
        PlanBundleFeasibilityMode.REVIEW
      },
    identity = launch.baselineIdentity,
    acceptedLines = acceptedLines.toList(),
    conflicts = conflicts.toList(),
    requiredRefs = launch.baselineIdentity.subtasks.flatMap { it.criterionRefs },
    carriedRefs = emptyList(),
    changedSubtaskIds = emptyList(),
    activeLaunch = null,
    settledLaunches = state.settledLaunches + completed,
    lastReviewValue = originalValue,
    approvalCandidate = null,
  )
}

public fun finalizeCurrentPlanBundleFeasibility(
  state: FeatureTaskRuntimePlanBundleFeasibilityState,
  projection: PlanBundleFeasibilityReviewProjection,
  candidate: PlanBundleFeasibilityApprovalCandidate,
): FeatureTaskRuntimePlanBundleFeasibilityState? {
  val refs = projection.identity.subtasks.flatMap { it.criterionRefs }
  if (!state.canFinalize(projection) || !candidate.matchesRuntimeCompletion(state, projection)) return null
  val sequence =
    (
      state.settledLaunches.filter {
        it.cycleId == state.currentCycleId
      }.maxOfOrNull { it.sequence } ?: 0
    ) + 1
  val transition =
    PlanBundleFeasibilitySettledLaunch(
      transitionId = candidate.transitionId,
      phaseId = candidate.phaseId,
      attempt = candidate.attempt,
      kind = PlanBundleFeasibilityLaunchKind.REVIEW,
      baselineIdentity = projection.identity,
      repairDirectory = null,
      intendedAfterIdentity = null,
      outcome = PlanBundleFeasibilityLaunchOutcome.COMPLETED,
      value = PLAN_BUNDLE_FEASIBILITY_RUNTIME_COMPLETION_VALUE,
      cycleId = state.currentCycleId,
      sequence = sequence,
    )
  return state.copy(
    revision = state.revision + 1,
    mode = PlanBundleFeasibilityMode.REVIEW,
    identity = projection.identity,
    acceptedLines = projection.carriedLines,
    conflicts = emptyList(),
    changedSubtaskIds = projection.changedSubtaskIds,
    requiredRefs = refs,
    carriedRefs = projection.carriedRefs,
    activeLaunch = null,
    settledLaunches = state.settledLaunches + transition,
    approval = null,
    approvalCandidate = candidate,
  )
}

private fun FeatureTaskRuntimePlanBundleFeasibilityState.canFinalize(
  projection: PlanBundleFeasibilityReviewProjection,
): Boolean {
  val idle = mode == PlanBundleFeasibilityMode.REVIEW && activeLaunch == null && conflicts.isEmpty()
  val retained = identity == projection.identity && acceptedLines == projection.carriedLines
  val reviewed = !currentCycleId.isNullOrBlank() && !lastReviewValue.isNullOrBlank()
  return idle && retained && reviewed && projection.hasCompleteCarriedEvidence()
}

private fun PlanBundleFeasibilityReviewProjection.hasCompleteCarriedEvidence(): Boolean {
  val refs = identity.subtasks.flatMap { it.criterionRefs }
  val fullCoverage = requiredRefs.isEmpty() && carriedRefs == refs && carriedLines.map { it.ref } == refs
  return fullCoverage && carriedLines.all { it.matchesCurrent(identity) }
}

private fun PlanBundleFeasibilityApprovalCandidate.matchesRuntimeCompletion(
  state: FeatureTaskRuntimePlanBundleFeasibilityState,
  projection: PlanBundleFeasibilityReviewProjection,
): Boolean {
  val validContext = transitionId.isNotBlank() && phaseId.isNotBlank() && attempt > 0 && normalizedEnvelope.isNotBlank()
  val currentEvidence =
    sourceRevision == state.revision + 1 &&
      immutableFingerprint == projection.identity.immutableFingerprint() && reviewerAgentId == "runtime"
  return validContext && currentEvidence && state.settledLaunches.none { it.transitionId == transitionId }
}

private fun PlanBundleFeasibilityAcceptedLine.matchesCurrent(identity: PlanBundleFeasibilityIdentity): Boolean {
  val subtask = identity.subtasks.firstOrNull { ref in it.criterionRefs } ?: return false
  val byId = identity.subtasks.associateBy { it.id }
  val dependencies =
    identity.transitiveDependencyIds(subtask.id).map { id ->
      PlanBundleFeasibilityDependencyHash(id, byId[id]?.specSha256.orEmpty())
    }
  val directDependencies =
    subtask.dependencies.map { dependency ->
      PlanBundleFeasibilityDependencyHash(dependency.subtaskId, byId[dependency.subtaskId]?.specSha256.orEmpty())
    }
  return judgedParentSha256 == identity.parentSha256 && judgedManifestSha256 == identity.manifestSha256 &&
    judgedSpecSha256 == subtask.specSha256 &&
    (dependencyHashes == dependencies || dependencyHashes == directDependencies)
}

private fun PlanBundleFeasibilityIdentity.immutableFingerprint(): String =
  sha256HexUtf8(
    listOf(parentSha256, immutableManifestSha256)
      .plus(subtasks.map { "${it.id}:${it.specPath}:${it.specSha256}" })
      .joinToString("\n"),
  )

public fun publishPlanBundleFeasibilityApproval(
  state: FeatureTaskRuntimePlanBundleFeasibilityState,
  phaseId: String,
  attempt: Int,
  expectedRevision: Int,
): FeatureTaskRuntimePlanBundleFeasibilityState? {
  val candidate = state.approvalCandidate ?: return null
  if (!state.isUnpublishedApprovalState(expectedRevision) ||
    !approvalCandidateMatchesFence(state, candidate, phaseId, attempt, expectedRevision)
  ) {
    return null
  }
  return state.copy(
    revision = state.revision + 1,
    mode = PlanBundleFeasibilityMode.APPROVED,
    approval =
      PlanBundleFeasibilityApproval(
        candidate.immutableFingerprint,
        state.acceptedLines.map { it.line },
        candidate.transitionId,
      ),
  )
}

public fun isPlanBundleFeasibilityApprovalCandidateEligible(
  state: FeatureTaskRuntimePlanBundleFeasibilityState,
  phaseId: String,
  attempt: Int,
  expectedRevision: Int,
  immutableFingerprint: String,
): Boolean {
  val candidate = state.approvalCandidate ?: return false
  return candidate.immutableFingerprint == immutableFingerprint &&
    state.isUnpublishedApprovalState(expectedRevision) &&
    approvalCandidateMatchesFence(state, candidate, phaseId, attempt, expectedRevision)
}

public fun isPublishedPlanBundleFeasibilityApprovalReplay(
  state: FeatureTaskRuntimePlanBundleFeasibilityState,
  phaseId: String,
  attempt: Int,
  expectedRevision: Int,
  transitionId: String,
): Boolean {
  val candidate = state.approvalCandidate ?: return false
  val approval = state.approval ?: return false
  val matchingPublication =
    state.revision == expectedRevision + 1 &&
      state.mode == PlanBundleFeasibilityMode.APPROVED && state.activeLaunch == null && state.conflicts.isEmpty()
  val matchingEvidence =
    approval.transitionId == transitionId && candidate.transitionId == transitionId &&
      approval.immutableFingerprint == candidate.immutableFingerprint &&
      approval.criterionLines == state.acceptedLines.map { it.line }
  if (!matchingPublication || !matchingEvidence ||
    !approvalCandidateMatchesFence(
      state.copy(revision = expectedRevision, mode = PlanBundleFeasibilityMode.REVIEW),
      candidate,
      phaseId,
      attempt,
      expectedRevision,
    )
  ) {
    return false
  }
  return true
}

private fun approvalCandidateMatchesFence(
  state: FeatureTaskRuntimePlanBundleFeasibilityState,
  candidate: PlanBundleFeasibilityApprovalCandidate,
  phaseId: String,
  attempt: Int,
  expectedRevision: Int,
): Boolean {
  val matchesRevision = state.revision == expectedRevision && candidate.sourceRevision <= expectedRevision
  val matchesAttempt = candidate.phaseId == phaseId && candidate.attempt == attempt && state.currentCycleId != null
  if (!matchesRevision || !matchesAttempt) return false
  val transition = state.settledLaunches.singleOrNull { it.transitionId == candidate.transitionId } ?: return false
  return state.matchesApprovingTransition(transition, candidate) &&
    state.hasCompleteApprovalCoverage(transition) &&
    state.hasCompatibleReopenings(candidate, expectedRevision)
}

private fun FeatureTaskRuntimePlanBundleFeasibilityState.matchesApprovingTransition(
  transition: PlanBundleFeasibilitySettledLaunch,
  candidate: PlanBundleFeasibilityApprovalCandidate,
): Boolean {
  val runtimeCompletion = candidate.reviewerAgentId == "runtime"
  val matchesReview =
    transition.kind == PlanBundleFeasibilityLaunchKind.REVIEW &&
      transition.outcome == PlanBundleFeasibilityLaunchOutcome.COMPLETED &&
      if (runtimeCompletion) {
        transition.value == PLAN_BUNDLE_FEASIBILITY_RUNTIME_COMPLETION_VALUE && lastReviewValue != null
      } else {
        transition.value == lastReviewValue
      }
  val matchesAttempt =
    transition.phaseId == candidate.phaseId && transition.attempt == candidate.attempt &&
      transition.cycleId == currentCycleId
  val hasLaterLaunch = settledLaunches.any { it.cycleId == currentCycleId && it.sequence > transition.sequence }
  return matchesReview && matchesAttempt && !hasLaterLaunch
}

private fun FeatureTaskRuntimePlanBundleFeasibilityState.hasCompleteApprovalCoverage(
  transition: PlanBundleFeasibilitySettledLaunch,
): Boolean {
  val refs = identity.subtasks.flatMap { it.criterionRefs }
  val matchingRefs =
    requiredRefs == refs && acceptedLines.map { it.ref } == refs &&
      carriedRefs == refs.filter { it in carriedRefs } && refs.isNotEmpty()
  if (!matchingRefs || acceptedLines.any { it.line.startsWith("conflict ") }) return false
  val judgedById = transition.baselineIdentity.subtasks.associateBy { it.id }
  return acceptedLines.all { line ->
    val subtask = judgedById.values.firstOrNull { line.ref in it.criterionRefs } ?: return@all false
    val matchingIdentity =
      line.judgedParentSha256 == transition.baselineIdentity.parentSha256 &&
        line.judgedManifestSha256 == transition.baselineIdentity.manifestSha256 &&
        line.judgedSpecSha256 == subtask.specSha256
    val transitive =
      transition.baselineIdentity.transitiveDependencyIds(subtask.id).map { dependencyId ->
        PlanBundleFeasibilityDependencyHash(
          dependencyId,
          judgedById[dependencyId]?.specSha256.orEmpty(),
        )
      }
    val direct =
      subtask.dependencies.map { dependency ->
        PlanBundleFeasibilityDependencyHash(
          dependency.subtaskId,
          judgedById[dependency.subtaskId]?.specSha256.orEmpty(),
        )
      }
    matchingIdentity && (line.dependencyHashes == transitive || line.dependencyHashes == direct)
  }
}

private fun FeatureTaskRuntimePlanBundleFeasibilityState.hasCompatibleReopenings(
  candidate: PlanBundleFeasibilityApprovalCandidate,
  expectedRevision: Int,
): Boolean {
  val intervening =
    reopenings.filter { reopening ->
      reopening.sourceRevision?.let { it >= candidate.sourceRevision && it < expectedRevision } == true
    }.sortedBy { it.sourceRevision }
  if (expectedRevision - candidate.sourceRevision != intervening.size) return false
  return intervening.withIndex().all { (index, reopening) ->
    val matchesSource =
      reopening.sourceRevision == candidate.sourceRevision + index &&
        reopening.sourceAttempt == candidate.attempt &&
        reopening.reason == PLAN_BUNDLE_FEASIBILITY_FULL_V2_REVIEW_ADOPTION_REASON
    val original = reopening.originalPhaseRecord
    val matchesRecord =
      original.phaseId == candidate.phaseId && original.attemptCount == candidate.attempt &&
        original.resolvedAgentId == candidate.reviewerAgentId
    val ordered = index == 0 || reopening.retryLedgerSequence > intervening[index - 1].retryLedgerSequence
    matchesSource && matchesRecord && ordered
  }
}

private fun FeatureTaskRuntimePlanBundleFeasibilityState.isUnpublishedApprovalState(expectedRevision: Int): Boolean {
  val matchesRevision = revision == expectedRevision && mode == PlanBundleFeasibilityMode.REVIEW
  return matchesRevision && activeLaunch == null && conflicts.isEmpty() && approval == null
}

internal const val MAX_REVIEW_ROUNDS = 3
internal const val MAX_REPAIR_LAUNCHES = 2

internal fun FeatureTaskRuntimePlanBundleFeasibilityState.hasRepairAndReviewSlots(): Boolean =
  repairLaunchesStarted < MAX_REPAIR_LAUNCHES && reviewRoundsStarted < MAX_REVIEW_ROUNDS

public fun reopenApprovedPlanBundleFeasibilityCycle(
  state: FeatureTaskRuntimePlanBundleFeasibilityState,
  identity: PlanBundleFeasibilityIdentity,
  cycleId: String,
  retryLedgerSequence: Int,
  phaseRecord: FeatureTaskRuntimePhaseRecord,
): FeatureTaskRuntimePlanBundleFeasibilityState? {
  if (!state.canReopenApprovedCycle(cycleId, retryLedgerSequence)) return null
  val priorCycleId = state.currentCycleId ?: state.settledLaunches.lastOrNull()?.cycleId ?: return null
  val history =
    PlanBundleFeasibilityCycleHistory(
      priorCycleId,
      state.identity,
      state.acceptedLines.toList(),
      state.conflicts.toList(),
      state.reviewRoundsStarted,
      state.repairLaunchesStarted,
      state.settledLaunches.filter { it.cycleId == priorCycleId },
      state.lastReviewValue,
      state.approval,
    )
  return state.copy(
    revision = state.revision + 1,
    mode = PlanBundleFeasibilityMode.REVIEW,
    identity = identity,
    acceptedLines = state.acceptedLines.filter { line -> identity.subtasks.any { line.ref in it.criterionRefs } },
    conflicts = emptyList(),
    changedSubtaskIds = emptyList(),
    requiredRefs = identity.subtasks.flatMap { it.criterionRefs },
    carriedRefs = emptyList(),
    reviewRoundsStarted = 0,
    repairLaunchesStarted = 0,
    activeLaunch = null,
    lastReviewValue = state.lastReviewValue,
    approval = null,
    approvalCandidate = null,
    currentCycleId = cycleId,
    cycleHistory = state.cycleHistory + history,
    reopenings =
      state.reopenings +
        PlanBundleFeasibilityReopening(
          phaseRecord.attemptCount,
          state.revision,
          retryLedgerSequence,
          "approved-cycle-reopened",
          phaseRecord,
        ),
  )
}

public fun refusePlanBundleFeasibilityReview(
  state: FeatureTaskRuntimePlanBundleFeasibilityState,
  transitionId: String,
  reason: String,
): FeatureTaskRuntimePlanBundleFeasibilityState? {
  val launch = state.activeLaunch?.takeIf { it.transitionId == transitionId } ?: return null
  if (launch.kind != PlanBundleFeasibilityLaunchKind.REVIEW) return null
  val refused =
    PlanBundleFeasibilitySettledLaunch(
      transitionId = launch.transitionId,
      phaseId = launch.phaseId,
      attempt = launch.attempt,
      kind = launch.kind,
      baselineIdentity = launch.baselineIdentity,
      repairDirectory = launch.repairDirectory,
      intendedAfterIdentity = launch.intendedAfterIdentity,
      outcome = PlanBundleFeasibilityLaunchOutcome.REFUSED,
      value = reason,
      cycleId = launch.cycleId,
      sequence = launch.sequence,
      repositoryBaseline = launch.repositoryBaseline,
      repairBeforeIdentity = launch.repairBeforeIdentity,
    )
  return state.copy(
    revision = state.revision + 1,
    mode =
      if (state.reviewRoundsStarted < MAX_REVIEW_ROUNDS) {
        PlanBundleFeasibilityMode.REVIEW
      } else {
        PlanBundleFeasibilityMode.BLOCKED
      },
    activeLaunch = null,
    settledLaunches = state.settledLaunches + refused,
  )
}

public fun settlePlanBundleFeasibilityRepair(
  state: FeatureTaskRuntimePlanBundleFeasibilityState,
  transitionId: String,
  postRepairIdentity: PlanBundleFeasibilityIdentity,
  originalValue: String,
): FeatureTaskRuntimePlanBundleFeasibilityState? {
  val launch = state.activeLaunch?.takeIf { it.transitionId == transitionId } ?: return null
  if (launch.kind != PlanBundleFeasibilityLaunchKind.REPAIR || !state.matchesRetainedContext(launch)) return null
  if (launch.intendedAfterIdentity != postRepairIdentity || launch.intendedRepairValue != originalValue) return null
  val completed =
    PlanBundleFeasibilitySettledLaunch(
      transitionId = launch.transitionId,
      phaseId = launch.phaseId,
      attempt = launch.attempt,
      kind = launch.kind,
      baselineIdentity = launch.baselineIdentity,
      repairDirectory = launch.repairDirectory,
      intendedAfterIdentity = postRepairIdentity,
      outcome = PlanBundleFeasibilityLaunchOutcome.COMPLETED,
      value = originalValue,
      cycleId = launch.cycleId,
      sequence = launch.sequence,
      repositoryBaseline = launch.repositoryBaseline,
      repairBeforeIdentity = launch.repairBeforeIdentity,
    )
  return state.copy(
    revision = state.revision + 1,
    mode = PlanBundleFeasibilityMode.REVIEW,
    identity = postRepairIdentity,
    acceptedLines =
      state.acceptedLines.filter {
          line ->
        postRepairIdentity.subtasks.any { line.ref in it.criterionRefs }
      },
    requiredRefs = postRepairIdentity.subtasks.flatMap { it.criterionRefs },
    carriedRefs = emptyList(),
    changedSubtaskIds = emptyList(),
    approval = null,
    activeLaunch = null,
    settledLaunches = state.settledLaunches + completed,
  )
}

public fun refusePlanBundleFeasibilityRepair(
  state: FeatureTaskRuntimePlanBundleFeasibilityState,
  transitionId: String,
  reason: String,
): FeatureTaskRuntimePlanBundleFeasibilityState? {
  val launch = state.activeLaunch?.takeIf { it.transitionId == transitionId } ?: return null
  if (launch.kind != PlanBundleFeasibilityLaunchKind.REPAIR) return null
  val refused =
    PlanBundleFeasibilitySettledLaunch(
      transitionId = launch.transitionId,
      phaseId = launch.phaseId,
      attempt = launch.attempt,
      kind = launch.kind,
      baselineIdentity = launch.baselineIdentity,
      repairDirectory = launch.repairDirectory,
      intendedAfterIdentity = launch.intendedAfterIdentity,
      outcome = PlanBundleFeasibilityLaunchOutcome.REFUSED,
      value = reason,
      cycleId = launch.cycleId,
      sequence = launch.sequence,
      repositoryBaseline = launch.repositoryBaseline,
      repairBeforeIdentity = launch.repairBeforeIdentity,
    )
  val canRetry = state.hasRepairAndReviewSlots()
  return state.copy(
    revision = state.revision + 1,
    mode = if (canRetry) PlanBundleFeasibilityMode.REPAIR else PlanBundleFeasibilityMode.BLOCKED,
    activeLaunch = null,
    settledLaunches = state.settledLaunches + refused,
  )
}

public fun blockPlanBundleFeasibilityLaunch(
  state: FeatureTaskRuntimePlanBundleFeasibilityState,
  transitionId: String,
  reason: String,
): FeatureTaskRuntimePlanBundleFeasibilityState? {
  val launch = state.activeLaunch?.takeIf { it.transitionId == transitionId } ?: return null
  val blocked =
    PlanBundleFeasibilitySettledLaunch(
      transitionId = launch.transitionId,
      phaseId = launch.phaseId,
      attempt = launch.attempt,
      kind = launch.kind,
      baselineIdentity = launch.baselineIdentity,
      repairDirectory = launch.repairDirectory,
      intendedAfterIdentity = launch.intendedAfterIdentity,
      outcome = PlanBundleFeasibilityLaunchOutcome.BLOCKED,
      value = reason,
      cycleId = launch.cycleId,
      sequence = launch.sequence,
      repositoryBaseline = launch.repositoryBaseline,
      repairBeforeIdentity = launch.repairBeforeIdentity,
    )
  return state.copy(
    revision = state.revision + 1,
    mode = PlanBundleFeasibilityMode.BLOCKED,
    activeLaunch = null,
    settledLaunches = state.settledLaunches + blocked,
  )
}

private fun FeatureTaskRuntimePlanBundleFeasibilityState.canReopenApprovedCycle(
  cycleId: String,
  retryLedgerSequence: Int,
): Boolean {
  if (invalidReason() != null) return false
  val approval = approval ?: return false
  val candidate = approvalCandidate ?: return false
  val approvingTransition = settledLaunches.singleOrNull { it.transitionId == approval.transitionId }
  val validCycle =
    cycleId.isNotBlank() && cycleId != currentCycleId &&
      cycleHistory.none { it.cycleId == cycleId } && retryLedgerSequence >= 0
  val validApproval =
    mode == PlanBundleFeasibilityMode.APPROVED && activeLaunch == null &&
      approval.transitionId == candidate.transitionId && conflicts.isEmpty()
  return validCycle && validApproval && approvingTransition?.let { matchesApprovingTransition(it, candidate) } == true
}
