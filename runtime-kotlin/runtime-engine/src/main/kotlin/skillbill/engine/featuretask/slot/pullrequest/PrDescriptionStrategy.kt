package skillbill.engine.featuretask.slot.pullrequest

import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.slot.PhaseRunState
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.withMeasuredFacts
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.PhaseSlot
import skillbill.workflow.taskruntime.model.core.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.util.concurrent.ConcurrentHashMap

class PrDescriptionStrategy(
  override val runner: PhaseRunner,
  private val pullRequestIdentityLookup: PullRequestIdentityLookup,
  private val readinessGate: PullRequestReadinessGate,
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

  private val beforeLookups = ConcurrentHashMap<String, PullRequestIdentity>()

  private val measuredHooks =
    object : PhaseStepHooks {
      override fun acceptedOutput(
        context: FeatureTaskRuntimeRunLoopContext,
        capture: ValidatedOutputCapture,
        attested: NormalizedFeatureTaskRuntimePhaseOutput,
        outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
      ): NormalizedFeatureTaskRuntimePhaseOutput =
        attested.withMeasuredFacts(
          measurement(context).facts(beforeLookups[context.request.workflowId], branch(context)),
        )
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
      valueContent = VALUE_CONTENT,
    )

  override fun runStep(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
    state: PhaseRunState,
  ): PhaseOutcome {
    readinessGate.blockedReason(
      workflowId = context.request.workflowId,
      repoRoot = context.request.repoRoot,
      baseBranch = context.recorder.loadResolvedBranch(context.request.workflowId)?.baseBranch ?: "main",
      gitOperations = context.phaseGates.gitOperations,
    )?.let { reason -> return PhaseOutcome.blocked(reason) }
    val workflowId = context.request.workflowId
    beforeLookups[workflowId] = measurement(context).identity(branch(context))
    return try {
      runAgentStep(run, context, state)
    } finally {
      beforeLookups.remove(workflowId)
    }
  }

  override fun stepHooks(stepId: String): PhaseStepHooks = measuredHooks

  private fun measurement(context: FeatureTaskRuntimeRunLoopContext): PullRequestMeasurement =
    PullRequestMeasurement(pullRequestIdentityLookup, context.request.repoRoot, context.diagnostics)

  private fun branch(context: FeatureTaskRuntimeRunLoopContext): String? =
    context.recorder.loadResolvedBranch(context.request.workflowId)?.branch

  companion object {
    const val ID = "pr-description"

    private const val DIRECTIVE: String =
      "Invoke bill-pr-description, honor any repo-native PR template except its checklist, and generate a title " +
        "in the form `[<issue key>] <descriptive title>` that explains the user-visible outcome rather than " +
        "copying a branch slug; create or reuse the open pull request for the branch idempotently."

    private const val VALUE_CONTENT: String =
      "Carry, as prose, the pull request URL and the title you set. The runtime looks the pull request up for the\n" +
        "branch itself before and after this step to record its number and whether this step created it."
  }
}
