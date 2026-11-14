package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopLaunch
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.install.model.SupportedAgent

internal fun PhaseRun.stepFacts(
  issueKey: String,
  attempt: Int?,
): PhaseStepFacts {
  val launched = FeatureTaskRuntimeRunLoopLaunch.launchedModelDirective(this)
  return PhaseStepFacts(
    issueKey = issueKey,
    repoRoot = request.repoRoot,
    agentWorkingDirectory = (qaExecution?.target as? QaExecutionTarget.Supported)?.checkout,
    additionalWritableDirectories = listOfNotNull(projectStoreRoot, qaExecution?.workspace?.directory).distinct(),
    timeout = request.timeout,
    invokedAgentId = resolvedAgent.invokedAgentId,
    configuredAgentOverrideId = resolvedAgent.configuredAgentOverrideId,
    modelOverride = launched.modelOverride,
    effortOverride = launched.effortOverride,
    openCodeIdentity = request.openCodeIdentity,
    compaction = compaction,
    attempt = attempt,
    observeLaunch = true,
    briefingText = "",
    liveChecks =
      request.validationCheckInvocationId?.let { invocationId ->
        PhaseLiveChecksLaunch(
          invocationId,
          SupportedAgent.fromWireOrNull(resolvedAgent.resolvedAgentId)?.loadsRuntimeMcpHeadless == true,
        )
      },
  )
}
