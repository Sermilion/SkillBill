package skillbill.engine.goalrunner.planning.attempt

import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposer
import skillbill.engine.featuretask.runner.phaseDeclaration
import skillbill.engine.featuretask.slot.PhaseStepFacts
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.goalrunner.planning.context.GoalPlanningContextPromptFormatter
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningProduceAttemptArgs
import skillbill.engine.goalrunner.planning.outcome.planningProgressMessage
import skillbill.engine.goalrunner.planning.sweep.DefaultGoalPlanningSweep
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.workflow.taskruntime.handoff.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries

internal fun DefaultGoalPlanningSweep.launchPlanningAttempt(
  phase: GoalPlanningPhaseContext,
  prompt: String,
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
      launch.state,
    )
  return requireNotNull(output.launchOutcome) { output.launchFailure?.reason.orEmpty() }
}

internal fun DefaultGoalPlanningSweep.composePlanningPrompt(args: GoalPlanningProduceAttemptArgs): String {
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
      planningProjectionValidator = planningProjectionValidator,
      agentAddonSelection = phase.request.agentAddonSelection,
      invariantFields = phase.launch.invariantFields,
    )
  val basePrompt =
    FeatureTaskRuntimePhasePromptComposer.compose(
      FeatureTaskRuntimePhasePromptComposeInputs(
        issueKey = phase.request.issueKey,
        briefing = briefing,
        suppressDecomposition = true,
        priorSchemaFailure = args.priorSchemaFailure,
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
