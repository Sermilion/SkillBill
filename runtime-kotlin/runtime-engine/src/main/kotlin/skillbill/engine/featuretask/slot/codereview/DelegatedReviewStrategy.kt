package skillbill.engine.featuretask.slot.codereview

import skillbill.agentaddon.model.AgentAddonPromptFormatter
import skillbill.application.review.model.ParallelCodeReviewPlanningFailure
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelCodeReviewRunOutcome
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunner
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseExecutionBindingKind
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepSession
import skillbill.engine.featuretask.slot.PhaseStrategyStatusProjection
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.PhaseReviewStepBinding
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.model.READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

class DelegatedReviewStrategy(
  runner: PhaseRunner,
  reviewRunner: ParallelCodeReviewRunner,
) : PhaseStrategyStatusProjection() {
  private val codeReview = CodeReviewSlot(runner, DelegatedReviewPass(reviewRunner))

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
    const val ID = "delegated"
  }
}

internal class DelegatedReviewPass(
  private val reviewRunner: ParallelCodeReviewRunner,
) : CodeReviewPass {
  override val policy =
    PhaseStepPolicy(
      mutating = false,
      singleAgentSession = false,
      readOnlyIdle = true,
      fileMutating = false,
      generationScoped = true,
    )

  override val directive: String = DelegatedReviewDirective.text

  override val recordsLaneTelemetry: Boolean = true

  override fun executedTier(resolved: CodeReviewExecutionMode): CodeReviewExecutionMode =
    CodeReviewExecutionMode.DELEGATED

  override fun review(
    run: PhaseRun,
    input: GoalSubtaskReviewInput,
    reviewRunId: String,
    runner: PhaseRunner,
    state: PhaseReviewStepBinding,
  ): ParallelCodeReviewRunOutcome {
    val agentId = run.resolvedAgent.resolvedAgentId
    var reviewed: ParallelCodeReviewResult? = null
    var planningFailure: ParallelCodeReviewPlanningFailure? = null
    val session =
      PhaseStepSession { launch ->
        when (val outcome = reviewRunner.run(request(run, input, reviewRunId, state, launch.skillRunRequest))) {
          is ParallelCodeReviewRunOutcome.PlanningFailed -> {
            planningFailure = outcome.failure
            UnsupportedAgentRunLaunch(SupportedAgent.fromWire(agentId), outcome.failure.message)
          }
          is ParallelCodeReviewRunOutcome.Reviewed -> {
            reviewed = outcome.result
            val stdout = outcome.result.mergeResult.formattedOutput
            AgentRunLaunchFacts(
              agent = SupportedAgent.fromWire(agentId),
              termination = AgentRunTermination.Exited(0),
              stdout = stdout,
              stderr = "",
              stdoutByteSize = stdout.encodeToByteArray().size.toLong(),
              stdoutSha256 = "",
            )
          }
        }
      }
    val output = runner.run(reviewStepInput(run, directive), state.launchState, session)
    planningFailure?.let { return ParallelCodeReviewRunOutcome.PlanningFailed(it) }
    return ParallelCodeReviewRunOutcome.Reviewed(
      reviewed ?: ParallelCodeReviewResult(
        mergeResult = ParallelReviewMergeResult(findings = emptyList(), formattedOutput = ""),
        lane1 =
          ParallelReviewLaneStatus(
            agentId = agentId,
            success = false,
            failureReason = output.launchFailure?.reason ?: "delegated review session did not run",
          ),
      ),
    )
  }

  private fun request(
    run: PhaseRun,
    input: GoalSubtaskReviewInput,
    reviewRunId: String,
    state: PhaseAcceptedStepExecution,
    launch: SkillRunRequest,
  ): ParallelCodeReviewRequest {
    val branch = state.resolvedBranch()
    val baselineUntracked = branch?.baselineUntrackedPaths.orEmpty().filter(String::isNotBlank).distinct().sorted()
    val invocation = run.request.reviewInvocation
    return delegatedReviewRequest(run.resolvedAgent.resolvedAgentId, run.request.repoRoot, run.reviewTarget, input)
      .copy(
        timeout = launch.timeout,
        reviewRunId = reviewRunId,
        reviewSessionId = invocation?.reviewSessionId,
        activityWorkflowId = run.request.workflowId.takeIf(String::isNotBlank),
        activityParentWorkflowId = run.request.goalContinuation?.parentWorkflowId?.takeIf(String::isNotBlank),
        prelaunchExpansions = invocation?.prelaunchExpansions.orEmpty(),
        baselineUntrackedPolicy =
          invocation?.baselineUntrackedPolicy
            ?: ParallelCodeReviewRequest.baselineUntrackedPolicy(emptyList(), baselineUntracked),
        ownedPathspec = branch?.workflowOwnedPaths.orEmpty().filter(String::isNotBlank).distinct(),
        specPath = reviewSpecPath(run),
        selectedAgentAddonsSection = AgentAddonPromptFormatter.format(run.request.agentAddonSelection),
        laneProgressIdleTimeout = launch.progressIdleTimeout ?: READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES.minutes,
      )
  }
}

internal object DelegatedReviewDirective {
  private const val RUNTIME_DIRECTIVE: String =
    "Review the change through the delegated specialist review: the runtime launches the dominant pack's " +
      "specialist lanes in parallel and merges their findings. Do not edit files. Emit the merged findings and " +
      "a verdict of approved or changes_requested. Criterion-gap detection remains exclusive to the audit " +
      "phase. Do not run `./gradlew check`, the pack collect-all gate, or `skill-bill phase validation`; " +
      "validate owns those."

  val text: String by lazy { RUNTIME_DIRECTIVE + "\n\n" + CodeReviewDirectives.review.trimEnd() }
}

internal fun delegatedReviewRequest(
  agentId: String,
  repoRoot: Path,
  target: ReviewTarget,
  input: GoalSubtaskReviewInput,
): ParallelCodeReviewRequest {
  val (scope, base, head) =
    when (target) {
      ReviewTarget.LastCommit ->
        Triple(ParallelReviewScope.WORKTREE_FROM_BASE, input.reviewBaseSha, input.currentHeadSha)
      ReviewTarget.Uncommitted -> Triple(ParallelReviewScope.UNCOMMITTED, null, null)
      is ReviewTarget.Commit -> Triple(ParallelReviewScope.BRANCH, "${target.sha}^", target.sha)
      is ReviewTarget.Scoped -> Triple(target.scope, target.baseRevision, target.headRevision)
    }
  return ParallelCodeReviewRequest(
    agent1Id = agentId,
    scope = scope,
    repoRoot = repoRoot,
    timeout = null,
    codeReviewMode = CodeReviewExecutionMode.DELEGATED,
    resolvedTier = CodeReviewExecutionMode.DELEGATED,
    suppliedDiffPath = (target as? ReviewTarget.Scoped)?.suppliedDiffPath,
    baseRevision = base,
    headRevision = head,
  )
}
