package skillbill.engine.featuretask.slot.pullrequest

import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepState
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptEnvironment
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptScope
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.withMeasuredFacts
import skillbill.error.featuretask.PullRequestBranchRefusedError
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.PullRequestTemplateFiles
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.gitops.ProtectedBranches
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.PhaseSlot
import skillbill.workflow.taskruntime.model.core.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class PrDescriptionStrategy(
  override val runner: PhaseRunner,
  private val pullRequestIdentityLookup: PullRequestIdentityLookup,
  private val readinessGate: PullRequestReadinessGate,
  private val templateFiles: PullRequestTemplateFiles,
) : PhaseStrategy() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to
        PhaseStepPolicy(
          mutating = false,
          relaunchOnInvalidOutput = false,
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
      override fun acceptedOutput(
        context: PhaseAttemptEnvironment,
        capture: ValidatedOutputCapture,
        attested: NormalizedFeatureTaskRuntimePhaseOutput,
        outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
      ): NormalizedFeatureTaskRuntimePhaseOutput {
        val measurement = measurement(context)
        val lookup = lookups[context.request.workflowId]
        val after = measurement.identity(branch(context)).also { identity -> lookup?.after = identity }
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
      stepContext = PrDescriptionPromptRules.section(template(inputs.repoRoot)),
      valueContent = VALUE_CONTENT,
    )

  override fun runStep(
    run: PhaseRun,
    state: PhaseStepState,
  ): PhaseOutcome {
    val context = PhaseAttemptScope(run.request, state)
    val resolved = context.recorder.loadResolvedBranch(context.request.workflowId)
    val branch = resolved?.branch
    val baseBranch = resolved?.baseBranch ?: DEFAULT_BASE_BRANCH
    refusal(branch, baseBranch)?.let { reason -> throw PullRequestBranchRefusedError(branch, reason) }
    requireNotNull(branch)
    if (FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH in context.transitions.forwardPhaseIds) {
      readinessGate.blockedReason(
        workflowId = context.request.workflowId,
        repoRoot = context.request.repoRoot,
        baseBranch = baseBranch,
        gitOperations = context.phaseGates.gitOperations,
      )?.let { reason -> return PhaseOutcome.blocked(reason) }
    }
    val repoRoot = context.request.repoRoot
    val template = template(repoRoot)
    (template as? PullRequestTemplate.Ambiguous)?.let { ambiguous ->
      return PhaseOutcome.blocked(
        "multiple pull request templates and no default: ${ambiguous.paths.joinToString(", ")}",
      )
    }
    pushIfAhead(context, branch)?.let { reason -> return PhaseOutcome.blocked(reason) }
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

  private fun measurement(context: PhaseAttemptEnvironment): PullRequestMeasurement =
    PullRequestMeasurement(pullRequestIdentityLookup, context.request.repoRoot, context.diagnostics)

  private fun branch(context: PhaseAttemptEnvironment): String? =
    context.recorder.loadResolvedBranch(context.request.workflowId)?.branch

  private fun refusal(
    branch: String?,
    baseBranch: String,
  ): String? =
    when {
      branch == null -> "the checkout is on no branch."
      ProtectedBranches.protectedName(branch) != null -> "'$branch' is a protected branch."
      branch == baseBranch -> "'$branch' is the base branch."
      else -> null
    }

  private fun pushIfAhead(
    context: PhaseAttemptEnvironment,
    branch: String,
  ): String? {
    val git = context.phaseGates.gitOperations
    val unpushed = git.localBranchHasUnpushedCommits(context.request.repoRoot, branch)
    if (unpushed !is WorkflowGitOperationResult.Ok) {
      return "Could not tell whether branch '$branch' has unpushed commits: ${unpushed.error}"
    }
    if (!unpushed.value.trim().equals("true", ignoreCase = true)) return null
    val push = git.pushBranch(context.request.repoRoot, branch)
    return if (push is WorkflowGitOperationResult.Ok) null else "Could not push branch '$branch': ${push.error}"
  }

  /** The pull request lookups around one pr step: before it runs, and after its output is accepted. */
  private class Lookups(val before: PullRequestIdentity) {
    @Volatile var after: PullRequestIdentity? = null
  }

  companion object {
    const val ID = "pr-description"
    private const val DEFAULT_BASE_BRANCH = "main"

    private const val DIRECTIVE: String =
      "Write the pull request title and description by the pull request description rules below, then create " +
        "or reuse the open pull request for the branch idempotently."

    private const val VALUE_CONTENT: String =
      "Carry, as prose, the pull request URL and the title you set. The runtime looks the pull request up for the\n" +
        "branch itself before and after this step to record its number and whether this step created it."
  }
}
