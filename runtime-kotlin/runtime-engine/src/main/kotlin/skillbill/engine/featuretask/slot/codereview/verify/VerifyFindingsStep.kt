package skillbill.engine.featuretask.slot.codereview.verify

import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseAcceptedOutputRecordingHooks
import skillbill.engine.featuretask.slot.PhaseCompletionRejectionHooks
import skillbill.engine.featuretask.slot.PhaseFailureContinuationOutput
import skillbill.engine.featuretask.slot.PhaseLaunchPromptHooks
import skillbill.engine.featuretask.slot.PhaseStepHookContextKind
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.PhaseUpstreamFailureContinuationHooks
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.state.PhaseVerifyFindingsStepBinding
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy

internal class VerifyFindingsStep :
  PhaseStepHooks<PhaseVerifyFindingsStepBinding>,
  PhaseUpstreamFailureContinuationHooks,
  PhaseLaunchPromptHooks<PhaseVerifyFindingsStepBinding>,
  PhaseCompletionRejectionHooks<PhaseVerifyFindingsStepBinding>,
  PhaseAcceptedOutputRecordingHooks<PhaseVerifyFindingsStepBinding> {
  override val contextKind = PhaseStepHookContextKind.FINDING_VERIFICATION
  val policy =
    PhaseStepPolicy(
      mutating = false,
      singleAgentSession = false,
      readOnlyIdle = true,
      fileMutating = true,
      generationScoped = false,
    )

  override fun failureContinuationOutput(
    upstream: List<FeatureTaskRuntimePhaseOutput>,
  ): PhaseFailureContinuationOutput =
    PhaseFailureContinuationOutput(
      VerifyFindingsFailureOutput.carryReviewFindings(upstream),
      "value_expected=verified_findings value_used=original_review_findings",
    )

  override fun launchPromptSupplement(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
    state: PhaseVerifyFindingsStepBinding,
  ): String = VerifyFindingsEvidence.launchSections(run, context, state)

  override fun checkValidatedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseVerifyFindingsStepBinding,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck = VerifyFindingsEvidence.boundaryBodyDelivery(run, context, state, outputMap)

  override fun completionRejection(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseVerifyFindingsStepBinding,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? = VerifyFindingsEvidence.completionRejection(run, context, state, outputMap)

  override fun interpretedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseVerifyFindingsStepBinding,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput = VerifyFindingsEvidence.interpretedOutput(run, context, state, output)

  override fun recordAcceptedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseVerifyFindingsStepBinding,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ) = VerifyFindingsEvidence.recordRejectedFindings(run, context, state, outputMap)
}
