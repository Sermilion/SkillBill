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
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
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
          inputs.suppressDecomposition -> "$GOAL_CONTINUATION_CONSTRAINT\n\n$PHASE_FEASIBILITY_CONSTRAINT"
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
      "Author a governed spec bundle that satisfies every acceptance criterion from the upstream preplan " +
        "digest. Write files only inside a new .feature-specs/<issue key>-<slug>/ directory and modify no " +
        "other repository file. When the bundle is complete, finish with a short prose summary of the plan. " +
        PREPLAN_DIGEST_AUTHORITY + " Do not read existing .feature-specs bundles either; the manifest template " +
        "below is the format."

    private const val DIRECTIVE: String =
      "Produce an ordered implementation plan that satisfies every acceptance criterion from the upstream " +
        "preplan digest. Do not modify repository files during this phase. Write the plan " +
        "as prose the implement phase can follow: the ordered tasks, the acceptance criteria each one serves, " +
        "the paths or symbols it touches, the tests to add or run, constraints, and how the plan is validated. " +
        "Do not forward progress diagnostics or a generic summary. " + PREPLAN_DIGEST_AUTHORITY

    private val PHASE_FEASIBILITY_CONSTRAINT: String =
      """
      ## Phase feasibility
      Check every planned requirement, constraint, non-goal, and task against the authority of the
      phase that must perform it. Implement produces repository end states; audit inspects them.
      Validation owns commands and their evidence. Review, commit, PR, history, and install work
      stays with its owning phase or parent runtime. Never invent a scope restriction that prevents
      required review or validation repairs to production wiring, test setup, formatting, or lint.
      Preserve behavior, assertions, and architecture rules instead. Preserve explicit operator
      constraints; report any conflict with required phase work during planning, before execution.
      """.trimIndent()

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

      Write decomposition-manifest.yaml in exactly this shape, one subtasks entry per subtask spec. Every
      field shown is required; dependencies lists only earlier subtask ids:

      ```yaml
      ---
      contract_version: "0.5"
      issue_key: "<issue key>"
      feature_name: "<slug>"
      parent_spec_path: ".feature-specs/<issue key>-<slug>/spec.md"
      status: "pending"
      execution_model: "same_branch_commit_per_subtask"
      base_branch: "<repository default branch>"
      feature_branch: "feat/<issue key>-<slug>"
      stack_branches: []
      current_subtask_intent:
        subtask_id: 1
        action: "start"
      subtasks:
      - id: 1
        name: "<subtask name>"
        spec_path: ".feature-specs/<issue key>-<slug>/spec_subtask_1_<subtask slug>.md"
        status: "pending"
        branch: null
        commit_sha: null
        workflow_id: null
        blocked_reason: null
        last_resumable_step: null
        dependencies: []
      - id: 2
        name: "<subtask name>"
        spec_path: ".feature-specs/<issue key>-<slug>/spec_subtask_2_<subtask slug>.md"
        status: "pending"
        branch: null
        commit_sha: null
        workflow_id: null
        blocked_reason: null
        last_resumable_step: null
        dependencies:
        - subtask_id: 1
          optional: false
          skipped: false
      ```
      """.trimIndent()
  }
}
