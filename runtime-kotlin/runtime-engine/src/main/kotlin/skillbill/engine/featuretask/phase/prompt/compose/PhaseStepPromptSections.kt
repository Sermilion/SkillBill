package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing

/**
 * Supplies the step-specific prompt sections a step's launch prompt composes with. The running strategy owns the
 * source; the shared composer only places the sections it returns.
 */
public fun interface PhaseStepPromptSource {
  /** The sections of the step for the composed [inputs]. */
  public fun sections(inputs: FeatureTaskRuntimePhasePromptComposeInputs): PhaseStepPromptSections
}

public data class PhaseStepPromptSections(
  public val taskDirective: String,
  internal val ceremonyLine: String? = null,
  internal val runsValidationGate: Boolean = false,
  internal val runsBuildGate: Boolean = false,
  internal val authoringDiscipline: String = "",
  internal val scopeBoundary: String = "",
  internal val testValueDiscipline: Boolean = false,
  public val stepContext: String = "",
  public val continuation: String = "",
  public val valueContent: String = "",
  public val settles: Boolean = true,
  public val outputContract: String? = null,
  public val retryFocus: String = "",
  internal val briefingRewrite: ((FeatureTaskRuntimePhaseLaunchBriefing) -> FeatureTaskRuntimePhaseLaunchBriefing)? =
    null,
)
