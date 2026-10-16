package skillbill.engine.featuretask.slot.monitor

import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.engine.featuretask.phase.core.MonitorRepairStepRoles
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseExecutionBindingKind
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.qualitygate.runtimeOwnedValidateAgentPhaseTask
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseCiObservation
import skillbill.engine.featuretask.slot.state.PhaseMonitorStepBinding
import skillbill.engine.featuretask.slot.state.PullRequestCiOutcome
import skillbill.ports.goalrunner.runner.PullRequestChecksLookup
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
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
        onCiRunning: (String, List<PullRequestCheck>) -> Unit,
        expectedHeadSha: String?,
        onInconclusive: (String) -> Unit,
      ): PullRequestCiOutcome = watcher.watch(repoRoot, branch, onCiRunning, expectedHeadSha, onInconclusive)

      override fun recordFailingChecks(
        issueKey: String,
        checks: List<PullRequestCheck>,
      ) = fixBrief.record(issueKey, checks)

      override fun recordMergeConflict(
        issueKey: String,
        baseBranch: String,
      ) = fixBrief.recordConflict(issueKey, baseBranch)
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

  internal override val monitorRepairSteps =
    MonitorRepairStepRoles(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
    )

  override val slot: PhaseSlot = PhaseSlot.MONITOR
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun executionBindingKind(stepId: String): PhaseExecutionBindingKind =
    PhaseExecutionBindingKind.CI_MONITOR.also { policies.policyOf(stepId) }

  override fun stepHooks(stepId: String): PhaseStepHooks =
    if (stepId == entryStep) PhaseStepHooks.None else repairPublication

  private val repairPublication =
    object : PhaseStepHooks {
      override fun settleCompletedOutput(
        run: PhaseRun,
        context: PhaseStepOutputContext,
        state: PhaseAcceptedStepExecution,
        outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
      ): PhaseStepOutputCheck =
        (state as PhaseMonitorStepBinding).publishRepair(run)?.let { PhaseStepOutputCheck.Block(it) }
          ?: PhaseStepOutputCheck.Accept
    }

  override fun directiveFor(stepId: String): String {
    policies.policyOf(stepId)
    return if (stepId == entryStep) {
      MONITOR_DIRECTIVE
    } else {
      runtimeOwnedValidateAgentPhaseTask() + " " + MONITOR_FIX_DIRECTIVE
    }
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
        runsValidationGate = true,
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
        "A failed check on an open pull request starts repair even while other checks are pending. " +
        "The initial failure starts repair but does not count as a failed repair. Only a completed, " +
        "published repair whose associated commit has a failing CI result counts toward the three-failure cap. " +
        "A merge conflict starts merge repair without counting as a CI failure. Cancelled checks do not start " +
        "repair. All checks passing, skipped, or cancelled completes the phase only when none is pending. " +
        "While checks are pending, report the current pull request and available commit or check links. Describe " +
        "queued checks as waiting, describe running work only when the observation identifies it, and name the next " +
        "useful event, such as a check starting or settling. Attribute runner capacity only to observed evidence; " +
        "if no capacity detail is available, say it is unavailable and do not query administrative APIs. " +
        "Do not infer a commit link, check state, elapsed time, or runner capacity. " +
        "Monitoring waits while checks are pending. " +
        "If no check has been reported within an hour, or a " +
        "GitHub CLI cannot report checks, monitoring blocks."

    private const val MONITOR_FIX_DIRECTIVE: String =
      "Monitor repair completion requires both a repository fix for the recorded failure and passing local " +
        "project checks. Passing local checks alone does not satisfy this phase, even when discovery finds " +
        "no applicable checks. When the brief names a merge conflict, merge the named base branch into the " +
        "current branch and resolve every conflict. When it names failing checks, open each recorded check " +
        "URL and read that failing job's logs first, for example with " +
        "`gh run view <run-id> --job <job-id> --log-failed`. Identify the failed command, assertion or error, " +
        "and relevant CI environment. Trace that evidence through the repository code, tests, and CI " +
        "configuration before editing, then fix the cause in the working tree. A passing local rerun does " +
        "not resolve the recorded CI failure. If it does not reproduce locally, investigate timing, " +
        "concurrency, isolation, and environment differences as the evidence warrants. Fix any identified " +
        "race or nondeterministic test setup without weakening assertions or skipping coverage, and run " +
        "targeted proof as well as the required project checks. Do not settle completed with an unchanged " +
        "tree merely because local checks passed, or use a CI rerun as a substitute for investigating the " +
        "cause. If investigation cannot establish a repository fix, settle blocked with the failed check " +
        "URL, the evidence inspected, and the unresolved cause or missing evidence, even if all local checks " +
        "pass. Unavailable CI logs, missing secrets, or an infrastructure outage also require a block with " +
        "the concrete obstacle. Do not commit or push. The runtime commits and pushes a completed repair " +
        "and owns subsequent CI confirmation. In natural prose, explain the CI failure, its cause, the repository " +
        "change, and local check results. Local validation is not a claim that CI passed. Never weaken assertions, " +
        "suppress findings, disable rules, or skip tests to silence the failure."
  }
}
