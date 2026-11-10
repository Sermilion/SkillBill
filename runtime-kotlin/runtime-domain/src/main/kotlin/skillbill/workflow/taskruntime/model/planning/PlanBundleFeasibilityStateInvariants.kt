package skillbill.workflow.taskruntime.model.planning

private const val MAX_REVIEW_ROUNDS = 3
private const val MAX_REPAIR_LAUNCHES = 2
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
    (acceptedRefs + conflictRefs + requiredRefs + carriedRefs).any { it !in refs } -> "unknown criterion reference"
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
    approval?.let {
      it.transitionId.isBlank() || it.criterionLines.size != identity.subtasks.sumOf { it.criterionRefs.size }
    }
      == true -> "invalid approval"
    else -> null
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
    activeLaunch?.let { !validLaunchIdentity(it.baselineIdentity, it.intendedAfterIdentity) } == true ->
      "invalid launch identity"
    settledLaunches.any { !validLaunchIdentity(it.baselineIdentity, it.intendedAfterIdentity) } ->
      "invalid launch identity"
    else -> null
  }

private fun validLaunchIdentity(
  baseline: PlanBundleFeasibilityIdentity,
  intended: PlanBundleFeasibilityIdentity?,
): Boolean = baseline.valid() && intended?.valid() != false

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
  if (subtasks.map { it.id }.distinct().size != subtasks.size) return false
  val prior = mutableSetOf<Int>()
  return subtasks.all { subtask ->
    val valid = subtask.valid() && subtask.dependencies.all { it.subtaskId in prior }
    prior += subtask.id
    valid
  }
}

private fun PlanBundleFeasibilitySubtaskIdentity.valid(): Boolean {
  val validIdentity = id > 0 && specPath.isNotBlank() && SHA256.matches(specSha256)
  val validRefs =
    criterionRefs.distinct().size == criterionRefs.size &&
      criterionRefs == criterionRefs.indices.map { index -> "$id.${index + 1}" }
  val uniqueDependencies = dependencies.map { it.subtaskId }.distinct().size == dependencies.size
  return validIdentity && validRefs && uniqueDependencies
}
