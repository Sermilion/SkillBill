package skillbill.engine.work.control

import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.idestatus.model.IdeStatusDisplayGroup
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.idestatus.model.IdeStatusPolicy
import skillbill.idestatus.model.IdeStatusWorkPolicy
import skillbill.idestatus.model.IdeStatusWorkPolicyFacts
import skillbill.ports.idestatus.model.IdeStatusPauseReasonCode
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.WorkLiveSection
import java.time.Instant

internal fun workControlEligible(snapshot: IdeStatusSnapshot): Boolean = policy(snapshot, null).controlEligible

internal fun workPauseEligible(snapshot: IdeStatusSnapshot): Boolean = policy(snapshot, null).pauseEligible

internal fun workLiveSection(
  snapshot: IdeStatusSnapshot,
  finishedAt: Instant?,
): WorkLiveSection? = policy(snapshot, finishedAt).liveSection?.let { WorkLiveSection.valueOf(it.name) }

internal fun workRequiresAttention(snapshot: IdeStatusSnapshot): Boolean = policy(snapshot, null).requiresAttention

internal fun workDisplayPriority(
  snapshot: IdeStatusSnapshot,
  finishedAt: Instant?,
): Int = policy(snapshot, finishedAt).displayPriority

internal fun workDisplayGroup(
  snapshot: IdeStatusSnapshot,
  finishedAt: Instant?,
): IdeStatusDisplayGroup? = policy(snapshot, finishedAt).displayGroup

internal fun workPolicy(
  snapshot: IdeStatusSnapshot,
  finishedAt: Instant?,
  observedAt: Instant,
): IdeStatusWorkPolicy = policy(snapshot, finishedAt, observedAt)

private fun policy(
  snapshot: IdeStatusSnapshot,
  finishedAt: Instant?,
  observedAt: Instant? = null,
) = IdeStatusPolicy.work(
  IdeStatusWorkPolicyFacts(
    lifecycle = snapshot.lifecycleState,
    family = snapshot.workflowFamily,
    scope = snapshot.execution?.scope,
    freshness = snapshot.freshness,
    finishedAt = finishedAt,
    awaitingOperatorDecision = snapshot.pauseReason?.code == IdeStatusPauseReasonCode.AWAITING_OPERATOR_DECISION,
    planningPrepared = snapshot.planning?.state == GoalPlanningStatusState.PREPARED,
    controlTargetPresent = !snapshot.issueKey.isNullOrBlank(),
    pauseRequested = snapshot.pauseRequested == true,
    observedAt = observedAt,
    launchScope = snapshot.execution?.launchScope ?: IdeStatusLaunchScope.STANDALONE,
  ),
)
