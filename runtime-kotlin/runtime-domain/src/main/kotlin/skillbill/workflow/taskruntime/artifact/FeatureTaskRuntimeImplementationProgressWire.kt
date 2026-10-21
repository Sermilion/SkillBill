package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidFeatureTaskRuntimeImplementationProgressSchema
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import java.math.BigDecimal
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimeImplementationProgressPayloadKeys as Keys

fun FeatureTaskRuntimeImplementationProgress.asImplementationProgressWireMap(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(
    linkedMapOf(
      Keys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_CONTRACT_VERSION,
      Keys.CRITERION_IDS to criterionIds,
      Keys.CURRENT_INDEX to currentIndex,
      Keys.WORKER_COUNT to workerCount,
      Keys.LAUNCH_COUNT to launchCount,
      Keys.CLOSED_IDS to closedIds,
    ),
  )

fun FeatureTaskRuntimeWorkflowArtifactMap.toImplementationProgress(
  sourceLabel: String,
): FeatureTaskRuntimeImplementationProgress {
  fun invalid(reason: String): Nothing =
    throw invalidFeatureTaskRuntimeImplementationProgressSchema(sourceLabel, reason)
  if (!isObject) invalid("progress must be an object.")
  val unexpected = keys - PROGRESS_FIELDS
  if (unexpected.isNotEmpty()) invalid("progress carries unsupported fields ${unexpected.sorted()}.")
  if (this[Keys.CONTRACT_VERSION] != FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_CONTRACT_VERSION) {
    invalid("progress has no supported contract version.")
  }
  val criterionIds = ids(Keys.CRITERION_IDS) ?: invalid("${Keys.CRITERION_IDS} must be an array of strings.")
  val closedIds = ids(Keys.CLOSED_IDS) ?: invalid("${Keys.CLOSED_IDS} must be an array of strings.")
  val currentIndex = exactInt(Keys.CURRENT_INDEX) ?: invalid("${Keys.CURRENT_INDEX} must be an integer.")
  val workerCount = exactInt(Keys.WORKER_COUNT) ?: invalid("${Keys.WORKER_COUNT} must be an integer.")
  val launchCount = exactInt(Keys.LAUNCH_COUNT) ?: invalid("${Keys.LAUNCH_COUNT} must be an integer.")
  FeatureTaskRuntimeImplementationProgress.violation(criterionIds, currentIndex, workerCount, launchCount)
    ?.let(::invalid)
  if (closedIds != criterionIds.take(currentIndex)) {
    invalid("${Keys.CLOSED_IDS} must equal the ${Keys.CRITERION_IDS} prefix before ${Keys.CURRENT_INDEX}.")
  }
  return FeatureTaskRuntimeImplementationProgress(criterionIds, currentIndex, workerCount, launchCount)
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.ids(key: String): List<String>? =
  (this[key] as? List<*>)?.map { it as? String ?: return null }

private fun FeatureTaskRuntimeWorkflowArtifactMap.exactInt(key: String): Int? {
  val number = this[key] as? Number ?: return null
  return try {
    BigDecimal(number.toString()).intValueExact()
  } catch (_: NumberFormatException) {
    null
  } catch (_: ArithmeticException) {
    null
  }
}

private val PROGRESS_FIELDS =
  setOf(
    Keys.CONTRACT_VERSION,
    Keys.CRITERION_IDS,
    Keys.CURRENT_INDEX,
    Keys.WORKER_COUNT,
    Keys.LAUNCH_COUNT,
    Keys.CLOSED_IDS,
  )
