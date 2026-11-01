package skillbill.engine.featuretask.slot.monitor

import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.engine.featuretask.phase.core.MonitorRepairStepRoles
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.MonitorRepairCapability
import skillbill.engine.featuretask.slot.PhaseBindingFamily
import skillbill.engine.featuretask.slot.PhaseStepDescriptor
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.qualitygate.runtimeOwnedValidateAgentPhaseTask
import skillbill.engine.featuretask.slot.state.ExpectedCiHead
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

public class MonitorStrategy(
  private val identityLookup: PullRequestIdentityLookup,
  checksLookup: PullRequestChecksLookup,
) : PhaseStrategy(),
  MonitorRepairCapability by MonitorRepairRoles {
  private val watcher = PullRequestCiWatcher(identityLookup, checksLookup)
  private val fixBrief = MonitorFixBrief()
  private val observation =
    object : PhaseCiObservation {
      override fun watch(
        repoRoot: Path,
        branch: String,
        onCiRunning: (String, List<PullRequestCheck>) -> Unit,
        expectedHead: ExpectedCiHead,
        onInconclusive: (String) -> Unit,
      ): PullRequestCiOutcome = watcher.watch(repoRoot, branch, onCiRunning, expectedHead, onInconclusive)

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

  override val slot: PhaseSlot = PhaseSlot.MONITOR
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun stepDescriptor(stepId: String): PhaseStepDescriptor<*> {
    policies.policyOf(stepId)
    return if (stepId == entryStep) {
      PhaseStepDescriptor(PhaseBindingFamily.Monitor, PhaseStepHooks.None) { run, state ->
        state.runMonitor(run, observation)
      }
    } else {
      PhaseStepDescriptor(PhaseBindingFamily.Monitor, repairPublication) { run, state -> runAgentStep(run, state) }
    }
  }

  private val repairPublication =
    object : PhaseStepHooks<PhaseMonitorStepBinding> {
      override fun settleCompletedOutput(
        run: PhaseRun,
        context: PhaseStepOutputContext,
        state: PhaseMonitorStepBinding,
        outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
      ): PhaseStepOutputCheck =
        state
          .publishRepair(run, identityLookup, fixBrief.conflictBaseBranch(run.request.issueKey))
          ?.let { PhaseStepOutputCheck.Block(it) }
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
        runsBuildGate = true,
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

  public companion object {
    public const val ID: String = "monitor-ci"

    private const val MONITOR_DIRECTIVE: String =
      "This phase does not launch an agent. The runtime watches every check reported for the pull request " +
        "the workflow created. A merged pull request completes monitoring regardless of its checks. " +
        "A failed check on an open pull request starts repair even while other checks are pending. " +
        "The initial failure starts repair but does not count as a failed repair. Only a completed, " +
        "published repair whose associated commit or pull request edit has a failing CI result counts toward the " +
        "three-failure cap. After a repair that only edited the pull request, failures count once checks settle " +
        "and a check has run since the repair started, or after a ten-minute grace. " +
        "A merge conflict starts merge repair without counting as a CI failure. Cancelled checks do not start " +
        "repair. All checks passing, skipped, or cancelled completes the phase only when none is pending. " +
        "While checks are pending, report the current pull request and available commit or check links. Describe " +
        "queued checks as waiting, describe running work only when the observation identifies it, and name the next " +
        "useful event, such as a check starting or settling. Attribute runner capacity only to observed evidence; " +
        "if no capacity detail is available, say it is unavailable and do not query administrative APIs. " +
        "Do not infer a commit link, check state, elapsed time, or runner capacity. " +
        "Monitoring waits while checks are pending. A failed GitHub identity or checks read is recorded and retried " +
        "as unknown for that round; it does not establish that CI has not started. If no check has been reported " +
        "within an hour after a usable no-check observation, monitoring blocks."

    private const val MONITOR_FIX_DIRECTIVE: String =
      "Monitor repair completion requires both a fix for the recorded failure and passing runnable local " +
        "project checks. " +
        "Passing local checks alone does not satisfy this phase, even when discovery finds no applicable checks. " +
        "When the brief names a merge conflict, follow its merge steps: merge the fetched remote base without " +
        "committing, resolve every conflict, and leave the merge in progress for the runtime to commit. " +
        "When it names failing checks, open each recorded check URL and read that failing job's " +
        "logs or the check's report first, for example with `gh run view <run-id> --job <job-id> --log-failed` " +
        "or the pull request comment the check links to. Identify the failed command, assertion, rule or error, " +
        "and relevant CI environment. When the evidence shows the check rejects the pull request itself, such as " +
        "its title, description or labels, fix the pull request with `gh pr edit` so it satisfies the rule the " +
        "check enforces, following the repository's conventions, and change repository files only when the " +
        "failure also needs them. Otherwise trace that evidence through the repository code, tests, and CI " +
        "configuration before editing, then fix the cause in the working tree. A passing local rerun does " +
        "not resolve the recorded CI failure. If it does not reproduce locally, investigate timing, " +
        "concurrency, isolation, and environment differences as the evidence warrants. Fix any identified " +
        "race or nondeterministic test setup without weakening assertions or skipping coverage, and run " +
        "targeted proof as well as the required project checks. Do not settle completed when neither the " +
        "working tree nor the pull request changed merely because local checks passed, or use a CI rerun as a " +
        "substitute for investigating the cause. If investigation cannot establish a repository or pull request " +
        "fix, settle blocked with the failed check URL, the evidence inspected, and the unresolved cause or " +
        "missing evidence, even if all local checks pass. Unavailable CI logs, missing secrets, or an " +
        "infrastructure outage also require a block with the concrete obstacle. Do not commit or push. The " +
        "runtime commits and pushes a completed repair and owns subsequent CI confirmation. In natural prose, " +
        "explain the CI failure, its cause, the repository or pull request change, and local check results. " +
        "Local validation is not a claim that CI passed. Never weaken assertions, suppress findings, disable " +
        "rules, or skip tests to silence the failure."
  }
}

private object MonitorRepairRoles : MonitorRepairCapability {
  override val monitorRepairSteps =
    MonitorRepairStepRoles(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
    )
}
