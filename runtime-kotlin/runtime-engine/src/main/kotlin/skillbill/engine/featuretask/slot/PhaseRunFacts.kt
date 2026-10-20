package skillbill.engine.featuretask.slot

import skillbill.engine.directive.directiveResource
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopLaunch
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfile

internal fun PhaseRun.stepFacts(
  issueKey: String,
  attempt: Int?,
): PhaseStepFacts {
  val launched = FeatureTaskRuntimeRunLoopLaunch.launchedModelDirective(this)
  return PhaseStepFacts(
    issueKey = issueKey,
    repoRoot = request.repoRoot,
    additionalWritableDirectories = listOfNotNull(qaExecution?.workspace?.directory),
    timeout = request.timeout,
    invokedAgentId = resolvedAgent.invokedAgentId,
    configuredAgentOverrideId = resolvedAgent.configuredAgentOverrideId,
    modelOverride = launched.modelOverride,
    effortOverride = launched.effortOverride,
    compaction = compaction,
    attempt = attempt,
    observeLaunch = true,
    briefingText = "",
  )
}

internal fun PhaseStepFacts.withLaunchSink(state: PhaseLaunchState): PhaseStepFacts =
  if (state.launchOutputSink === AgentRunOutputSink.NONE) {
    this
  } else {
    copy(outputSink = state.launchOutputSink, streamOutputForLiveness = true)
  }

internal fun PhaseStepPromptSections.appendWhenOpus(
  profile: PhaseModelProfile,
  extra: String,
): PhaseStepPromptSections {
  if (profile != PhaseModelProfile.OPUS_5_5 || extra.isBlank()) return this
  val block = extra.trimEnd()
  return copy(
    taskDirective = "$taskDirective\n\n$block",
    retryFocus = if (retryFocus.isBlank()) retryFocus else "$retryFocus\n\n$block",
    outputContract = outputContract?.let { "$it\n\n$block" },
  )
}

internal fun opus55Directive(resourcePath: String): String = directiveResource(resourcePath).trimEnd()
