package skillbill.engine.featuretask.slot.codereview.verify

import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptEnvironment
import skillbill.engine.featuretask.slot.state.PhaseStepState
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy

internal class VerifyFindingsStep : PhaseStepHooks {
  val policy =
    PhaseStepPolicy(
      mutating = false,
      relaunchOnInvalidOutput = true,
      singleAgentSession = false,
      readOnlyIdle = true,
      fileMutating = true,
      generationScoped = false,
    )

  override fun launchPromptSupplement(
    run: PhaseRun,
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
  ): String = VerifyFindingsEvidence.launchSections(run, context, state)

  override fun retainSchemaRejectedOutput(
    state: PhaseStepState,
    outputText: String,
  ) = VerifyFindingsEvidence.retainCheckpoint(state, outputText)

  override fun checkValidatedOutput(
    run: PhaseRun,
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck = VerifyFindingsEvidence.boundaryBodyDelivery(run, context, state, outputMap)

  override fun completionRejection(
    run: PhaseRun,
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? = VerifyFindingsEvidence.completionRejection(run, context, state, outputMap)

  override fun recordAcceptedOutput(
    run: PhaseRun,
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ) = VerifyFindingsEvidence.recordRejectedFindings(run, context, state, outputMap)
}
