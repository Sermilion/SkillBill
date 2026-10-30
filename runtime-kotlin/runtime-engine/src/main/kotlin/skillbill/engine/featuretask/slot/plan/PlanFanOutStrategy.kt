package skillbill.engine.featuretask.slot.plan

import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseAfterCompletionHooks
import skillbill.engine.featuretask.slot.PhaseBindingFamily
import skillbill.engine.featuretask.slot.PhaseResumeCapability
import skillbill.engine.featuretask.slot.PhaseStepDescriptor
import skillbill.engine.featuretask.slot.PhaseStepHookContextKind
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptTraversalHookContext
import skillbill.engine.featuretask.slot.attempt.PhasePlanningTraversalContext
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.state.PhaseFanOutUnits
import skillbill.engine.featuretask.slot.state.PhasePlanningAgentStepBinding
import skillbill.engine.featuretask.slot.state.PhasePlanningStepBinding
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.PhaseRunFanOut
import skillbill.error.featuretask.UnknownPhaseStepError
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class PlanFanOutStrategy private constructor(
  private val fanOutPort: BoundedWorkFanOutPort,
  private val planFanOutCap: Int,
  private val plan: AgentPlanStrategy,
) : PhaseStrategy(),
  PhaseResumeCapability by PlanFanOutResume(plan) {
  constructor(
    fanOutPort: BoundedWorkFanOutPort,
    planFanOutCap: Int,
  ) : this(fanOutPort, planFanOutCap, AgentPlanStrategy())

  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to
        plan.policyFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS to
        plan.policyFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN),
    )

  private val unitDescriptor =
    PhaseStepDescriptor(PhaseBindingFamily.PlanningUnit, PlanSubtasksStepHooks) { run, state ->
      runAgentStep(run, state)
    }

  override val slot: PhaseSlot = PhaseSlot.PLAN
  override val strategyId: String = ID
  override val semanticRevision: Int = SEMANTIC_REVISION
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun directiveFor(stepId: String): String =
    if (isSubtaskStep(stepId)) PLAN_SPEC_DIRECTIVE else plan.directiveFor(stepId)

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections =
    if (isSubtaskStep(stepId)) {
      PhaseStepPromptSections(
        taskDirective = PLAN_SPEC_DIRECTIVE,
        testValueDiscipline = true,
        stepContext = "${AgentPlanStrategy.PHASE_FEASIBILITY_CONSTRAINT}\n\n$planningFeasibilityDirective",
      )
    } else {
      plan.promptSections(stepId, inputs)
    }

  override fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> =
    plan.briefingInvariantFields(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN)

  override fun stepDescriptor(stepId: String): PhaseStepDescriptor<*> =
    if (isSubtaskStep(stepId)) {
      PhaseStepDescriptor(PhaseBindingFamily.PlanningFanOut, PlanSubtasksStepHooks) { run, planning ->
        runSubtasks(run, planning)
      }
    } else {
      PhaseStepDescriptor(PhaseBindingFamily.PlanningFanOut, AgentPlanStrategy.PlanStepHooks) { run, planning ->
        planning.settleExistingBundle(run, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS)
          ?: plan.runPlan(run, planning)
      }
    }

  private fun isSubtaskStep(stepId: String): Boolean = isPlanSubtasksStep(stepId, steps)

  private fun runSubtasks(
    run: PhaseRun,
    planning: PhasePlanningStepBinding,
  ): PhaseOutcome {
    val fanOut = planning.fanOut(run.phaseId)
    planning.authorizeFanOutWave(run)
    val outcome =
      try {
        runFanOutStep(run, fanOut)
      } finally {
        planning.releaseFanOutWave(run)
      }
    if (outcome is PhaseOutcome.Completed) {
      val settled =
        checkNotNull(outcome.output.normalizedOutput) {
          "Plan fan-out completion for '${run.phaseId}' must carry a normalized output."
        }
      planning.settleRuntimeAuthoredCompletion(run.phaseId, settled)
    }
    return outcome
  }

  private fun runFanOutStep(
    run: PhaseRun,
    fanOut: PhaseRunFanOut,
  ): PhaseOutcome {
    val pending =
      when (val units = fanOut.pendingUnits()) {
        is PhaseFanOutUnits.Stopped -> return units.outcome
        is PhaseFanOutUnits.Pending -> units.unitIds
      }
    val waves = pending.chunked(planFanOutCap)
    for ((index, wave) in waves.withIndex()) {
      val nextWaveFirstId = waves.getOrNull(index + 1)?.first()
      val stopped =
        fanOut.pauseBefore(wave.first())
          ?: runPlanFanOutWave(fanOutPort, planFanOutCap, fanOut, wave) { runUnit(run, fanOut, it) }
          ?: nextWaveFirstId?.let(fanOut::pauseBefore)
      if (stopped != null) return stopped
    }
    return fanOut.completed()
  }

  private fun runUnit(
    run: PhaseRun,
    fanOut: PhaseRunFanOut,
    unitId: Int,
  ): PhaseOutcome {
    val sink = UnitAttributedOutputSink(fanOutPort, fanOut.outputSink, unitId)
    val state = fanOut.unitState(unitId, sink, unitDescriptor)
    return try {
      unitDescriptor.execute(run, state)
    } finally {
      state.finishStepExecution()
      sink.flushTrailingLines()
    }
  }

  private object PlanSubtasksStepHooks :
    PhaseStepHooks<PhasePlanningAgentStepBinding>,
    PhaseAfterCompletionHooks {
    override val contextKind = PhaseStepHookContextKind.PLANNING

    override fun afterCompletion(
      context: PhaseAttemptTraversalHookContext,
      output: FeatureTaskRuntimePhaseOutput,
    ): String? =
      (
        context as? PhasePlanningTraversalContext
          ?: error("Plan subtasks completion requires the accepted planning traversal context.")
      ).settlePlanningStop(output)
  }

  private class UnitAttributedOutputSink(
    private val fanOutPort: BoundedWorkFanOutPort,
    private val delegate: AgentRunOutputSink,
    unitId: Int,
  ) : AgentRunOutputSink {
    private val attribution = "[subtask $unitId] "
    private val pending = mutableMapOf<AgentRunOutputStream, StringBuilder>()

    override fun write(
      stream: AgentRunOutputStream,
      text: String,
    ) = fanOutPort.runExclusively {
      val buffer = pending.getOrPut(stream) { StringBuilder() }.append(text)
      var newline = buffer.indexOf("\n")
      while (newline >= 0) {
        val line = buffer.substring(0, newline + 1)
        buffer.delete(0, newline + 1)
        delegate.write(stream, attribution + line)
        newline = buffer.indexOf("\n")
      }
    }

    fun flushTrailingLines() =
      fanOutPort.runExclusively {
        pending.forEach { (stream, buffer) ->
          if (buffer.isNotEmpty()) {
            delegate.write(stream, attribution + buffer + "\n")
            buffer.setLength(0)
          }
        }
      }
  }

  companion object {
    const val ID = "goal-plan-fan-out"
    const val SEMANTIC_REVISION = 2

    private const val PLAN_SPEC_DIRECTIVE: String =
      "Plan the current governed sub-spec and write the plan into that same file. Edit only the assigned " +
        "sub-spec named in the planning session context: keep its title, scope, acceptance criteria, " +
        "dependencies, validation strategy, and next path unchanged, and add a non-blank " +
        "\"## Implementation Details\" section holding the ordered tasks, the acceptance criteria each one " +
        "serves, the paths or symbols it touches, the tests to add or run, and constraints, using the " +
        "upstream preplan digest and any targeted feasibility evidence. Never modify the parent spec, a sibling " +
        "sub-spec, or any other " +
        "repository file; this directive supersedes any earlier instruction not to modify files. " +
        "Finish with a short prose summary of the plan. " + PREPLAN_DIGEST_AUTHORITY +
        " " + PLANNING_INSTALL_PROHIBITION + " Read the assigned sub-spec and only the targeted evidence " +
        "permitted above. Other bundle content is supplied by the runtime."
  }
}

private class PlanFanOutResume(
  private val plan: AgentPlanStrategy,
) : PhaseResumeCapability {
  override fun resumeRules(stepId: String): PhaseResumeRules =
    if (isPlanSubtasksStep(stepId, PLAN_FAN_OUT_STEPS)) {
      AgentPlanStrategy.PlanSubtasksResumeRules
    } else {
      plan.resumeRules(stepId)
    }
}

private val PLAN_FAN_OUT_STEPS =
  listOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
  )

private fun isPlanSubtasksStep(
  stepId: String,
  steps: List<String>,
): Boolean {
  if (stepId !in steps) throw UnknownPhaseStepError(stepId)
  return stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS
}
