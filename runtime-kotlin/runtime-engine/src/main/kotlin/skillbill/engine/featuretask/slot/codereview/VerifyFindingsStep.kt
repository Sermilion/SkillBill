package skillbill.engine.featuretask.slot.codereview

import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseRunState
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.PhaseStepPolicy

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
    context: FeatureTaskRuntimeRunLoopContext,
    state: PhaseRunState,
  ): String = VerifyFindingsEvidence.launchSections(run, context, state)

  override fun retainSchemaRejectedOutput(
    state: PhaseRunState,
    outputText: String,
  ) = VerifyFindingsEvidence.retainCheckpoint(state, outputText)

  override fun checkValidatedOutput(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
    state: PhaseRunState,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck = VerifyFindingsEvidence.boundaryBodyDelivery(run, context, state, outputMap)

  override fun completionRejection(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
    state: PhaseRunState,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? = VerifyFindingsEvidence.completionRejection(run, context, state, outputMap)

  override fun recordAcceptedOutput(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
    state: PhaseRunState,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ) = VerifyFindingsEvidence.recordRejectedFindings(run, context, state, outputMap)
}
