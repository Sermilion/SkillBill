package skillbill.engine.featuretask.slot.codereview.verify

import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStepHookContextKind
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseStepBinding
import skillbill.engine.featuretask.slot.state.PhaseVerifyFindingsStepBinding
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy

internal class VerifyFindingsStep : PhaseStepHooks {
  override val contextKind = PhaseStepHookContextKind.FINDING_VERIFICATION
  val policy =
    PhaseStepPolicy(
      mutating = false,
      singleAgentSession = false,
      readOnlyIdle = true,
      fileMutating = true,
      generationScoped = false,
    )

  override fun launchPromptSupplement(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
    state: PhaseStepBinding,
  ): String = VerifyFindingsEvidence.launchSections(run, context, state.asVerifyFindingsBinding())

  override fun checkValidatedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseAcceptedStepExecution,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck =
    VerifyFindingsEvidence.boundaryBodyDelivery(run, context, state.asVerifyFindingsBinding(), outputMap)

  override fun completionRejection(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseAcceptedStepExecution,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? = VerifyFindingsEvidence.completionRejection(run, context, state.asVerifyFindingsBinding(), outputMap)

  override fun interpretedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseAcceptedStepExecution,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput =
    VerifyFindingsEvidence.interpretedOutput(run, context, state.asVerifyFindingsBinding(), output)

  override fun recordAcceptedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseStepBinding,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ) = VerifyFindingsEvidence.recordRejectedFindings(run, context, state.asVerifyFindingsBinding(), outputMap)

  private fun PhaseStepBinding.asVerifyFindingsBinding(): PhaseVerifyFindingsStepBinding =
    this as PhaseVerifyFindingsStepBinding
}
