package skillbill.workflow.taskruntime.validation

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeProviderLimitSignal

object FeatureTaskRuntimeProviderLimitPause {
  const val MARKER: String = "agent provider refused the request at a usage limit"

  fun reason(
    phaseLabel: String,
    agentId: String,
    resumePhaseId: String,
    signal: FeatureTaskRuntimeProviderLimitSignal,
  ): String {
    val reset = signal.resetHint?.let { " Access resets $it." }.orEmpty()
    return "$phaseLabel agent '$agentId' stopped because the $MARKER.$reset " +
      "The phase produced no output and consumed no repair attempt; the run is paused and " +
      "resumes at '$resumePhaseId'. Provider said: ${signal.evidence}"
  }

  fun isProviderLimitPauseReason(reason: String): Boolean = reason.contains(MARKER)
}
