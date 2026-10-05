package skillbill.engine.featuretask.slot.standalonereview

import skillbill.agentaddon.model.AgentAddonPromptFormatter
import skillbill.application.review.model.ParallelCodeReviewReportContract
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelCodeReviewRunOutcome
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunner
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.slot.PhaseExecutionBindingKind
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.engine.featuretask.slot.PhaseRepositoryObservations
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepFileManifest
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.PhaseStepSession
import skillbill.engine.featuretask.slot.PhaseStrategyStatusProjection
import skillbill.engine.featuretask.slot.codereview.InlineReviewEnvelope
import skillbill.engine.featuretask.slot.codereview.ReviewTargetResolver
import skillbill.engine.featuretask.slot.codereview.delegatedReviewRequest
import skillbill.engine.featuretask.slot.codereview.reviewSpecPath
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.PhaseReviewStepBinding
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.featuretask.slot.stepFacts
import skillbill.error.featuretask.UnknownPhaseReviewTargetError
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.model.READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.parallel.ParallelReviewFindingParser
import skillbill.review.parallel.ParallelReviewMerger
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfile
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.time.Duration.Companion.minutes

open class InlineStandaloneReviewStrategy(runner: PhaseRunner) : StandaloneReviewStrategy(runner, null) {
  override val strategyId: String = ID

  companion object {
    const val ID = "inline"
  }
}

open class DelegatedStandaloneReviewStrategy(
  runner: PhaseRunner,
  reviewRunner: ParallelCodeReviewRunner,
) : StandaloneReviewStrategy(runner, reviewRunner) {
  override val strategyId: String = ID

  companion object {
    const val ID = "delegated"
  }
}

abstract class StandaloneReviewStrategy(
  private val runner: PhaseRunner,
  private val reviewRunner: ParallelCodeReviewRunner?,
) : PhaseStrategyStatusProjection() {
  override val slot: PhaseSlot = PhaseSlot.STANDALONE_REVIEW
  override val steps: List<String> = listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PRESENT_FINDINGS)
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PRESENT_FINDINGS
  override val loopRules: PhaseLoopRules = object : PhaseLoopRules {}

  override fun executionBindingKind(stepId: String): PhaseExecutionBindingKind {
    check(stepId == entryStep)
    return PhaseExecutionBindingKind.REVIEW
  }

  override fun policyFor(stepId: String): PhaseStepPolicy {
    check(stepId == entryStep)
    return PhaseStepPolicy(
      mutating = false,
      singleAgentSession = reviewRunner == null,
      readOnlyIdle = true,
      fileMutating = false,
      generationScoped = false,
      extendsOwnedInventory = false,
    )
  }

  override fun directiveFor(stepId: String): String {
    check(stepId == entryStep)
    return "Review the resolved target and return findings only. Do not edit, stage, commit, amend, reset, " +
      "or launch another review command. Return a findings register, or NO_FINDINGS, followed by exactly one " +
      "canonical verdict line: verdict: approved or verdict: changes_requested."
  }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections = PhaseStepPromptSections(taskDirective = directiveFor(stepId), settles = false)

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome {
    val binding = state as? PhaseReviewStepBinding ?: error("Standalone review requires an accepted review binding.")
    val iteration = binding.nextStepIteration()
    binding.startReviewStep(run, iteration)?.let { return it }
    return runStartedReview(run, binding, iteration)
  }

  private fun runStartedReview(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
    iteration: Int,
  ): PhaseOutcome {
    val context = binding.reviewExecutionContext()
    val target =
      resolveTarget(run, binding, context.gitOperations)
        ?: return block(binding, iteration, "Standalone review could not resolve its requested target.")
    val head = context.gitOperations.headCommitSha(run.request.repoRoot)
    if (head !is WorkflowGitOperationResult.Ok) {
      return block(binding, iteration, "Standalone review could not read HEAD: ${head.error}")
    }
    val headSha = head.value.trim()
    val reviewInput = GoalSubtaskReviewInput(headSha, headSha, "", "")
    val invocation = run.request.reviewInvocation
    val reviewRunId = invocation?.reviewRunId ?: InlineReviewEnvelope.mintReviewRunId(context.clock)
    val directive = standaloneDirective(run, target, headSha)
    val prompt = PhaseStepPromptSource { PhaseStepPromptSections(taskDirective = directive) }
    prepareReport(binding, iteration, reviewRunId, prompt, reviewInput)?.let { return it }
    binding.reviewLaunched(iteration)
    val output = launch(run, binding, StandaloneReviewLaunchInput(target, reviewInput, reviewRunId, directive))
    val result =
      output.result
        ?: failedResult(run.resolvedAgent.resolvedAgentId, output.failureReason ?: "review did not produce a result")
    val raw = output.rawOutput
    val retainedResult =
      result.copy(
        mergeResult =
          result.mergeResult.copy(
            formattedOutput = ParallelReviewMerger.formattedOutput(result.mergeResult.findings),
          ),
        rawOutput = raw,
        reportTruncated = output.truncated || result.reportTruncated,
      )
    binding.retainReviewOutput(iteration, raw)
    return finishReport(binding, iteration, reviewRunId, retainedResult, output)
  }

  private fun prepareReport(
    binding: PhaseReviewStepBinding,
    iteration: Int,
    reviewRunId: String,
    prompt: PhaseStepPromptSource,
    reviewInput: GoalSubtaskReviewInput,
  ): PhaseOutcome? {
    val started = binding.startReview(iteration, reviewRunId)
    if (started is RequiredPhaseWrite.Rejected) return binding.blockRequiredReviewWrite(started)
    val prepared = binding.prepareReviewBriefing(iteration, prompt, reviewInput)
    return (prepared as? RequiredPhaseWrite.Rejected)?.let(binding::blockRequiredReviewWrite)
  }

  private fun finishReport(
    binding: PhaseReviewStepBinding,
    iteration: Int,
    reviewRunId: String,
    retainedResult: ParallelCodeReviewResult,
    output: StandaloneLaunch,
  ): PhaseOutcome {
    val report =
      StandaloneReviewReportAdmission.admit(
        output.rawOutput,
        retainedResult,
        output.truncated || retainedResult.reportTruncated,
        reviewRunner != null,
      )
    val failure = output.failureReason
    val rejection =
      listOfNotNull(
        failure,
        report.rejectionReasons.takeIf { it.isNotEmpty() }?.joinToString("; "),
        report.diagnostics.takeIf {
          it.isNotEmpty()
        }?.take(MAX_FAILURE_DIAGNOSTICS)?.joinToString("; ") { it.take(MAX_FAILURE_DIAGNOSTIC_CHARS) },
      ).joinToString("; ")
    val recordedResult =
      if (rejection.isBlank()) {
        retainedResult.copy(
          mergeResult = retainedResult.mergeResult.copy(formattedOutput = report.registerOutput),
        )
      } else {
        retainedResult
      }
    binding.recordReviewRun(reviewRunId, recordedResult, reviewRunner != null)
    if (rejection.isNotBlank()) {
      binding.blockReviewStep(
        iteration,
        rejection,
        FeatureTaskRuntimeFailureDisposition.RETRYABLE,
      )
      return PhaseOutcome.blocked(rejection)
    }
    return completeReport(binding, iteration, report)
  }

  private fun completeReport(
    binding: PhaseReviewStepBinding,
    iteration: Int,
    report: StandaloneReviewReport,
  ): PhaseOutcome {
    val verdict = requireNotNull(report.verdict)
    val value = report.registerOutput
    val envelope =
      linkedMapOf<String, Any?>(
        SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        SharedPayloadKeys.PHASE_ID to entryStep,
        SharedPayloadKeys.STATUS to STATUS_COMPLETED,
        SharedPayloadKeys.SUMMARY to value.take(MAX_REPORT_SUMMARY_CHARS),
        SharedPayloadKeys.PRODUCED_OUTPUTS to mapOf(SharedPayloadKeys.VALUE to value),
        SharedPayloadKeys.VERDICT to verdict,
      )
    val normalized =
      NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(FeatureTaskRuntimeWorkflowArtifactMap.from(envelope))
    binding.completeReview(
      iteration,
      value,
      normalized,
      PhaseStepFileManifest(emptyList(), emptyList()),
    )
      ?.let { reason -> return PhaseOutcome.blocked(reason) }
    binding.stepCompleted(iteration)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(
        entryStep,
        iteration,
        normalized.output,
        normalized,
      ),
    )
  }

  private fun resolveTarget(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
    gitOperations: PhaseRepositoryObservations,
  ): ReviewTarget? {
    val status = gitOperations.worktreeStatus(run.request.repoRoot)
    if (status !is WorkflowGitOperationResult.Ok) return null
    val target = ReviewTargetResolver.resolve(run.request.reviewInvocation?.target, status.value.orEmpty())
    val revisions =
      when (target) {
        is ReviewTarget.Commit -> listOf(target.sha)
        is ReviewTarget.Scoped -> listOfNotNull(target.baseRevision, target.headRevision)
        else -> emptyList()
      }
    revisions.forEach { revision ->
      if (gitOperations.resolveCommit(run.request.repoRoot, revision) !is WorkflowGitOperationResult.Ok) {
        throw UnknownPhaseReviewTargetError(revision)
      }
    }
    return binding.pinnedReviewTarget { target }
  }

  private fun standaloneDirective(
    run: PhaseRun,
    target: ReviewTarget,
    head: String,
  ): String =
    buildString {
      target.openingLines("$head^", head).forEach(::appendLine)
      appendLine("Return a findings register with one finding per line, or the exact line NO_FINDINGS when empty.")
      appendLine("End with exactly one canonical line: verdict: approved or verdict: changes_requested.")
      append(AgentAddonPromptFormatter.format(run.request.agentAddonSelection))
      appendLine()
      appendLine(
        "This is report-only. Do not apply recommendations, edit, stage, commit, amend, reset, " +
          "or launch another review command.",
      )
      appendLine("A changes_requested verdict is a valid completed report. The runtime will not repair findings.")
      if (run.launchAssignment?.profile == PhaseModelProfile.OPUS_5_5) {
        val extra =
          when (strategyId) {
            DelegatedStandaloneReviewOpus55Strategy.ID -> DelegatedStandaloneReviewOpus55Strategy.DIRECTIVE
            else -> InlineStandaloneReviewOpus55Strategy.DIRECTIVE
          }
        appendLine()
        append(extra)
      }
    }

  private fun launch(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
    facts: StandaloneReviewLaunchInput,
  ): StandaloneLaunch {
    val operatorInstructions =
      listOfNotNull(
        run.request.phaseInstructions?.forStep(entryStep),
        "Report-only review. Do not apply fixes or make repository changes.",
      ).joinToString("\n\n")
    val phaseInput =
      PhaseStepInput(
        entryStep,
        facts.directive,
        emptyMap(),
        operatorInstructions,
        run.stepFacts(run.request.workflowId.ifBlank { "code-review" }, null),
        policyFor(entryStep),
      )
    return if (reviewRunner == null) {
      launchInline(run, phaseInput, binding)
    } else {
      launchDelegated(run, binding, facts, phaseInput)
    }
  }

  private fun launchInline(
    run: PhaseRun,
    phaseInput: PhaseStepInput,
    binding: PhaseReviewStepBinding,
  ): StandaloneLaunch {
    val launched = runner.run(phaseInput, binding.launchState)
    val parsed = ParallelReviewFindingParser.parse(launched.stdout.text)
    val launchFailure = launchFailureReason(launched)
    val merged =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult(run.resolvedAgent.resolvedAgentId, parsed.findings),
        ParallelReviewLaneResult(run.resolvedAgent.resolvedAgentId, emptyList()),
      )
    return StandaloneLaunch(
      launched.stdout.text,
      ParallelCodeReviewResult(
        merged.copy(formattedOutput = launched.stdout.text),
        ParallelReviewLaneStatus(
          run.resolvedAgent.resolvedAgentId,
          launched.termination is AgentRunTermination.Exited && launched.termination.code == 0,
          launchFailure,
          reviewDisposition =
            if (launchFailure == null) {
              ReviewLaneReviewDisposition.COMPLETE
            } else {
              ReviewLaneReviewDisposition.INCOMPLETE
            },
        ),
        citationDiagnostics = parsed.citationDiagnostics,
        rawOutput = launched.stdout.text,
        rejectedCandidateCount = parsed.rejections.size,
      ),
      launchFailure,
      launched.stdout.truncated,
    )
  }

  private fun launchDelegated(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
    facts: StandaloneReviewLaunchInput,
    phaseInput: PhaseStepInput,
  ): StandaloneLaunch {
    val delegatedRunner = requireNotNull(reviewRunner)
    var result: ParallelCodeReviewResult? = null
    var failure: String? = null
    val session =
      PhaseStepSession { launch ->
        val delegatedTarget =
          if (facts.target == ReviewTarget.LastCommit) ReviewTarget.Commit(facts.input.currentHeadSha) else facts.target
        val request =
          delegatedReviewRequest(
            run.resolvedAgent.resolvedAgentId,
            run.request.repoRoot,
            delegatedTarget,
            facts.input,
          ).copy(
            timeout = launch.skillRunRequest.timeout,
            reviewRunId = facts.reviewRunId,
            reviewSessionId = run.request.reviewInvocation?.reviewSessionId,
            prelaunchExpansions = run.request.reviewInvocation?.prelaunchExpansions.orEmpty(),
            baselineUntrackedPolicy =
              run.request.reviewInvocation?.baselineUntrackedPolicy
                ?: ParallelCodeReviewRequest.baselineUntrackedPolicy(emptyList(), emptyList()),
            specPath = reviewSpecPath(run),
            selectedAgentAddonsSection = AgentAddonPromptFormatter.format(run.request.agentAddonSelection),
            reportContract = ParallelCodeReviewReportContract.STANDALONE_REPORT_ONLY,
            laneProgressIdleTimeout =
              launch.skillRunRequest.progressIdleTimeout
                ?: READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES.minutes,
            modelOverride = run.launchAssignment?.launch?.effectiveModel,
            directiveSuffix =
              if (run.launchAssignment?.profile == PhaseModelProfile.OPUS_5_5) {
                DelegatedStandaloneReviewOpus55Strategy.DIRECTIVE
              } else {
                ""
              },
          )
        when (val outcome = delegatedRunner.run(request)) {
          is ParallelCodeReviewRunOutcome.PlanningFailed -> {
            failure = outcome.failure.message
            UnsupportedAgentRunLaunch(
              SupportedAgent.fromWire(run.resolvedAgent.resolvedAgentId),
              outcome.failure.message,
            )
          }
          is ParallelCodeReviewRunOutcome.Reviewed -> {
            result = outcome.result
            val text = outcome.result.output
            AgentRunLaunchFacts(
              SupportedAgent.fromWire(run.resolvedAgent.resolvedAgentId),
              AgentRunTermination.Exited(0),
              text,
              "",
              text.encodeToByteArray().size.toLong(),
              "",
            )
          }
        }
      }
    val launched = runner.run(phaseInput, binding.launchState, session)
    val text = result?.rawOutput?.ifBlank { launched.stdout.text } ?: launched.stdout.text
    return StandaloneLaunch(
      text,
      result,
      failure ?: launchFailureReason(launched),
      launched.stdout.truncated || result?.reportTruncated == true,
    )
  }

  private fun launchFailureReason(output: PhaseStepOutput): String? =
    when {
      output.launchFailure != null -> output.launchFailure.reason
      output.termination !is AgentRunTermination.Exited -> "review execution did not exit normally"
      output.termination.code != 0 ->
        "review exited with status ${output.termination.code}"
      output.stdout.truncated -> "review output was truncated"
      else -> null
    }

  private fun failedResult(
    agentId: String,
    reason: String,
  ) = ParallelCodeReviewResult(
    ParallelReviewMergeResult(emptyList(), ""),
    ParallelReviewLaneStatus(agentId, false, reason),
  )

  private fun block(
    binding: PhaseReviewStepBinding,
    iteration: Int,
    reason: String,
  ): PhaseOutcome {
    binding.blockReviewStep(
      iteration,
      reason,
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    )
    return PhaseOutcome.blocked(reason)
  }

  override fun stepHooks(stepId: String): PhaseStepHooks = PhaseStepHooks.None

  override fun resumeRules(stepId: String): PhaseResumeRules = PhaseResumeRules.None

  override fun currentExecution(
    stepId: String,
    context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
  ): IdeStatusCurrentPhaseExecution? = null

  override fun verdictRule(
    stepId: String,
    diagnostics: RuntimeDiagnostics,
  ): FeatureTaskRuntimeStepVerdictRule? = null
}

private data class StandaloneLaunch(
  val rawOutput: String,
  val result: ParallelCodeReviewResult?,
  val failureReason: String?,
  val truncated: Boolean,
)

private data class StandaloneReviewLaunchInput(
  val target: ReviewTarget,
  val input: GoalSubtaskReviewInput,
  val reviewRunId: String,
  val directive: String,
)

private const val MAX_FAILURE_DIAGNOSTICS = 5
private const val MAX_FAILURE_DIAGNOSTIC_CHARS = 200
private const val MAX_REPORT_SUMMARY_CHARS = 2000
