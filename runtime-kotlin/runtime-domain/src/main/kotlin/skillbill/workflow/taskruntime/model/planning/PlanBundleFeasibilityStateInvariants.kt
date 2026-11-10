package skillbill.workflow.taskruntime.model.planning

import skillbill.text.sha256HexUtf8

private val SHA256 = Regex("[0-9a-f]{64}")

internal fun invalidFeasibilityStateReason(state: FeatureTaskRuntimePlanBundleFeasibilityState): String? =
  with(state) {
    when {
      revision < 0 || reviewRoundsStarted !in 0..MAX_REVIEW_ROUNDS ||
        repairLaunchesStarted !in 0..MAX_REPAIR_LAUNCHES -> "invalid counters"
      !identity.valid() -> "invalid bundle identity"
      else -> invalidReferencesReason() ?: invalidEvidenceReason() ?: invalidLaunchesReason()
    }
  }

private fun FeatureTaskRuntimePlanBundleFeasibilityState.invalidReferencesReason(): String? {
  val refs = identity.subtasks.flatMap { it.criterionRefs }
  val acceptedRefs = acceptedLines.map { it.ref }
  val conflictRefs = conflicts.map { it.ref }
  val referenceLists = listOf(refs, requiredRefs, carriedRefs, acceptedRefs, conflictRefs)
  return when {
    referenceLists.any { it.distinct().size != it.size } -> "duplicate criterion reference"
    (acceptedRefs + requiredRefs + carriedRefs).any { it !in refs } -> "unknown criterion reference"
    (acceptedRefs + conflictRefs).distinct().size != acceptedRefs.size + conflictRefs.size ->
      "criterion cannot be both accepted and conflicted"
    else -> null
  }
}

private fun FeatureTaskRuntimePlanBundleFeasibilityState.invalidEvidenceReason(): String? =
  when {
    acceptedLines.any { !it.valid() } -> "invalid accepted evidence identity"
    changedSubtaskIds.distinct().size != changedSubtaskIds.size ||
      changedSubtaskIds.any { id -> identity.subtasks.none { it.id == id } } -> "invalid changed subtask ids"
    approval?.let { !validApproval(it) } == true -> "invalid approval"
    mode == PlanBundleFeasibilityMode.APPROVED && approval == null -> "approved mode lacks approval evidence"
    approvalCandidate?.let { !validApprovalCandidate(it) } == true ->
      "invalid approval candidate"
    else -> invalidHistoryReason()
  }

private fun FeatureTaskRuntimePlanBundleFeasibilityState.invalidHistoryReason(): String? =
  when {
    currentCycleId?.isBlank() == true || cycleHistory.map { it.cycleId }.distinct().size != cycleHistory.size ->
      "invalid feasibility cycle history"
    reopenings.any { it.sourceAttempt < 1 || it.retryLedgerSequence < 0 || it.reason.isBlank() } ->
      "invalid feasibility reopening history"
    else -> null
  }

private fun FeatureTaskRuntimePlanBundleFeasibilityState.validApproval(
  approval: PlanBundleFeasibilityApproval,
): Boolean {
  val validIdentity = approval.transitionId.isNotBlank() && SHA256.matches(approval.immutableFingerprint)
  val validCoverage =
    approval.criterionLines.size == identity.subtasks.sumOf { it.criterionRefs.size } &&
      approval.criterionLines == acceptedLines.map { it.line } && conflicts.isEmpty()
  return validIdentity && validCoverage && mode == PlanBundleFeasibilityMode.APPROVED &&
    approvalTransitionMatches(approval)
}

private fun FeatureTaskRuntimePlanBundleFeasibilityState.validApprovalCandidate(
  candidate: PlanBundleFeasibilityApprovalCandidate,
): Boolean {
  val validIdentity =
    candidate.sourceRevision <= revision && candidate.transitionId.isNotBlank() &&
      candidate.phaseId.isNotBlank() && candidate.attempt > 0
  val validEvidence =
    candidate.reviewerAgentId.isNotBlank() && candidate.normalizedEnvelope.isNotBlank() &&
      SHA256.matches(candidate.immutableFingerprint)
  return validIdentity && validEvidence && candidateTransitionMatches(candidate)
}

private fun FeatureTaskRuntimePlanBundleFeasibilityState.approvalTransitionMatches(
  approval: PlanBundleFeasibilityApproval,
): Boolean {
  val candidate = approvalCandidate ?: return false
  return approval.transitionId == candidate.transitionId && candidateTransitionMatches(candidate)
}

private fun FeatureTaskRuntimePlanBundleFeasibilityState.candidateTransitionMatches(
  candidate: PlanBundleFeasibilityApprovalCandidate,
): Boolean {
  val transition = settledLaunches.singleOrNull { it.transitionId == candidate.transitionId } ?: return false
  return transition.kind == PlanBundleFeasibilityLaunchKind.REVIEW &&
    transition.outcome == PlanBundleFeasibilityLaunchOutcome.COMPLETED && transition.phaseId == candidate.phaseId &&
    transition.attempt == candidate.attempt && transition.value == lastReviewValue &&
    currentCycleId != null && transition.cycleId == currentCycleId
}

private fun PlanBundleFeasibilityAcceptedLine.valid(): Boolean =
  listOf(judgedParentSha256, judgedManifestSha256, judgedSpecSha256).all(SHA256::matches) &&
    dependencyHashes.all { it.subtaskId > 0 && SHA256.matches(it.specSha256) } &&
    dependencyHashes.map { it.subtaskId }.distinct().size == dependencyHashes.size

private fun FeatureTaskRuntimePlanBundleFeasibilityState.invalidLaunchesReason(): String? {
  val settledIds = settledLaunches.map { it.transitionId }
  return when {
    settledIds.distinct().size != settledIds.size || activeLaunch?.transitionId in settledIds ->
      "duplicate launch identity"
    settledLaunches.any { !validLaunchContext(it.transitionId, it.phaseId, it.attempt) } -> "invalid settled launch"
    activeLaunch?.let {
      !validLaunchContext(
        it.transitionId,
        it.phaseId,
        it.attempt,
      )
    } == true -> "invalid active launch"
    else -> invalidLaunchIdentityReason()
  }
}

private fun validLaunchContext(
  transitionId: String,
  phaseId: String,
  attempt: Int,
): Boolean = transitionId.isNotBlank() && phaseId.isNotBlank() && attempt > 0

private fun FeatureTaskRuntimePlanBundleFeasibilityState.invalidLaunchIdentityReason(): String? =
  when {
    allLaunches().any { (it.cycleId == null) != (it.sequence == 0) } -> "incomplete launch cycle identity"
    allLaunches().any { it.cycleId != null && (it.cycleId.isBlank() || it.sequence < 1) } -> "invalid launch sequence"
    allLaunches().mapNotNull { it.cycleId?.let { cycle -> cycle to it.sequence } }.distinct().size !=
      allLaunches().count { it.cycleId != null } -> "duplicate cycle launch sequence"
    activeLaunch?.let { !validLaunchIdentity(it.baselineIdentity, it.intendedAfterIdentity) } == true ->
      "invalid launch identity"
    allLaunches().any { it.repairBeforeIdentity?.let { identity -> !identity.valid() } == true } ->
      "invalid repair before identity"
    allLaunches().any { !validRepositoryBaseline(it.repositoryBaseline) } -> "invalid repository baseline"
    settledLaunches.any { !validLaunchIdentity(it.baselineIdentity, it.intendedAfterIdentity) } ->
      "invalid launch identity"
    else -> null
  }

private fun FeatureTaskRuntimePlanBundleFeasibilityState.allLaunches(): List<PlanBundleFeasibilityLaunch> =
  settledLaunches.map { launch ->
    PlanBundleFeasibilityLaunch(
      launch.transitionId,
      launch.phaseId,
      launch.attempt,
      launch.kind,
      launch.baselineIdentity,
      launch.repairDirectory,
      launch.intendedAfterIdentity,
      launch.cycleId,
      launch.sequence,
      repositoryBaseline = launch.repositoryBaseline,
      repairBeforeIdentity = launch.repairBeforeIdentity,
    )
  } + listOfNotNull(activeLaunch)

private fun validLaunchIdentity(
  baseline: PlanBundleFeasibilityIdentity,
  intended: PlanBundleFeasibilityIdentity?,
): Boolean = baseline.valid() && intended?.valid() != false

private fun validRepositoryBaseline(baseline: PlanBundleFeasibilityRepositoryBaseline?): Boolean =
  baseline == null || (
    baseline.headCommit.isNotBlank() &&
      baseline.dirtyPaths.distinct().size == baseline.dirtyPaths.size &&
      baseline.dirtyPaths.all(String::isNotBlank) &&
      baseline.contentIdentities.keys.all(baseline.dirtyPaths.toSet()::contains) &&
      baseline.contentIdentities.values.all(String::isNotBlank)
  )

internal fun invalidFeasibilityInitializationReason(state: FeatureTaskRuntimePlanBundleFeasibilityState): String? =
  with(state) {
    when {
      revision != 0 || mode != PlanBundleFeasibilityMode.REVIEW ->
        "initial state must start at revision zero in review mode"
      reviewRoundsStarted != 0 || repairLaunchesStarted != 0 -> "initial state cannot charge launches"
      activeLaunch != null || settledLaunches.isNotEmpty() -> "initial state cannot contain launches"
      else -> invalidInitialEvidenceReason()
    }
  }

private fun FeatureTaskRuntimePlanBundleFeasibilityState.invalidInitialEvidenceReason(): String? =
  when {
    acceptedLines.isNotEmpty() || conflicts.isNotEmpty() -> "initial state cannot contain review evidence"
    changedSubtaskIds.isNotEmpty() || carriedRefs.isNotEmpty() -> "initial state cannot carry incremental scope"
    approval != null || lastReviewValue != null -> "initial state cannot contain review results"
    requiredRefs != identity.subtasks.flatMap { it.criterionRefs } -> "initial state must require every criterion"
    else -> null
  }

private fun PlanBundleFeasibilityIdentity.valid(): Boolean {
  if (!listOf(parentSha256, manifestSha256, immutableManifestSha256).all(SHA256::matches)) return false
  if (rawParentText?.let { sha256HexUtf8(it) != parentSha256 } == true ||
    rawManifestText?.let { sha256HexUtf8(it) != manifestSha256 } == true
  ) {
    return false
  }
  val rawMaterialPresent =
    listOfNotNull(rawParentText, rawManifestText).size + subtasks.count { it.rawSpecText != null }
  if (rawMaterialPresent != 0 && rawMaterialPresent != subtasks.size + 2) return false
  val prior = mutableSetOf<Int>()
  return subtasks.map { it.id }.distinct().size == subtasks.size &&
    subtasks.all { subtask ->
      val valid = subtask.valid() && subtask.dependencies.all { it.subtaskId in prior }
      prior += subtask.id
      valid
    }
}

private fun PlanBundleFeasibilitySubtaskIdentity.valid(): Boolean {
  val validIdentity =
    id > 0 && specPath.isNotBlank() && SHA256.matches(specSha256) &&
      (rawSpecText == null || sha256HexUtf8(rawSpecText) == specSha256)
  val validRefs =
    criterionRefs.distinct().size == criterionRefs.size &&
      criterionRefs == criterionRefs.indices.map { index -> "$id.${index + 1}" }
  val uniqueDependencies = dependencies.map { it.subtaskId }.distinct().size == dependencies.size
  return validIdentity && validRefs && uniqueDependencies
}

public fun isPlanBundleFeasibilityLifecycleTransitionValid(
  current: FeatureTaskRuntimePlanBundleFeasibilityState,
  proposed: FeatureTaskRuntimePlanBundleFeasibilityState,
  transitionId: String?,
): Boolean {
  val currentLaunch = current.activeLaunch
  val proposedLaunch = proposed.activeLaunch
  if (currentLaunch == null && proposedLaunch != null) {
    return validFeasibilityAdmission(current, proposed, proposedLaunch, transitionId)
  }
  if (currentLaunch != null && proposedLaunch != null) {
    return currentLaunch.matchesContext(proposedLaunch) && current.mode == proposed.mode &&
      proposedLaunch.transitionId == transitionId &&
      proposed.settledLaunches == current.settledLaunches
  }
  if (currentLaunch == null || proposedLaunch != null || currentLaunch.transitionId != transitionId) return false
  return validFeasibilitySettlement(current, proposed, currentLaunch)
}

private fun validFeasibilitySettlement(
  current: FeatureTaskRuntimePlanBundleFeasibilityState,
  proposed: FeatureTaskRuntimePlanBundleFeasibilityState,
  currentLaunch: PlanBundleFeasibilityLaunch,
): Boolean {
  val settlement = proposed.settledLaunches.drop(current.settledLaunches.size).singleOrNull() ?: return false
  if (!settlement.matchesContext(currentLaunch)) return false
  val expectedMode = feasibilitySettlementMode(proposed, currentLaunch, settlement.outcome)
  return proposed.mode == expectedMode
}

private fun feasibilitySettlementMode(
  proposed: FeatureTaskRuntimePlanBundleFeasibilityState,
  launch: PlanBundleFeasibilityLaunch,
  outcome: PlanBundleFeasibilityLaunchOutcome,
): PlanBundleFeasibilityMode {
  return when (launch.kind) {
    PlanBundleFeasibilityLaunchKind.REVIEW ->
      when (outcome) {
        PlanBundleFeasibilityLaunchOutcome.COMPLETED ->
          if (proposed.conflicts.isNotEmpty()) {
            if (proposed.hasRepairAndReviewSlots()) {
              PlanBundleFeasibilityMode.REPAIR
            } else {
              PlanBundleFeasibilityMode.BLOCKED
            }
          } else {
            PlanBundleFeasibilityMode.REVIEW
          }
        PlanBundleFeasibilityLaunchOutcome.REFUSED ->
          if (proposed.reviewRoundsStarted < MAX_REVIEW_ROUNDS) {
            PlanBundleFeasibilityMode.REVIEW
          } else {
            PlanBundleFeasibilityMode.BLOCKED
          }
        PlanBundleFeasibilityLaunchOutcome.BLOCKED ->
          PlanBundleFeasibilityMode.BLOCKED
      }
    PlanBundleFeasibilityLaunchKind.REPAIR ->
      when (outcome) {
        PlanBundleFeasibilityLaunchOutcome.COMPLETED ->
          PlanBundleFeasibilityMode.REVIEW
        PlanBundleFeasibilityLaunchOutcome.REFUSED ->
          if (proposed.hasRepairAndReviewSlots()) {
            PlanBundleFeasibilityMode.REPAIR
          } else {
            PlanBundleFeasibilityMode.BLOCKED
          }
        PlanBundleFeasibilityLaunchOutcome.BLOCKED ->
          PlanBundleFeasibilityMode.BLOCKED
      }
  }
}

private fun validFeasibilityAdmission(
  current: FeatureTaskRuntimePlanBundleFeasibilityState,
  proposed: FeatureTaskRuntimePlanBundleFeasibilityState,
  proposedLaunch: PlanBundleFeasibilityLaunch,
  transitionId: String?,
): Boolean {
  val expectedMode =
    when (proposedLaunch.kind) {
      PlanBundleFeasibilityLaunchKind.REVIEW -> PlanBundleFeasibilityMode.REVIEW
      PlanBundleFeasibilityLaunchKind.REPAIR -> PlanBundleFeasibilityMode.REPAIR
    }
  return current.mode == expectedMode && proposed.mode == expectedMode &&
    proposedLaunch.transitionId == transitionId &&
    (
      proposedLaunch.kind != PlanBundleFeasibilityLaunchKind.REVIEW ||
        current.reviewRoundsStarted < MAX_REVIEW_ROUNDS
    ) &&
    (
      proposedLaunch.kind != PlanBundleFeasibilityLaunchKind.REPAIR ||
        (current.hasRepairAndReviewSlots())
    )
}
