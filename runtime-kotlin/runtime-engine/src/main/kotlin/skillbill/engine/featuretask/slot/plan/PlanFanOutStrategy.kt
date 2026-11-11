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
import skillbill.ports.agentrun.activity.model.AgentRunActivity
import skillbill.ports.agentrun.model.AgentRunActivityOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

public class PlanFanOutStrategy private constructor(
  private val fanOutPort: BoundedWorkFanOutPort,
  private val planFanOutCap: Int,
  private val plan: AgentPlanStrategy,
) : PhaseStrategy(),
  PhaseResumeCapability by PlanFanOutResume(plan) {
  public constructor(
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
    PhaseStepDescriptor(
      PhaseBindingFamily.PlanningUnit,
      PlanSubtasksStepHooks,
    ) { run, state, outputSink, captureStreamOutput ->
      runAgentStep(run, state, outputSink, captureStreamOutput)
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
      val feasibility = BundleFeasibilityStep()
      PhaseStepDescriptor(
        PhaseBindingFamily.PlanningFanOut,
        feasibility,
      ) { run, planning, outputSink, captureStreamOutput ->
        val planned = runSubtasks(run, planning, outputSink, captureStreamOutput)
        if (planned is PhaseOutcome.Completed) {
          feasibility.run(run, planning, outputSink, captureStreamOutput)
        } else {
          planned
        }
      }
    } else {
      PhaseStepDescriptor(
        PhaseBindingFamily.PlanningFanOut,
        AgentPlanStrategy.PlanStepHooks,
      ) { run, planning, outputSink, captureStreamOutput ->
        planning.settleExistingBundle(run, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS)
          ?: runAgentStep(run, planning, outputSink, captureStreamOutput)
      }
    }

  private fun isSubtaskStep(stepId: String): Boolean = isPlanSubtasksStep(stepId, steps)

  private fun runSubtasks(
    run: PhaseRun,
    planning: PhasePlanningStepBinding,
    outputSink: AgentRunOutputSink,
    captureStreamOutput: Boolean,
  ): PhaseOutcome {
    val fanOut = planning.fanOut(run.phaseId)
    planning.authorizeFanOutWave(run)
    val outcome =
      try {
        runFanOutStep(run, fanOut, outputSink, captureStreamOutput)
      } finally {
        planning.releaseFanOutWave(run)
      }
    return outcome
  }

  private fun runFanOutStep(
    run: PhaseRun,
    fanOut: PhaseRunFanOut,
    outputSink: AgentRunOutputSink,
    captureStreamOutput: Boolean,
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
          ?: if (!fanOut.recordWave(planFanOutCap, wave)) {
            PhaseOutcome.blocked("Planning subtask wave ${index + 1} could not be recorded before dispatch.")
          } else {
            runPlanFanOutWave(fanOutPort, planFanOutCap, fanOut, wave) {
              runUnit(run, fanOut, it, outputSink, captureStreamOutput)
            }
          }
          ?: nextWaveFirstId?.let(fanOut::pauseBefore)
      if (stopped != null) return stopped
    }
    return fanOut.completed()
  }

  private fun runUnit(
    run: PhaseRun,
    fanOut: PhaseRunFanOut,
    unitId: Int,
    outputSink: AgentRunOutputSink,
    captureStreamOutput: Boolean,
  ): PhaseOutcome {
    val sink = UnitAttributedOutputSink(fanOutPort, outputSink, unitId, captureStreamOutput)
    val state = fanOut.unitState(unitId, unitDescriptor)
    return try {
      unitDescriptor.execute(run, state, sink, captureStreamOutput)
    } finally {
      try {
        state.finishStepExecution()
      } finally {
        sink.flushTrailingLines()
      }
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
    private val unitId: Int,
    private val boundPendingLines: Boolean,
  ) : AgentRunActivityOutputSink {
    override val capturesActivity: Boolean = (delegate as? AgentRunActivityOutputSink)?.capturesActivity == true

    override fun activity(event: AgentRunActivity) =
      fanOutPort.runExclusively {
        checkNotNull(delegate as? AgentRunActivityOutputSink).activity(event.copy(subtaskId = unitId))
      }

    override fun raw(
      stream: AgentRunOutputStream,
      text: String,
      subtaskId: Int?,
    ) = fanOutPort.runExclusively {
      checkNotNull(delegate as? AgentRunActivityOutputSink).raw(stream, text, unitId)
    }

    private val attribution = "[subtask $unitId] "
    private val pending = mutableMapOf<AgentRunOutputStream, StringBuilder>()

    override fun write(
      stream: AgentRunOutputStream,
      text: String,
    ) = fanOutPort.runExclusively {
      val buffer = pending.getOrPut(stream) { StringBuilder() }
      text.forEach { character ->
        if (character == '\n') {
          delegate.write(stream, attribution + buffer + "\n")
          buffer.setLength(0)
        } else if (!boundPendingLines || buffer.length < MAX_PENDING_LINE_CHARS) {
          buffer.append(character)
        }
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

  public companion object {
    public const val ID: String = "goal-plan-fan-out"
    private const val SEMANTIC_REVISION: Int = 2
    private const val MAX_PENDING_LINE_CHARS: Int = 160

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
