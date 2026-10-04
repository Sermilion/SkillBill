package skillbill.engine.operation.verify

import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelCodeReviewRunOutcome
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.slot.PhaseStepSession
import skillbill.engine.featuretask.slot.codereview.InlineReviewEnvelope
import skillbill.engine.featuretask.slot.codereview.InlineReviewResultDecoder
import skillbill.engine.featuretask.slot.codereview.delegatedReviewRequest
import skillbill.engine.operation.core.OperationContext
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationStepResult
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict

/** The multi-agent review the delegated mode runs; production wires `ParallelCodeReviewRunner.run`. */
fun interface VerifyDelegatedReviewer {
  fun review(request: ParallelCodeReviewRequest): ParallelCodeReviewRunOutcome
}

internal enum class VerifyReviewMode(val wireValue: String) {
  INLINE("inline"),
  DELEGATED("delegated"),
  ;

  companion object {
    fun fromWire(value: String?): VerifyReviewMode? =
      if (value == null) INLINE else entries.firstOrNull { it.wireValue == value }
  }
}

internal data class VerifyCodeReview(
  val verdict: FeatureTaskRuntimeVerdict,
  val findings: List<ParallelReviewMergedFinding>,
)

internal class VerifyCodeReviewStep(
  private val delegatedReviewer: VerifyDelegatedReviewer,
) {
  fun run(
    context: OperationContext,
    mode: VerifyReviewMode,
    baseRevision: String,
    headRevision: String,
    priorValues: Map<String, String>,
  ): VerifyCodeReviewOutcome {
    val target = ReviewTarget.Scoped(ParallelReviewScope.BRANCH, baseRevision, headRevision)
    val directive = VerifyPromptSections.codeReviewDirective(baseRevision, headRevision, target)
    val agentId = context.invokedAgentId.orEmpty()
    var delegated: ParallelCodeReviewRunOutcome? = null
    val session =
      when (mode) {
        VerifyReviewMode.INLINE -> null
        VerifyReviewMode.DELEGATED -> {
          val input = GoalSubtaskReviewInput(baseRevision, headRevision, trackedDelta = "", ownedUntrackedPatches = "")
          delegatedSession(context, agentId, target, input) { outcome -> delegated = outcome }
        }
      }
    val stepName = VerifyPromptSections.CODE_REVIEW_STEP
    val step = context.steps.runReadOnly(context, stepName, directive, priorValues, session)
    (delegated as? ParallelCodeReviewRunOutcome.PlanningFailed)?.let { planning ->
      return VerifyCodeReviewOutcome.Failed(planning.failure.message)
    }
    val settled =
      when (step) {
        is OperationStepResult.Failed -> return VerifyCodeReviewOutcome.Failed(step.reason)
        is OperationStepResult.Refused -> return VerifyCodeReviewOutcome.Refused(step.refusal)
        is OperationStepResult.Settled -> step
      }
    return reviewOutcome(agentId, (delegated as? ParallelCodeReviewRunOutcome.Reviewed)?.result, settled)
  }

  private fun reviewOutcome(
    agentId: String,
    delegated: ParallelCodeReviewResult?,
    settled: OperationStepResult.Settled,
  ): VerifyCodeReviewOutcome {
    val reviewed =
      delegated
        ?: settled.output?.let { output -> InlineReviewResultDecoder.decode(agentId, output) }
        ?: return VerifyCodeReviewOutcome.Failed("code review produced no runner output")
    if (!reviewed.lane1.success) {
      return VerifyCodeReviewOutcome.Failed(reviewed.lane1.failureReason ?: "code review lane failed")
    }
    val findings = reviewed.mergeResult.findings
    val verdict =
      if (findings.any { it.severity in BLOCKING_SEVERITIES }) {
        FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
      } else {
        InlineReviewEnvelope.extractReviewVerdict(reviewed.output)
      }
    return VerifyCodeReviewOutcome.Reviewed(VerifyCodeReview(verdict, findings))
  }

  private fun delegatedSession(
    context: OperationContext,
    agentId: String,
    target: ReviewTarget,
    input: GoalSubtaskReviewInput,
    onOutcome: (ParallelCodeReviewRunOutcome) -> Unit,
  ): PhaseStepSession =
    PhaseStepSession { launch ->
      val request = delegatedReviewRequest(agentId, context.repoRoot, target, input)
      val outcome = delegatedReviewer.review(request.copy(timeout = launch.skillRunRequest.timeout))
      onOutcome(outcome)
      when (outcome) {
        is ParallelCodeReviewRunOutcome.PlanningFailed ->
          UnsupportedAgentRunLaunch(SupportedAgent.fromWire(agentId), outcome.failure.message)
        is ParallelCodeReviewRunOutcome.Reviewed -> reviewedLaunchFacts(agentId, outcome.result)
      }
    }

  private fun reviewedLaunchFacts(
    agentId: String,
    result: ParallelCodeReviewResult,
  ): AgentRunLaunchFacts {
    val stdout =
      if (result.lane1.success) {
        result.mergeResult.formattedOutput.ifBlank { "Review completed." }
      } else {
        ""
      }
    return AgentRunLaunchFacts(
      agent = SupportedAgent.fromWire(agentId),
      termination = AgentRunTermination.Exited(if (result.lane1.success) 0 else 1),
      stdout = stdout,
      stderr = result.lane1.failureReason.orEmpty(),
      stdoutByteSize = stdout.encodeToByteArray().size.toLong(),
      stdoutSha256 = "",
    )
  }

  private companion object {
    val BLOCKING_SEVERITIES = setOf(ParallelReviewSeverity.BLOCKER, ParallelReviewSeverity.MAJOR)
  }
}

internal sealed interface VerifyCodeReviewOutcome {
  data class Reviewed(val review: VerifyCodeReview) : VerifyCodeReviewOutcome

  data class Failed(val reason: String) : VerifyCodeReviewOutcome

  data class Refused(val refusal: OperationOutcome.Blocked) : VerifyCodeReviewOutcome
}
