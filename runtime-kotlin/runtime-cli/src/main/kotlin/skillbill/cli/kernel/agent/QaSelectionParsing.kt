package skillbill.cli.kernel.agent

import com.github.ajalt.clikt.core.UsageError
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.error.core.SkillBillRuntimeException
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection

internal fun resolveRequestedQaSelection(
  cliValues: List<String>,
  environment: Map<String, String>,
): FeatureTaskRuntimeQaSelection? {
  val tokens = FeatureTaskRuntimeGoalContinuationLaunchTokens
  val fromEnv =
    environment[tokens.QA_SELECTION_ENV]?.takeIf(String::isNotBlank)?.let { raw ->
      parseQaSelection(tokens.QA_SELECTION_ENV, raw)
    }
  val fromCli =
    when (cliValues.size) {
      0 -> null
      1 -> parseQaSelection(tokens.QA_SELECTION_FLAG, cliValues.single())
      else -> {
        val raw = cliValues.joinToString(", ")
        val message =
          if (cliValues.distinct().size == 1) {
            "Duplicate ${tokens.QA_SELECTION_FLAG} '$raw' is not allowed; supply it at most once."
          } else {
            "Conflicting ${tokens.QA_SELECTION_FLAG} values '$raw' are not allowed; supply exactly one selection."
          }
        throw UsageError(message)
      }
    }
  if (fromCli != null && fromEnv != null && fromCli != fromEnv) {
    throw UsageError("Conflicting ${tokens.QA_SELECTION_FLAG} and ${tokens.QA_SELECTION_ENV} values are not allowed.")
  }
  return fromCli ?: fromEnv
}

private fun parseQaSelection(
  source: String,
  raw: String,
): FeatureTaskRuntimeQaSelection =
  try {
    FeatureTaskRuntimeQaSelection.fromWire(raw)
  } catch (_: SkillBillRuntimeException) {
    throw UsageError(
      "Unknown $source value '$raw'. Allowed: ${FeatureTaskRuntimeQaSelection.entries.joinToString { it.wireValue }}.",
    )
  }
