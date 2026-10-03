package skillbill.engine.goalrunner.planning.attempt

import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeBriefingScope
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposer
import skillbill.engine.featuretask.runner.phaseDeclaration
import skillbill.engine.featuretask.slot.PhaseStepFacts
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.state.PhasePlanningBriefingBinding
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.planning.context.GoalPlanningContextPromptFormatter
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningProduceAttemptArgs
import skillbill.engine.goalrunner.planning.outcome.planningProgressMessage
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.workflow.taskruntime.handoff.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries

internal fun launchPlanningAttempt(
  phase: GoalPlanningPhaseContext,
  prompt: String,
  manifestStore: GoalRunnerManifestStore,
): AgentRunLaunchOutcome {
  val shared = phase.shared
  val request = phase.request
  val sink = phase.outputSink
  val launch = phase.launch
  sink.write(AgentRunOutputStream.STDERR, planningProgressMessage(phase.phaseId, phase.subtask))
  val facts =
    PhaseStepFacts(
      issueKey = request.issueKey,
      repoRoot = shared.repoRoot,
      timeout = request.planningBudget,
      invokedAgentId = shared.invokedAgentId,
      configuredAgentOverrideId = shared.configuredAgentOverrideId,
      modelOverride = null,
      effortOverride = null,
      compaction = null,
      attempt = null,
      observeLaunch = false,
      briefingText = prompt,
      subtaskId = phase.subtask?.id,
      progressIdleTimeout = request.progressIdleTimeout,
      outputSink = sink,
      streamOutputForLiveness = true,
      spawnAuthorization = manifestStore.authorizePlanningLaunch(shared.parentWorkflowId),
    )
  val output =
    launch.runner.run(
      PhaseStepInput(phase.phaseId, prompt, emptyMap(), null, facts, launch.policy),
      launch.state.launchState,
    )
  return requireNotNull(output.launchOutcome) { output.launchFailure?.reason.orEmpty() }
}

internal inline fun composePlanningPrompt(
  args: GoalPlanningProduceAttemptArgs,
  onRejected: (RequiredPhaseWrite.Rejected) -> Nothing,
): String {
  val phase = args.phase
  val handoff =
    FeatureTaskRuntimeHandoffContract.assembleHandoff(
      FeatureTaskRuntimeHandoffAssemblyRequest(
        declaration =
          FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclaration(
            phase.phaseId,
            phase.runInvariants.featureSize,
          ),
        runInvariants = phase.runInvariants,
        recordedOutputs = args.recordedOutputs,
      ),
    )
  val briefing =
    FeatureTaskRuntimePhaseBriefingAssembler.assemble(
      handoff,
      agentAddonSelection = phase.request.agentAddonSelection,
      scope = FeatureTaskRuntimeBriefingScope(invariantFields = phase.launch.invariantFields),
    )
  val write = (phase.launch.state as PhasePlanningBriefingBinding).recordPlanningBriefing(briefing, args.attempt)
  if (write is RequiredPhaseWrite.Rejected) onRejected(write)
  val basePrompt =
    FeatureTaskRuntimePhasePromptComposer.compose(
      FeatureTaskRuntimePhasePromptComposeInputs(
        issueKey = phase.request.issueKey,
        briefing = briefing,
        suppressDecomposition = true,
      ),
      phase.launch.prompt,
    )
  return GoalPlanningContextPromptFormatter.append(
    basePrompt,
    phase.shared.planningPacket,
    phase.subtask,
    phase.phaseId,
    args.resolvedBodies,
  )
}
