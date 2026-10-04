package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing

/**
 * Supplies the step-specific prompt sections a step's launch prompt composes with. The running strategy owns the
 * source; the shared composer only places the sections it returns.
 */
fun interface PhaseStepPromptSource {
  /** The sections of the step for the composed [inputs]. */
  fun sections(inputs: FeatureTaskRuntimePhasePromptComposeInputs): PhaseStepPromptSections
}

data class PhaseStepPromptSections(
  val taskDirective: String,
  val ceremonyLine: String? = null,
  val runsValidationGate: Boolean = false,
  val runsBuildGate: Boolean = false,
  val authoringDiscipline: String = "",
  val scopeBoundary: String = "",
  val testValueDiscipline: Boolean = false,
  val stepContext: String = "",
  val continuation: String = "",
  val valueContent: String = "",
  val settles: Boolean = true,
  val outputContract: String? = null,
  val retryFocus: String = "",
  val briefingRewrite: ((FeatureTaskRuntimePhaseLaunchBriefing) -> FeatureTaskRuntimePhaseLaunchBriefing)? = null,
)
