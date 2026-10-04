package skillbill.engine.featuretask.slot.commitpush

import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseCommitStepBinding
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class RuntimeCommitStrategy : PhaseStrategy() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to
        PhaseStepPolicy(
          mutating = false,
          singleAgentSession = false,
          readOnlyIdle = false,
          fileMutating = true,
          generationScoped = false,
        ),
    )

  override val slot: PhaseSlot = PhaseSlot.COMMIT_PUSH
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH

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
      stepContext = COMMIT_OWNERSHIP,
      settles = false,
    )

  override fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> {
    policies.policyOf(stepId)
    return FeatureTaskRuntimeRunInvariantPromptAllowlist.FINALIZATION
  }

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome {
    state.requireAcceptedStep(run, strategyId)
    return (
      state as? PhaseCommitStepBinding
        ?: error("Commit requires its accepted execution binding.")
    ).runCommitPush(run)
  }

  override fun stepHooks(stepId: String): PhaseStepHooks {
    policies.policyOf(stepId)
    return RuntimeCommitUpstreamHeadFallback
  }

  companion object {
    const val ID = "runtime-commit"

    private const val DIRECTIVE: String =
      "This phase does not launch an agent. The runtime stages every dirty non-ignored path, including " +
        "`.feature-specs/`, commits with a subject from the issue key and subtask name, pushes, and records " +
        "commit_sha. If goal-continuation suppresses PR, this phase is the terminal success signal for the goal " +
        "subtask."

    private const val COMMIT_OWNERSHIP: String =
      "## Commit ownership\n" +
        "Never amend, reset, or restage a commit this runtime does not own, including a\n" +
        "commit a human operator authored: leave those alone."
  }
}
