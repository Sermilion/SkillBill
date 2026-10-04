package skillbill.engine.featuretask.slot.codereview

import skillbill.agentaddon.model.AgentAddonPromptFormatter
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelCodeReviewRunOutcome
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.application.review.service.RuntimeOwnedReviewMode
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.directives.projectAuthoringDisciplineDirective
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseExecutionBindingKind
import skillbill.engine.featuretask.slot.PhaseLaunchFailureKind
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.PhaseStrategyStatusProjection
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.PhaseReviewStepBinding
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.parallel.ParallelReviewFindingParser
import skillbill.review.parallel.ParallelReviewMerger
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import java.nio.file.Path

class InlineReviewStrategy(
  runner: PhaseRunner,
) : PhaseStrategyStatusProjection() {
  private val codeReview = CodeReviewSlot(runner, InlineReviewPass)

  override val slot: PhaseSlot = PhaseSlot.CODE_REVIEW
  override val strategyId: String = ID
  override val steps: List<String> = codeReview.steps
  override val entryStep: String = codeReview.entryStep

  override fun executionBindingKind(stepId: String): PhaseExecutionBindingKind = codeReview.executionBindingKind(stepId)

  override fun policyFor(stepId: String): PhaseStepPolicy = codeReview.policyFor(stepId)

  override fun directiveFor(stepId: String): String = codeReview.directiveFor(stepId)

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections = codeReview.promptSections(stepId, inputs)

  override fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> =
    codeReview.briefingInvariantFields(stepId, super.briefingInvariantFields(stepId))

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome = codeReview.runStep(this, run, state)

  override fun stepHooks(stepId: String): PhaseStepHooks = codeReview.stepHooks(stepId)

  override fun verdictRule(
    stepId: String,
    diagnostics: RuntimeDiagnostics,
  ): FeatureTaskRuntimeStepVerdictRule? = codeReview.verdictRule(stepId)

  override fun resumeRules(stepId: String): PhaseResumeRules = codeReview.resumeRules(stepId)

  override val loopRules: PhaseLoopRules = codeReview.loopRules

  override fun currentExecution(
    stepId: String,
    context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
  ): IdeStatusCurrentPhaseExecution? = codeReview.currentExecution(stepId, context)

  companion object {
    const val ID = "inline"
  }
}

internal object InlineReviewPass : CodeReviewPass {
  override val policy =
    PhaseStepPolicy(
      mutating = false,
      singleAgentSession = false,
      readOnlyIdle = false,
      fileMutating = true,
      generationScoped = true,
    )

  override val directive: String = InlineReviewPromptSections.REVIEW_DIRECTIVE

  override val recordsLaneTelemetry: Boolean = false

  override fun executedTier(resolved: CodeReviewExecutionMode): CodeReviewExecutionMode =
    RuntimeOwnedReviewMode.execute(resolved)

  override fun review(
    run: PhaseRun,
    input: GoalSubtaskReviewInput,
    reviewRunId: String,
    runner: PhaseRunner,
    state: PhaseReviewStepBinding,
  ): ParallelCodeReviewRunOutcome {
    val directive =
      InlineReviewDirective.compose(
        target = run.reviewTarget,
        baseRevision = input.reviewBaseSha,
        headRevision = input.currentHeadSha,
        specPath = reviewSpecPath(run),
        agentAddonsSection = AgentAddonPromptFormatter.format(run.request.agentAddonSelection),
      )
    val output = runner.run(reviewStepInput(run, directive), state.launchState)
    return ParallelCodeReviewRunOutcome.Reviewed(
      InlineReviewResultDecoder.decode(run.resolvedAgent.resolvedAgentId, output)
        .copy(reviewSessionId = run.request.reviewInvocation?.reviewSessionId),
    )
  }
}

object InlineReviewDirective {
  fun compose(
    target: ReviewTarget,
    baseRevision: String,
    headRevision: String,
    specPath: Path?,
    agentAddonsSection: String,
  ): String {
    val prompt =
      buildString {
        target.openingLines(baseRevision, headRevision).forEach(::appendLine)
        if (target !is ReviewTarget.Scoped) {
          appendLine(
            "Do not use `origin/main...HEAD`, a merge base, the full feature branch, or a pre-baked diff blob.",
          )
        }
        appendLine(
          "Do not launch `skill-bill phase review`, delegated review subagents, or an isolated review process.",
        )
        appendLine("Fix every Blocker and Major finding in this same session before you emit.")
        appendLine("You may edit files. Leave Minor and Nit unfixed unless the edit is local and obvious.")
        appendLine("Do not commit, amend, reset, or stage changes; the runtime owns the review checkpoint.")
        appendLine(
          "Criterion-gap detection remains exclusive to audit. Do not report unsatisfied acceptance criteria.",
        )
        appendLine(
          "Do not run `./gradlew check`, the pack collect-all gate, or `skill-bill phase validation`; " +
            "validate owns those.",
        )
        appendLine()
        appendLine(projectAuthoringDisciplineDirective())
        appendLine()
        specPath?.let { path -> appendLine("Subtask spec path: `$path`.") }
        appendLine()
        append(CodeReviewDirectives.review)
        appendLine()
        append(CodeReviewDirectives.inlineReview)
        appendLine()
        appendLine(
          "Emit the bounded authoring evidence record as leading prose before the findings register and final " +
            "verdict. Do not format evidence lines as `[F-NNN]` register lines or `verdict:` lines. The runtime " +
            "retains only the first 2,000 characters as the summary, so state any evidence limitation explicitly.",
        )
        appendLine("After fixes, emit remaining findings in this register shape, one per line:")
        appendLine("- [F-001] Blocker | High | path/File.kt:12 | remaining defect after your edits")
        appendLine("End with exactly one line: `verdict: approved` or `verdict: changes_requested`.")
        appendLine("Use `changes_requested` when any Blocker or Major remains; otherwise `approved`.")
        appendLine("An explicit empty findings list plus `verdict: approved` means no remaining Blocker or Major.")
      }
    if (agentAddonsSection.isEmpty()) return prompt
    return prompt.trimEnd() + "\n\n" + agentAddonsSection
  }
}

internal object InlineReviewResultDecoder {
  fun decode(
    agentId: String,
    output: PhaseStepOutput,
  ): ParallelCodeReviewResult {
    val unsupported = output.launchFailure?.takeIf { it.kind == PhaseLaunchFailureKind.UNSUPPORTED_AGENT }
    return unsupported?.let { failedResult(agentId, it.cause) } ?: launchedResult(agentId, output)
  }

  private fun launchedResult(
    agentId: String,
    output: PhaseStepOutput,
  ): ParallelCodeReviewResult {
    launchFailureReason(output)?.let { reason -> return failedResult(agentId, reason) }
    val stdout = output.stdout.text
    val parsed = ParallelReviewFindingParser.parse(stdout)
    val merged =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult(agentId = agentId, findings = parsed.findings),
        ParallelReviewLaneResult(agentId = agentId, findings = emptyList()),
      )
    return ParallelCodeReviewResult(
      mergeResult = merged.copy(formattedOutput = stdout.ifBlank { "Review completed." }),
      lane1 =
        ParallelReviewLaneStatus(
          agentId = agentId,
          success = true,
          droppedCandidateDiagnostic = droppedCandidateDiagnostic(parsed.rejections.size, parsed.candidateCount),
          reviewDisposition =
            if (stdout.isBlank()) {
              ReviewLaneReviewDisposition.INCOMPLETE
            } else {
              ReviewLaneReviewDisposition.COMPLETE
            },
        ),
    )
  }

  private fun failedResult(
    agentId: String,
    reason: String,
  ) = ParallelCodeReviewResult(
    mergeResult = ParallelReviewMergeResult(findings = emptyList(), formattedOutput = ""),
    lane1 = ParallelReviewLaneStatus(agentId = agentId, success = false, failureReason = reason),
  )

  private fun launchFailureReason(output: PhaseStepOutput): String? =
    when (val termination = output.termination) {
      null -> "agent process failed to spawn"
      AgentRunTermination.TimedOut -> "agent timed out"
      AgentRunTermination.SpawnFailed -> "agent process failed to spawn"
      AgentRunTermination.Interrupted -> "agent was interrupted"
      is AgentRunTermination.Exited ->
        when {
          termination.code != 0 -> "agent exited with status ${termination.code}"
          output.stdout.truncated -> "agent output exceeded the retention cap before completion"
          else -> null
        }
    }

  private fun droppedCandidateDiagnostic(
    rejected: Int,
    candidateCount: Int,
  ): String? =
    if (rejected == 0) {
      null
    } else {
      "dropped $rejected of $candidateCount [F-XXX] candidate line(s)"
    }
}
