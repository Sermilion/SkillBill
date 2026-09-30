package skillbill.engine.featuretask.slot.plan

import skillbill.engine.directive.directiveResource
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.runloop.planning.PlanDecompositionStop
import skillbill.engine.featuretask.slot.PhaseStepHookContextKind
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptTraversalHookContext
import skillbill.engine.featuretask.slot.attempt.PhasePlanningLaunchContext
import skillbill.engine.featuretask.slot.attempt.PhasePlanningOutputContext
import skillbill.engine.featuretask.slot.attempt.PhasePlanningTraversalContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.PhaseStepBinding
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class AgentPlanStrategy : PhaseStrategy() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to
        PhaseStepPolicy(
          mutating = false,
          singleAgentSession = false,
          readOnlyIdle = false,
          fileMutating = false,
          generationScoped = false,
        ),
    )

  override val slot: PhaseSlot = PhaseSlot.PLAN
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun directiveFor(stepId: String): String {
    policies.policyOf(stepId)
    return DIRECTIVE
  }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections {
    policies.policyOf(stepId)
    val bundleRequired = inputs.specBundleRequired && !inputs.suppressDecomposition
    return PhaseStepPromptSections(
      taskDirective = if (bundleRequired) BUNDLE_DIRECTIVE else directiveFor(stepId),
      testValueDiscipline = true,
      stepContext =
        when {
          inputs.suppressDecomposition -> GOAL_CONTINUATION_CONSTRAINT
          inputs.specBundleRequired -> "$featureSpecDirective\n\n$SPEC_BUNDLE_REQUIREMENT"
          else -> featureSpecDirective
        },
    )
  }

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome = runAgentStep(run, state)

  override fun stepHooks(stepId: String): PhaseStepHooks {
    policies.policyOf(stepId)
    return PlanStepHooks
  }

  override fun resumeRules(stepId: String): PhaseResumeRules {
    policies.policyOf(stepId)
    return PlanResumeRules
  }

  private object PlanStepHooks : PhaseStepHooks {
    override val contextKind = PhaseStepHookContextKind.PLANNING

    override fun beforeAgentLaunch(
      run: PhaseRun,
      context: PhaseAttemptLaunchHookContext,
      state: PhaseStepBinding,
    ): String? =
      if (PlanDecompositionStop.requiresBundle(run.request)) {
        (context as PhasePlanningLaunchContext).existingBundleReason()
      } else {
        null
      }

    override fun settleCompletedRound(
      context: PhaseStepOutputContext,
      capture: ValidatedOutputCapture,
      attested: NormalizedFeatureTaskRuntimePhaseOutput,
      outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
    ): AttemptResult? =
      if (PlanDecompositionStop.requiresBundle(context.request)) {
        (context as PhasePlanningOutputContext).settleAuthoredBundle(capture)
      } else {
        null
      }

    override fun acceptedOutput(
      context: PhaseStepOutputContext,
      capture: ValidatedOutputCapture,
      attested: NormalizedFeatureTaskRuntimePhaseOutput,
      outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
    ): NormalizedFeatureTaskRuntimePhaseOutput =
      if (PlanDecompositionStop.requiresBundle(context.request)) {
        (context as PhasePlanningOutputContext).withAuthoredParentSpecPath(attested)
      } else {
        attested
      }

    override fun afterCompletion(
      context: PhaseAttemptTraversalHookContext,
      output: FeatureTaskRuntimePhaseOutput,
    ): String? =
      (
        context as? PhasePlanningTraversalContext
          ?: error("Plan completion requires the accepted planning traversal context.")
      ).settlePlanningStop(output)
  }

  internal object PlanResumeRules : PhaseResumeRules {
    override val buffersIncompleteOutput: Boolean = false

    override fun dropsResumedCompletion(completedStepIds: Set<String>): Boolean =
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN !in completedStepIds
  }

  companion object {
    const val ID = "agent-plan"

    private const val FEATURE_SPEC_DIRECTIVE =
      "/skillbill/engine/featuretask/slot/plan/feature-spec-directive.md"

    private val featureSpecDirective: String by lazy { directiveResource(FEATURE_SPEC_DIRECTIVE).trimEnd() }

    private const val BUNDLE_DIRECTIVE: String =
      "Author a governed spec bundle that satisfies every acceptance criterion, using the upstream preplan " +
        "value as planning context. Write files only inside a new .feature-specs/<issue key>-<slug>/ " +
        "directory and modify no other repository file. When the bundle is complete, finish with a short " +
        "prose summary of the plan."

    private const val DIRECTIVE: String =
      "Produce an ordered implementation plan that satisfies every acceptance criterion, using the upstream " +
        "preplan value as planning context. Do not modify repository files during this phase. Write the plan " +
        "as prose the implement phase can follow: the ordered tasks, the acceptance criteria each one serves, " +
        "the paths or symbols it touches, the tests to add or run, constraints, and how the plan is validated. " +
        "Do not forward progress diagnostics or a generic summary."

    private val GOAL_CONTINUATION_CONSTRAINT: String =
      """
      ## Goal-continuation planning constraint
      This run is already executing one governed decomposed subtask. Do not propose a new decomposition in
      the plan phase. Produce an implementable plan in prose for the current spec.
      Never include installer, uninstall, or
      install-sync commands in the plan: do not plan to run
      `./install.sh`, `./uninstall.sh`, `skill-bill install`, `skill-bill install apply`, or any
      equivalent install refresh inside a goal-continuation child. The plan phase defines how future
      acceptance work will be implemented and validated; it does not require that work to have already
      happened. Never block planning merely because a later implementation or validation action is not
      yet complete. A blocked plan requires a genuinely missing input or an irreconcilable constraint
      that prevents an implementable plan from being produced.
      """.trimIndent()

    private val SPEC_BUNDLE_REQUIREMENT: String =
      """
      ## Spec bundle planning requirement
      No later phase consumes this plan: the runtime accepts it as a governed spec bundle that you author on
      disk. Create the new directory .feature-specs/<issue key>-<slug>/ (it must not exist yet) holding the
      parent spec.md, one spec_subtask_<id>_<slug>.md per subtask (one or more, in ascending dependency
      order), and decomposition-manifest.yaml whose parent spec path names that spec.md and whose subtasks
      list those files in order, each depending only on earlier subtasks. The parent and every subtask spec
      need an Acceptance Criteria list as the Spec Format Contract requires. Write nothing outside that
      directory, never write through a symlink, and never overwrite an existing spec. A bundle that fails
      these checks blocks the plan.
      """.trimIndent()
  }
}
