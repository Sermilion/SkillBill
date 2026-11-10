package skillbill.workflow.taskruntime.model.planning

public enum class PlanBundleFeasibilityMode(public val wireValue: String) {
  REVIEW("review"),
  REPAIR("repair"),
  APPROVED("approved"),
  BLOCKED("blocked"),
}

public enum class PlanBundleFeasibilityLaunchKind(public val wireValue: String) {
  REVIEW("review"),
  REPAIR("repair"),
}

public enum class PlanBundleFeasibilityLaunchOutcome(public val wireValue: String) {
  COMPLETED("completed"),
  BLOCKED("blocked"),
  REFUSED("refused"),
}

public data class PlanBundleFeasibilityDependency(val subtaskId: Int, val optional: Boolean, val skipped: Boolean)

public data class PlanBundleFeasibilitySubtaskIdentity(
  val id: Int,
  val specPath: String,
  val specSha256: String,
  val criterionRefs: List<String>,
  val dependencies: List<PlanBundleFeasibilityDependency>,
)

public data class PlanBundleFeasibilityIdentity(
  val parentSha256: String,
  val manifestSha256: String,
  val immutableManifestSha256: String,
  val subtasks: List<PlanBundleFeasibilitySubtaskIdentity>,
)

public data class PlanBundleFeasibilityDependencyHash(val subtaskId: Int, val specSha256: String)

public data class PlanBundleFeasibilityAcceptedLine(
  val ref: String,
  val line: String,
  val judgedParentSha256: String,
  val judgedManifestSha256: String,
  val judgedSpecSha256: String,
  val dependencyHashes: List<PlanBundleFeasibilityDependencyHash>,
)

public data class PlanBundleFeasibilityConflict(val ref: String, val line: String)

public data class PlanBundleFeasibilityLaunch(
  val transitionId: String,
  val phaseId: String,
  val attempt: Int,
  val kind: PlanBundleFeasibilityLaunchKind,
  val baselineIdentity: PlanBundleFeasibilityIdentity,
  val repairDirectory: String?,
  val intendedAfterIdentity: PlanBundleFeasibilityIdentity?,
) {
  public fun matchesContext(other: PlanBundleFeasibilityLaunch): Boolean =
    transitionId == other.transitionId && phaseId == other.phaseId && attempt == other.attempt &&
      kind == other.kind && baselineIdentity == other.baselineIdentity &&
      repairDirectory == other.repairDirectory && intendedAfterIdentity == other.intendedAfterIdentity
}

public data class PlanBundleFeasibilitySettledLaunch(
  val transitionId: String,
  val phaseId: String,
  val attempt: Int,
  val kind: PlanBundleFeasibilityLaunchKind,
  val baselineIdentity: PlanBundleFeasibilityIdentity,
  val repairDirectory: String?,
  val intendedAfterIdentity: PlanBundleFeasibilityIdentity?,
  val outcome: PlanBundleFeasibilityLaunchOutcome,
  val value: String,
) {
  public fun matchesContext(launch: PlanBundleFeasibilityLaunch): Boolean =
    transitionId == launch.transitionId && phaseId == launch.phaseId && attempt == launch.attempt &&
      kind == launch.kind && baselineIdentity == launch.baselineIdentity &&
      repairDirectory == launch.repairDirectory && intendedAfterIdentity == launch.intendedAfterIdentity

  public fun matchesContext(other: PlanBundleFeasibilitySettledLaunch): Boolean =
    transitionId == other.transitionId && phaseId == other.phaseId && attempt == other.attempt &&
      kind == other.kind && baselineIdentity == other.baselineIdentity &&
      repairDirectory == other.repairDirectory && intendedAfterIdentity == other.intendedAfterIdentity
}

public data class PlanBundleFeasibilityApproval(
  val immutableFingerprint: String,
  val criterionLines: List<String>,
  val transitionId: String,
)

public sealed interface PlanBundleFeasibilityStateValidation {
  public object Valid : PlanBundleFeasibilityStateValidation

  public data class Invalid(val reason: String) : PlanBundleFeasibilityStateValidation
}

public data class FeatureTaskRuntimePlanBundleFeasibilityState(
  val revision: Int,
  val mode: PlanBundleFeasibilityMode,
  val identity: PlanBundleFeasibilityIdentity,
  val acceptedLines: List<PlanBundleFeasibilityAcceptedLine>,
  val conflicts: List<PlanBundleFeasibilityConflict>,
  val changedSubtaskIds: List<Int>,
  val requiredRefs: List<String>,
  val carriedRefs: List<String>,
  val reviewRoundsStarted: Int,
  val repairLaunchesStarted: Int,
  val activeLaunch: PlanBundleFeasibilityLaunch?,
  val settledLaunches: List<PlanBundleFeasibilitySettledLaunch>,
  val lastReviewValue: String?,
  val approval: PlanBundleFeasibilityApproval?,
  val legacyOrigin: Boolean,
) {
  public fun validation(): PlanBundleFeasibilityStateValidation =
    invalidReason()?.let(PlanBundleFeasibilityStateValidation::Invalid) ?: PlanBundleFeasibilityStateValidation.Valid

  public fun invalidReason(): String? = invalidFeasibilityStateReason(this)

  public fun initializationInvalidReason(): String? = invalidFeasibilityInitializationReason(this)

  public fun preservesLaunchHistoryFrom(
    previous: FeatureTaskRuntimePlanBundleFeasibilityState,
    transitionId: String,
  ): Boolean {
    if (settledLaunches.size < previous.settledLaunches.size ||
      settledLaunches.take(previous.settledLaunches.size) != previous.settledLaunches
    ) {
      return false
    }
    val additions = settledLaunches.drop(previous.settledLaunches.size)
    if (additions.isNotEmpty()) {
      val active = previous.activeLaunch ?: return false
      return additions.size == 1 && activeLaunch == null && active.transitionId == transitionId &&
        additions.single().matchesContext(active)
    }
    val previousActive = previous.activeLaunch
    return if (previousActive == null) {
      activeLaunch?.transitionId == transitionId
    } else {
      activeLaunch?.matchesContext(previousActive) == true
    }
  }

  public fun matchesRetainedContext(settlement: PlanBundleFeasibilitySettledLaunch): Boolean {
    val activeMatches = activeLaunch?.takeIf { it.transitionId == settlement.transitionId }
    val settledMatches = settledLaunches.filter { it.transitionId == settlement.transitionId }
    if ((activeMatches?.let { 1 } ?: 0) + settledMatches.size != 1) return false
    return activeMatches?.let(settlement::matchesContext) ?: settledMatches.single().let(settlement::matchesContext)
  }

  public fun matchesRetainedContext(launch: PlanBundleFeasibilityLaunch): Boolean {
    val activeMatches = activeLaunch?.takeIf { it.transitionId == launch.transitionId }
    val settledMatches = settledLaunches.filter { it.transitionId == launch.transitionId }
    if ((activeMatches?.let { 1 } ?: 0) + settledMatches.size != 1) return false
    return activeMatches?.let(launch::matchesContext) ?: settledMatches.single().matchesContext(launch)
  }
}
