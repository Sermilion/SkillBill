package skillbill.workflow.taskruntime.model.planning

import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

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
  val rawSpecText: String? = null,
)

public data class PlanBundleFeasibilityIdentity(
  val parentSha256: String,
  val manifestSha256: String,
  val immutableManifestSha256: String,
  val subtasks: List<PlanBundleFeasibilitySubtaskIdentity>,
  val rawParentText: String? = null,
  val rawManifestText: String? = null,
)

public data class PlanBundleFeasibilityDependencyHash(val subtaskId: Int, val specSha256: String)

public data class PlanBundleFeasibilityRepositoryBaseline(
  val headCommit: String,
  val dirtyPaths: List<String>,
  val contentIdentities: Map<String, String>,
)

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
  val cycleId: String? = null,
  val sequence: Int = 0,
  val intendedRepairValue: String? = null,
  val repositoryBaseline: PlanBundleFeasibilityRepositoryBaseline? = null,
  val repairBeforeIdentity: PlanBundleFeasibilityIdentity? = null,
) {
  public fun matchesContext(other: PlanBundleFeasibilityLaunch): Boolean =
    transitionId == other.transitionId && phaseId == other.phaseId && attempt == other.attempt &&
      kind == other.kind && baselineIdentity == other.baselineIdentity && repairDirectory == other.repairDirectory &&
      cycleId == other.cycleId && sequence == other.sequence && repositoryBaseline == other.repositoryBaseline
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
  val cycleId: String? = null,
  val sequence: Int = 0,
  val repositoryBaseline: PlanBundleFeasibilityRepositoryBaseline? = null,
  val repairBeforeIdentity: PlanBundleFeasibilityIdentity? = null,
) {
  public fun matchesContext(launch: PlanBundleFeasibilityLaunch): Boolean =
    transitionId == launch.transitionId && phaseId == launch.phaseId && attempt == launch.attempt &&
      kind == launch.kind && baselineIdentity == launch.baselineIdentity &&
      repairDirectory == launch.repairDirectory && cycleId == launch.cycleId && sequence == launch.sequence &&
      repositoryBaseline == launch.repositoryBaseline && repairBeforeIdentity == launch.repairBeforeIdentity

  public fun matchesContext(other: PlanBundleFeasibilitySettledLaunch): Boolean =
    transitionId == other.transitionId && phaseId == other.phaseId && attempt == other.attempt &&
      kind == other.kind && baselineIdentity == other.baselineIdentity &&
      repairDirectory == other.repairDirectory && cycleId == other.cycleId && sequence == other.sequence &&
      repositoryBaseline == other.repositoryBaseline && repairBeforeIdentity == other.repairBeforeIdentity
}

public data class PlanBundleFeasibilityApproval(
  val immutableFingerprint: String,
  val criterionLines: List<String>,
  val transitionId: String,
)

public data class PlanBundleFeasibilityApprovalCandidate(
  val sourceRevision: Int,
  val immutableFingerprint: String,
  val transitionId: String,
  val phaseId: String,
  val attempt: Int,
  val reviewerAgentId: String,
  val normalizedEnvelope: String,
  val fileManifestBefore: List<String>,
  val fileManifestAfter: List<String>,
  val fileManifestIntroduced: List<String>,
) {
  public fun matchesRecord(record: FeatureTaskRuntimePhaseRecord): Boolean {
    val matchesAttempt =
      phaseId == record.phaseId && attempt == record.attemptCount &&
        reviewerAgentId == record.resolvedAgentId
    val matchesEvidence =
      normalizedEnvelope == record.outputArtifact &&
        fileManifestBefore == record.fileManifestBefore && fileManifestAfter == record.fileManifestAfter &&
        fileManifestIntroduced == record.fileManifestIntroduced
    return matchesAttempt && matchesEvidence
  }
}

public data class PlanBundleFeasibilityCycleHistory(
  val cycleId: String,
  val identity: PlanBundleFeasibilityIdentity,
  val acceptedLines: List<PlanBundleFeasibilityAcceptedLine>,
  val conflicts: List<PlanBundleFeasibilityConflict>,
  val reviewRoundsStarted: Int,
  val repairLaunchesStarted: Int,
  val settledLaunches: List<PlanBundleFeasibilitySettledLaunch>,
  val lastReviewValue: String?,
  val approval: PlanBundleFeasibilityApproval?,
)

public data class PlanBundleFeasibilityReopening(
  val sourceAttempt: Int,
  val sourceRevision: Int?,
  val retryLedgerSequence: Int,
  val reason: String,
  val originalPhaseRecord: FeatureTaskRuntimePhaseRecord,
)

public const val PLAN_BUNDLE_FEASIBILITY_FULL_V2_REVIEW_ADOPTION_REASON: String = "full-v2-review-adoption"
public const val PLAN_BUNDLE_FEASIBILITY_RUNTIME_COMPLETION_VALUE: String = "bundle-feasibility-runtime-finalized"

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
  val approvalCandidate: PlanBundleFeasibilityApprovalCandidate? = null,
  val currentCycleId: String? = null,
  val cycleHistory: List<PlanBundleFeasibilityCycleHistory> = emptyList(),
  val reopenings: List<PlanBundleFeasibilityReopening> = emptyList(),
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
    if (additions.isNotEmpty()) return preservesSettledAddition(previous, transitionId, additions)

    val previousActive = previous.activeLaunch
    return if (previousActive == null) {
      activeLaunch?.transitionId == transitionId
    } else {
      activeLaunch?.let { preservesActiveIntent(it, previousActive) } == true
    }
  }

  private fun preservesSettledAddition(
    previous: FeatureTaskRuntimePlanBundleFeasibilityState,
    transitionId: String,
    additions: List<PlanBundleFeasibilitySettledLaunch>,
  ): Boolean {
    val active = previous.activeLaunch ?: return false
    if (additions.size != 1 || activeLaunch != null || active.transitionId != transitionId) return false
    val settled = additions.single()
    val intendedAfterValid =
      if (settled.kind == PlanBundleFeasibilityLaunchKind.REPAIR &&
        settled.outcome == PlanBundleFeasibilityLaunchOutcome.COMPLETED && active.intendedAfterIdentity == null
      ) {
        settled.intendedAfterIdentity != null
      } else {
        settled.intendedAfterIdentity == active.intendedAfterIdentity
      }
    val repairValueValid =
      settled.outcome != PlanBundleFeasibilityLaunchOutcome.COMPLETED ||
        settled.kind != PlanBundleFeasibilityLaunchKind.REPAIR ||
        active.intendedRepairValue == null || settled.value == active.intendedRepairValue
    return settled.matchesContext(active) && intendedAfterValid && repairValueValid
  }

  private fun preservesActiveIntent(
    active: PlanBundleFeasibilityLaunch,
    previous: PlanBundleFeasibilityLaunch,
  ): Boolean =
    active.matchesContext(previous) &&
      when {
        active.intendedAfterIdentity == previous.intendedAfterIdentity ->
          active.intendedRepairValue == previous.intendedRepairValue &&
            active.repairBeforeIdentity == previous.repairBeforeIdentity
        previous.kind == PlanBundleFeasibilityLaunchKind.REPAIR &&
          previous.intendedAfterIdentity == null && active.intendedAfterIdentity != null ->
          active.intendedRepairValue != null && active.repairBeforeIdentity != null
        else -> false
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
