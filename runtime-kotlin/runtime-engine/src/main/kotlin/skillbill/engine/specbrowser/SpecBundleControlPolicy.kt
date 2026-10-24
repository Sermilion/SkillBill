package skillbill.engine.specbrowser

import skillbill.engine.specbrowser.model.SpecBrowserStatus
import skillbill.engine.specbrowser.model.SpecBundleControl
import skillbill.engine.specbrowser.model.SpecBundleRunState
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.install.model.SupportedAgent

private const val START_LABEL = "Start"

private val RUNNING_STATES = setOf(SpecBundleRunState.RUNNING, SpecBundleRunState.PAUSE_REQUESTED)

internal data class SpecBundleControlFacts(
  val issueKey: String?,
  val unavailableReason: String?,
  val everStarted: Boolean,
  val treeStatus: SpecBrowserStatus,
  val live: ControlLive?,
)

internal fun specBundleControl(facts: SpecBundleControlFacts): SpecBundleControl {
  if (facts.unavailableReason != null) return unavailableControl(facts.issueKey, facts.unavailableReason)
  val live = facts.live
  val state = runState(live, facts.treeStatus)
  val options = live?.options
  val base =
    SpecBundleControl(
      issueKey = facts.issueKey,
      runState = state,
      everStarted = facts.everStarted,
      workflowId = live?.workflowId,
      repositoryIdentity = live?.repositoryIdentity,
      canStart = false,
      startLabel = options?.launchLabel ?: START_LABEL,
      canPause = false,
      pauseRequested = false,
      canCancel = false,
      purgeVisible = facts.everStarted,
      canPurge = facts.everStarted && state !in RUNNING_STATES,
      agentId = options?.agentId,
      agentChoices = options?.agentChoices.orEmpty(),
      unavailableReason = options?.unavailableReason,
    )
  return when (state) {
    SpecBundleRunState.NOT_STARTED -> base.copy(canStart = true, agentChoices = SupportedAgent.supportedIds)
    SpecBundleRunState.RUNNING ->
      base.copy(canPause = live?.pauseEligible == true, canCancel = options?.canCancel == true)
    SpecBundleRunState.PAUSE_REQUESTED -> base.copy(pauseRequested = true, canCancel = options?.canCancel == true)
    SpecBundleRunState.PAUSED, SpecBundleRunState.BLOCKED ->
      base.copy(canStart = options?.canLaunch == true, canCancel = options?.canCancel == true)
    SpecBundleRunState.CANCELED -> base.copy(canStart = options?.canLaunch == true)
    SpecBundleRunState.COMPLETE, SpecBundleRunState.UNAVAILABLE -> base
  }
}

private fun runState(
  live: ControlLive?,
  treeStatus: SpecBrowserStatus,
): SpecBundleRunState =
  when {
    live != null -> lifecycleRunState(live.lifecycle, live.pauseRequested)
    treeStatus == SpecBrowserStatus.COMPLETE -> SpecBundleRunState.COMPLETE
    else -> SpecBundleRunState.NOT_STARTED
  }

private fun lifecycleRunState(
  lifecycle: IdeStatusLifecycleState,
  pauseRequested: Boolean,
): SpecBundleRunState =
  when (lifecycle) {
    IdeStatusLifecycleState.ACTIVE ->
      if (pauseRequested) SpecBundleRunState.PAUSE_REQUESTED else SpecBundleRunState.RUNNING
    IdeStatusLifecycleState.PAUSED, IdeStatusLifecycleState.IDLE -> SpecBundleRunState.PAUSED
    IdeStatusLifecycleState.BLOCKED, IdeStatusLifecycleState.FAILED -> SpecBundleRunState.BLOCKED
    IdeStatusLifecycleState.CANCELED -> SpecBundleRunState.CANCELED
    IdeStatusLifecycleState.TERMINAL -> SpecBundleRunState.COMPLETE
  }

private fun unavailableControl(
  issueKey: String?,
  reason: String,
): SpecBundleControl =
  SpecBundleControl(
    issueKey = issueKey,
    runState = SpecBundleRunState.UNAVAILABLE,
    everStarted = false,
    workflowId = null,
    repositoryIdentity = null,
    canStart = false,
    startLabel = START_LABEL,
    canPause = false,
    pauseRequested = false,
    canCancel = false,
    purgeVisible = false,
    canPurge = false,
    agentId = null,
    agentChoices = emptyList(),
    unavailableReason = reason,
  )
