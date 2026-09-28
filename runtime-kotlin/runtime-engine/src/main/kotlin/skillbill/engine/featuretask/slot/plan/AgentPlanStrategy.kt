package skillbill.engine.featuretask.slot.plan

import skillbill.engine.directive.directiveResource
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.directives.envelopeContract
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptEnvironment
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.jsonValueContent
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.PhaseStepState
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class AgentPlanStrategy(override val runner: PhaseRunner) : PhaseStrategy() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to
        PhaseStepPolicy(
          mutating = false,
          relaunchOnInvalidOutput = true,
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
      valueContent = if (bundleRequired) "" else VALUE_CONTENT,
      outputContract =
        if (bundleRequired) {
          envelopeContract(
            stepName = stepId,
            producedOutputsAddendum =
              ". For completed output, include a non-blank value summarizing the plan and the complete " +
                "decomposition_package described in the spec bundle planning requirement. " +
                "Both fields belong inside produced_outputs.",
            verdictContractLine = "",
          )
        } else {
          null
        },
    )
  }

  override fun runStep(
    run: PhaseRun,
    state: PhaseStepState,
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
    override fun completionRejection(
      run: PhaseRun,
      context: PhaseAttemptEnvironment,
      state: PhaseStepState,
      outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
    ): String? = PlanDecompositionStop.completionRejection(context, outputMap)

    override fun afterCompletion(
      context: PhaseAttemptEnvironment,
      output: FeatureTaskRuntimePhaseOutput,
    ): String? = PlanDecompositionStop.apply(context, output)
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
      "Produce a governed spec bundle that satisfies every acceptance criterion, using the upstream preplan " +
        "value as planning context. Return the complete phase-output envelope with produced_outputs.value " +
        "and produced_outputs.decomposition_package. Do not modify repository files; the runtime writes " +
        "the bundle from the decomposition package."

    private const val DIRECTIVE: String =
      "Produce an ordered implementation plan that satisfies every acceptance criterion, using the upstream " +
        "preplan value as planning context (structured prose: interpret the stuffed digest JSON). Do not modify " +
        "repository files during this phase. Emit produced_outputs with a non-blank value string carrying the " +
        "executable_plan JSON (same fields as before, stuffed inside value); optional prompt may add a short " +
        "directive when non-blank. Do not forward the complete plan envelope, a generic summary, or progress " +
        "diagnostics."

    private val GOAL_CONTINUATION_CONSTRAINT: String =
      """
      ## Goal-continuation planning constraint
      This run is already executing one governed decomposed subtask. Do not propose or emit a new
      decomposition package in the plan phase. Produce implementable planning value for the current spec
      (executable_plan JSON stuffed inside value); never emit produced_outputs.decomposition_package.
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
      No later phase consumes this plan: the runtime persists it as a governed spec bundle (parent spec,
      subtask specs, and decomposition manifest). Plan in mode "decompose" and emit, beside value,
      produced_outputs.decomposition_package with: "mode": "decompose", "reason", "feature_name",
      "parent_spec_overview", "validation_strategy", "base_branch", "feature_branch", and "subtasks" (one
      or more). Each subtask carries an integer "id", "name", "scope", "acceptance_criteria" (list),
      "non_goals" (list), "dependency_notes", "validation_strategy", "next_path", and "depends_on" (list of
      earlier subtask ids). Do not write the spec files yourself; the runtime writes them. A plan without a
      decomposition package blocks.
      """.trimIndent()

    private val VALUE_CONTENT: String =
      jsonValueContent(
        innerJsonExample =
          "{ \"projection_kind\": \"executable_plan\",\n" +
            "  \"contract_version\": \"0.2\",\n" +
            "  \"mode\": \"direct\",\n" +
            "  \"tasks\": [ { \"task_id\": \"task-1\", \"depends_on\": [], " +
            "\"description\": \"<imperative task>\",\n" +
            "    \"criterion_refs\": [\"AC-001\"], \"target_paths_or_symbols\": [\"path/or/Symbol\"],\n" +
            "    \"test_obligations\": [\"<test to add or run>\"], \"constraints\": [] } ],\n" +
            "  \"validation_strategy\": [\"<how the plan is validated>\"] }\n",
        notes =
          "Upstream preplan value is structured prose carrying the digest JSON; read and interpret it. " +
            "task_id MUST match ^[a-z][a-z0-9-]*\$ (lowercase kebab; \"T1\" is wrong — use \"task-1\"); " +
            "criterion_refs use the AC-### form.",
      )
  }
}
