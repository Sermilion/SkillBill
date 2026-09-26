package skillbill.engine.featuretask.review.core

import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

/**
 * The rule a step's strategy supplies for the verdict its output settles to. The shared output verification applies
 * it in place of the default wire-verdict reading, so the step's verdict words live with the step.
 */
fun interface FeatureTaskRuntimeStepVerdictRule {
  /** The verdict of [outputObject], whose parsed wire verdict is [wireVerdict]. */
  fun verdictFor(
    wireVerdict: FeatureTaskRuntimeVerdict?,
    outputObject: FeatureTaskRuntimeWorkflowArtifactMap?,
  ): FeatureTaskRuntimeVerdict
}
