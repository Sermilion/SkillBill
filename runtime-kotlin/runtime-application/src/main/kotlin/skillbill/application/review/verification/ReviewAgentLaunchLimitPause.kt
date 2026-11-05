package skillbill.application.review.verification

import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitDetector
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause

internal const val CLAIM_VERIFICATION_PHASE_LABEL: String = "claim verification"
internal const val SPEC_ADJUDICATION_PHASE_LABEL: String = "spec adjudication"
internal const val INTEGRATION_PASS_PHASE_LABEL: String = "integration pass"

internal fun reviewLaunchProviderLimitPauseReason(
  facts: AgentRunLaunchFacts,
  phaseLabel: String,
): String? {
  val exited = facts.termination as? AgentRunTermination.Exited ?: return null
  if (exited.code == 0) return null
  val signal = FeatureTaskRuntimeProviderLimitDetector.detect(facts.stderr, facts.stdout) ?: return null
  return FeatureTaskRuntimeProviderLimitPause.reason(
    phaseLabel = phaseLabel,
    agentId = facts.agent.wireValue,
    resumePhaseId = phaseLabel,
    signal = signal,
  )
}
