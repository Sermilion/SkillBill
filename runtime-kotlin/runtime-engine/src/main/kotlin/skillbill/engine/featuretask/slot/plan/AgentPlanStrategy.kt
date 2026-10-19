package skillbill.engine.featuretask.slot.plan

import skillbill.engine.directive.directiveResource
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.slot.PhaseStepHookContextKind
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhasePlanningOutputContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseBlockResume
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
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
    return BUNDLE_DIRECTIVE
  }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections {
    policies.policyOf(stepId)
    return PhaseStepPromptSections(
      taskDirective = directiveFor(stepId),
      testValueDiscipline = true,
      stepContext =
        "$featureSpecDirective\n\n${specBundleRequirement(inputs.specRewritable)}\n\n$MANIFEST_TEMPLATE\n\n" +
          PHASE_FEASIBILITY_CONSTRAINT,
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

  internal object PlanStepHooks : PhaseStepHooks {
    override val contextKind = PhaseStepHookContextKind.PLANNING

    override fun settleCompletedRound(
      context: PhaseStepOutputContext,
      capture: ValidatedOutputCapture,
      attested: NormalizedFeatureTaskRuntimePhaseOutput,
      outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
    ): AttemptResult? = (context as PhasePlanningOutputContext).settleAuthoredBundle(capture)

    override fun acceptedOutput(
      context: PhaseStepOutputContext,
      capture: ValidatedOutputCapture,
      attested: NormalizedFeatureTaskRuntimePhaseOutput,
      outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
    ): NormalizedFeatureTaskRuntimePhaseOutput =
      (context as PhasePlanningOutputContext).withAuthoredParentSpecPath(attested)
  }

  internal object PlanResumeRules : PhaseResumeRules {
    override val buffersIncompleteOutput: Boolean = false

    override fun persistedBlockResume(
      reason: String,
      recentBlockedReasons: List<String?>,
    ): PhaseBlockResume = PhaseBlockResume.RELAUNCH_WITH_FRESH_BUDGET

    override fun dropsResumedCompletion(completedStepIds: Set<String>): Boolean =
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN !in completedStepIds
  }

  internal object PlanSubtasksResumeRules : PhaseResumeRules {
    override val buffersIncompleteOutput: Boolean = false

    override fun persistedBlockResume(
      reason: String,
      recentBlockedReasons: List<String?>,
    ): PhaseBlockResume = PhaseBlockResume.RELAUNCH_WITH_FRESH_BUDGET

    override fun dropsResumedCompletion(completedStepIds: Set<String>): Boolean =
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN !in completedStepIds
  }

  companion object {
    const val ID = "agent-plan"

    private const val FEATURE_SPEC_DIRECTIVE =
      "/skillbill/engine/featuretask/slot/plan/feature-spec-directive.md"

    private val featureSpecDirective: String by lazy { directiveResource(FEATURE_SPEC_DIRECTIVE).trimEnd() }

    private const val BUNDLE_DIRECTIVE: String =
      "Author a governed spec bundle that satisfies every acceptance criterion from the upstream preplan " +
        "digest. Write files only inside the .feature-specs/<issue key>-<slug>/ directory that already " +
        "holds the parent spec.md and modify no other repository file. When the bundle is complete, finish " +
        "with a short prose summary of the plan. " +
        PREPLAN_DIGEST_AUTHORITY + " Do not read other .feature-specs bundles; inspect this workflow's own " +
        "bundle on resume. " + PLANNING_INSTALL_PROHIBITION + " The manifest template " +
        "below is the format."

    internal val PHASE_FEASIBILITY_CONSTRAINT: String =
      """
      ## Phase feasibility
      Check every planned requirement, constraint, non-goal, and task against the authority of the
      phase that must perform it. Implement produces repository end states; audit inspects them.
      Acceptance criteria are production end states only; tests are never acceptance criteria and
      belong under Test Obligations. Validation owns commands and their evidence. Review, commit, PR, history, and install work
      stays with its owning phase or parent runtime. Never invent a scope restriction that prevents
      required review or validation repairs to production wiring, test setup, formatting, or lint.
      Preserve behavior, assertions, and architecture rules instead. Preserve explicit operator
      constraints; report any conflict with required phase work during planning, before execution.
      """.trimIndent()

    private fun specBundleRequirement(specRewritable: Boolean): String {
      val parentSpecRule =
        if (specRewritable) {
          "The runtime seeded that spec.md from the intake; rewrite it as the full parent spec when the plan " +
            "needs more than the seed."
        } else {
          "The operator authored that spec.md; never modify it, the runtime blocks the plan when its bytes change."
        }
      return """
        ## Spec bundle planning requirement
        No later phase consumes this plan: the runtime accepts it as a governed spec bundle that you author on
        disk. The directory .feature-specs/<issue key>-<slug>/ already holds the parent spec.md. $parentSpecRule
        Add one spec_subtask_<id>_<slug>.md per subtask (one or more, in ascending dependency order) and
        decomposition-manifest.yaml whose parent spec path names that spec.md and whose
        subtasks list those files in order, each depending only on earlier subtasks. The parent and every subtask
        spec need an Acceptance Criteria list as the Spec Format Contract requires. Each spec you author must
        have one to five criteria; preserve an operator-authored parent unchanged even if it exceeds five.
        Split a subtask needing more than five criteria into the fewest dependency-ordered specs needed
        to keep each at five or fewer. Balance the counts so they differ by at most one: six criteria
        become two specs with three each; eleven become three specs with four, four, and three.
        Cover every upstream requirement without combining unrelated outcomes or hiding criteria in nested lists.
        Do not write an Implementation
        Details section: the runtime plans each subtask in the plan_subtasks step that follows this one.
        Write nothing outside that directory and never write through a symlink. When resuming, this workflow's
        previously authored manifest and subtask specs may already exist. Inspect them, keep valid content, and
        repair incomplete content in that same bundle. An existing file in your own resumed bundle is not a reason
        to block.
        Never replace a bundle owned by another workflow. A bundle that
        fails these checks blocks the plan.
        """.trimIndent()
    }

    private val MANIFEST_TEMPLATE: String =
      """
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
