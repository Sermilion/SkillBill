package skillbill.engine.goalrunner.planning.attempt

import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposer
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.runner.phaseDeclaration
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.goalrunner.planning.context.GoalPlanningContextPromptFormatter
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningProduceAttemptArgs
import skillbill.engine.goalrunner.planning.outcome.planningProgressMessage
import skillbill.engine.goalrunner.planning.sweep.DefaultGoalPlanningSweep
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.workflow.taskruntime.handoff.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries
import skillbill.workflow.taskruntime.phase.task.SkeletonDefinition

private val GOAL_PLANNING_SELECTION_FACTS = PhaseStrategySelectionFacts(SkeletonDefinition.GOAL_CHILD, emptySet())

internal fun DefaultGoalPlanningSweep.launchPlanningAttempt(
  phase: GoalPlanningPhaseContext,
  prompt: String,
): AgentRunLaunchOutcome {
  val shared = phase.shared
  val request = phase.request
  val sink = phase.outputSink
  sink.write(AgentRunOutputStream.STDERR, planningProgressMessage(phase.phaseId, phase.subtask))
  return subtaskLauncher.launch(
    GoalRunnerSubtaskLaunchRequest(
      invokedAgentId = shared.invokedAgentId,
      configuredAgentOverrideId = shared.configuredAgentOverrideId,
      skillRunRequest =
        SkillRunRequest(
          issueKey = request.issueKey,
          repoRoot = shared.repoRoot,
          subtaskId = phase.subtask?.id,
          timeout = request.planningBudget,
          progressIdleTimeout = request.progressIdleTimeout,
          outputSink = sink,
          promptOverride = prompt,
          streamOutputForLiveness = true,
          spawnAuthorization = manifestStore.authorizePlanningLaunch(shared.parentWorkflowId),
        ),
    ),
  )
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
  val strategy = phaseStrategies.strategyFor(phase.phaseId, GOAL_PLANNING_SELECTION_FACTS)
  val briefing =
    FeatureTaskRuntimePhaseBriefingAssembler.assemble(
      handoff,
      planningProjectionValidator = planningProjectionValidator,
      agentAddonSelection = phase.request.agentAddonSelection,
      invariantFields = strategy.briefingInvariantFields(phase.phaseId),
    )
  val basePrompt =
    FeatureTaskRuntimePhasePromptComposer.compose(
      FeatureTaskRuntimePhasePromptComposeInputs(
        issueKey = phase.request.issueKey,
        briefing = briefing,
        suppressDecomposition = true,
        priorSchemaFailure = args.priorSchemaFailure,
      ),
      PhaseStepPromptSource { inputs -> strategy.promptSections(phase.phaseId, inputs) },
    )
  return GoalPlanningContextPromptFormatter.append(
    basePrompt,
    phase.shared.planningPacket,
    phase.subtask,
    phase.phaseId,
    args.resolvedBodies,
  )
}
