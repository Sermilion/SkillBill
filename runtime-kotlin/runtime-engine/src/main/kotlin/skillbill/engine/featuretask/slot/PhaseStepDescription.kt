package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.model.core.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.phase.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.requireAcceptedOutput

internal data class PhaseStepDescription(
  val step: String,
  val prompt: PhaseStepPromptSource,
  val policy: PhaseStepPolicy,
  val decoder: PhaseOutputDecoder,
)

/** Decodes the text a step settled with into the accepted phase output the output gate stores. */
internal fun interface PhaseOutputDecoder {
  /** Decodes [text] settled by [stepName] with [validator], raising the schema error on invalid output. */
  fun decode(
    validator: FeatureTaskRuntimePhaseOutputValidator,
    text: String,
    stepName: String,
  ): AcceptedFeatureTaskRuntimePhaseOutput
}

internal val phaseEnvelopeDecoder =
  PhaseOutputDecoder { validator, text, stepName ->
    validator.validatePhaseOutput(text, sourceLabel = stepName).requireAcceptedOutput(stepName)
  }
