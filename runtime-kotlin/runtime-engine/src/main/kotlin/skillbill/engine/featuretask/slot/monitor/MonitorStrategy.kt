package skillbill.engine.featuretask.slot.monitor

import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseExecutionBindingKind
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseCiObservation
import skillbill.engine.featuretask.slot.state.PhaseMonitorStepBinding
import skillbill.engine.featuretask.slot.state.PullRequestCiOutcome
import skillbill.ports.goalrunner.runner.PullRequestChecksLookup
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

class MonitorStrategy(
  identityLookup: PullRequestIdentityLookup,
  checksLookup: PullRequestChecksLookup,
) : PhaseStrategy() {
  private val watcher = PullRequestCiWatcher(identityLookup, checksLookup)
  private val fixBrief = MonitorFixBrief()
  private val observation =
    object : PhaseCiObservation {
      override fun watch(
        repoRoot: Path,
        branch: String,
      ): PullRequestCiOutcome = watcher.watch(repoRoot, branch)

      override fun recordFailingChecks(
        issueKey: String,
        checks: List<PullRequestCheck>,
      ) = fixBrief.record(issueKey, checks)
    }

  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR to
        PhaseStepPolicy(
          mutating = false,
          singleAgentSession = false,
          readOnlyIdle = false,
          fileMutating = false,
          generationScoped = false,
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX to
        PhaseStepPolicy(
          mutating = true,
          singleAgentSession = false,
          readOnlyIdle = false,
          fileMutating = true,
          generationScoped = false,
          extendsOwnedInventory = true,
        ),
    )

  override val slot: PhaseSlot = PhaseSlot.MONITOR
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun executionBindingKind(stepId: String): PhaseExecutionBindingKind =
    if (stepId == entryStep) PhaseExecutionBindingKind.CI_MONITOR else super.executionBindingKind(stepId)

  override fun directiveFor(stepId: String): String {
    policies.policyOf(stepId)
    return if (stepId == entryStep) MONITOR_DIRECTIVE else MONITOR_FIX_DIRECTIVE
  }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections {
    policies.policyOf(stepId)
    return if (stepId == entryStep) {
      PhaseStepPromptSections(taskDirective = directiveFor(stepId), settles = false)
    } else {
      PhaseStepPromptSections(
        taskDirective = directiveFor(stepId),
        stepContext = fixBrief.stepContextFor(inputs.issueKey),
      )
    }
  }

  override fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> {
    policies.policyOf(stepId)
    return if (stepId == entryStep) {
      FeatureTaskRuntimeRunInvariantPromptAllowlist.FINALIZATION
    } else {
      super.briefingInvariantFields(stepId)
    }
  }

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome =
    if (run.phaseId == entryStep) {
      (
        state as? PhaseMonitorStepBinding
          ?: error("Monitor requires its accepted execution binding.")
      ).runMonitor(run, observation)
    } else {
      runAgentStep(run, state)
    }

  companion object {
    const val ID = "monitor-ci"

    private const val MONITOR_DIRECTIVE: String =
      "This phase does not launch an agent. The runtime watches every check reported for the pull request " +
        "the workflow created. A merged pull request completes monitoring regardless of its checks. " +
        "A failed or cancelled check on an open pull request immediately completes it with verdict ci_failed, " +
        "which routes to monitor_fix even while other checks are pending. All checks passing or skipped " +
        "completes the phase only when none is pending. Monitoring waits while checks are running. " +
        "If no check has been reported within an hour, or a " +
        "GitHub CLI cannot report checks, monitoring blocks."

    private const val MONITOR_FIX_DIRECTIVE: String =
      "Fix the root cause of the failing CI checks in the working tree. Read the failing run logs first, for " +
        "example with `gh run view <run-id> --log-failed`, and fix the cause rather than the symptom. Do not " +
        "commit or push: the runtime commits and pushes the fix. Settle with a prose summary of what you " +
        "changed. If the failure cannot be fixed in this repository, such as missing secrets or an " +
        "infrastructure outage, block with the reason instead."
  }
}
