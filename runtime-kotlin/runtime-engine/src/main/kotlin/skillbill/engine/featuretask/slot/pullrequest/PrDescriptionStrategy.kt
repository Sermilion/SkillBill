package skillbill.engine.featuretask.slot.pullrequest

import skillbill.engine.directive.directiveResource
import skillbill.engine.featuretask.lifecycle.branch.requirePublishableBranch
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.slot.PhaseStepHookContextKind
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhasePullRequestLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhasePullRequestContext
import skillbill.engine.featuretask.slot.state.PhasePullRequestStepBinding
import skillbill.engine.featuretask.slot.state.PhaseStepBinding
import skillbill.engine.featuretask.slot.withMeasuredFacts
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.PullRequestTemplateFiles
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.skeleton.SkeletonRunStateKind
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class PrDescriptionStrategy(
  private val pullRequestIdentityLookup: PullRequestIdentityLookup,
  private val readinessGate: PullRequestReadinessGate,
  private val templateFiles: PullRequestTemplateFiles,
) : PhaseStrategy() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to
        PhaseStepPolicy(
          mutating = false,
          singleAgentSession = false,
          readOnlyIdle = false,
          fileMutating = true,
          generationScoped = false,
        ),
    )

  private val lookups = ConcurrentHashMap<String, Lookups>()
  private val templates = ConcurrentHashMap<Path, PullRequestTemplate>()

  private val measuredHooks =
    object : PhaseStepHooks {
      override val contextKind = PhaseStepHookContextKind.PULL_REQUEST

      override fun beforeAgentLaunch(
        run: PhaseRun,
        context: PhaseAttemptLaunchHookContext,
        state: PhaseStepBinding,
      ): String? = (context as PhasePullRequestLaunchHookContext).pushResolvedBranchIfAhead()

      override fun acceptedOutput(
        context: PhaseStepOutputContext,
        capture: ValidatedOutputCapture,
        attested: NormalizedFeatureTaskRuntimePhaseOutput,
        outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
      ): NormalizedFeatureTaskRuntimePhaseOutput {
        val resolved = context.resolvedBranch()
        val branchName =
          requirePublishableBranch(resolved?.branch, resolved?.baseBranch ?: DEFAULT_BASE_BRANCH)
        val measurement =
          PullRequestMeasurement(pullRequestIdentityLookup, context.request.repoRoot, context.diagnostics)
        val lookup = lookups[context.request.workflowId]
        val after = measurement.identity(branchName).also { identity -> lookup?.after = identity }
        return attested.withMeasuredFacts(measurement.facts(lookup?.before, after))
      }
    }

  override val slot: PhaseSlot = PhaseSlot.PULL_REQUEST
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun directiveFor(stepId: String): String {
    policies.policyOf(stepId)
    return DIRECTIVE
  }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = directiveFor(stepId),
      stepContext = listOf(RULES, PrDescriptionPromptRules.section(template(inputs.repoRoot))).joinToString("\n\n"),
      valueContent = VALUE_CONTENT,
    )

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome {
    state.requireAcceptedStep(run, strategyId)
    val context =
      (
        state as? PhasePullRequestStepBinding
          ?: error("PR requires its accepted execution binding.")
      ).pullRequestContext()
    val resolved = context.resolvedBranch()
    val branch = requirePublishableBranch(resolved?.branch, resolved?.baseBranch ?: DEFAULT_BASE_BRANCH)
    val baseBranch = resolved?.baseBranch ?: DEFAULT_BASE_BRANCH
    if (context.request.skeletonDefinition?.runStateKind != SkeletonRunStateKind.IN_MEMORY &&
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH in context.transitions.forwardPhaseIds
    ) {
      readinessGate
        .blockedReason(
          workflowId = context.request.workflowId,
          repoRoot = context.request.repoRoot,
          baseBranch = baseBranch,
          gitOperations = context.gitOperations,
        )?.let { reason -> return PhaseOutcome.blocked(reason) }
    }
    val repoRoot = context.request.repoRoot
    val template = template(repoRoot)
    (template as? PullRequestTemplate.Ambiguous)?.let { ambiguous ->
      return PhaseOutcome.blocked(
        "multiple pull request templates and no default: ${ambiguous.paths.joinToString(", ")}",
      )
    }
    val workflowId = context.request.workflowId
    val measurement = measurement(context)
    val lookup = Lookups(before = measurement.identity(branch))
    lookups[workflowId] = lookup
    templates[repoRoot] = template
    val outcome =
      try {
        runAgentStep(run, state)
      } finally {
        lookups.remove(workflowId)
        templates.remove(repoRoot)
      }
    if (outcome.completedOutput != null) {
      PrDescriptionGeneratedEmission(context, measurement).emit(lookup.before, lookup.after, branch, baseBranch)
    }
    return outcome
  }

  override fun stepHooks(stepId: String): PhaseStepHooks = measuredHooks

  private fun template(repoRoot: Path?): PullRequestTemplate =
    repoRoot?.let { root -> templates[root] ?: PullRequestTemplateSearch.resolve(root, templateFiles) }
      ?: PullRequestTemplate.Absent

  private fun measurement(context: PhasePullRequestContext): PullRequestMeasurement =
    PullRequestMeasurement(pullRequestIdentityLookup, context.request.repoRoot, context.diagnostics)

  private class Lookups(
    val before: PullRequestIdentity,
  ) {
    @Volatile var after: PullRequestIdentity? = null
  }

  companion object {
    const val ID = "pr-description"
    private const val DEFAULT_BASE_BRANCH = "main"
    private const val RULES_RESOURCE = "/skillbill/engine/featuretask/slot/pullrequest/pr-description-directive.md"

    private val RULES: String by lazy { directiveResource(RULES_RESOURCE).trimEnd() }

    private const val DIRECTIVE: String =
      "Write the pull request title and description by the pull request description rules below, then create " +
        "or reuse the open pull request for the branch idempotently."

    private const val VALUE_CONTENT: String =
      "Carry, as prose, the pull request URL and the title you set. The runtime looks the pull request up for the\n" +
        "branch itself before and after this step to record its number and whether this step created it."
  }
}
