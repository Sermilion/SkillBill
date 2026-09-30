package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy

internal data class PhaseStepDescription(
  val step: String,
  val prompt: PhaseStepPromptSource,
  val policy: PhaseStepPolicy,
)
