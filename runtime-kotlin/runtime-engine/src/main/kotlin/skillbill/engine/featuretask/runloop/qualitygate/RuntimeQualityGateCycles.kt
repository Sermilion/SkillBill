package skillbill.engine.featuretask.runloop.qualitygate

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.attempt.PhaseQualityGateCycleContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepCall
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeValidationGateCoordinator
import skillbill.engine.featuretask.validation.ValidationGateResolver
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator

@Inject
class RuntimeQualityGateCycles(
  private val buildCoordinator: FeatureTaskRuntimeBuildGateCoordinator,
  private val receiptValidator: FeatureTaskRuntimeWireArtifactValidator,
  private val validationCoordinator: FeatureTaskRuntimeValidationGateCoordinator,
  private val resolver: ValidationGateResolver,
) {
  internal fun runPackGate(
    context: PhaseQualityGateCycleContext,
    call: PhaseStepCall,
    run: PhaseRun,
    commandFamily: ValidationGateCommandFamily,
  ): PhaseOutcome = PackBuildGateCycle(context, call, commandFamily, buildCoordinator, receiptValidator).run(run)

  internal fun runAgentValidation(
    context: PhaseQualityGateCycleContext,
    call: PhaseStepCall,
    run: PhaseRun,
  ): PhaseOutcome = AgentValidateGateCycle(context, call, validationCoordinator).run(run)

  internal fun resolve(
    request: FeatureTaskRuntimeRunFacts,
    changedPaths: List<String>,
  ): ValidationGateResolution {
    val admitted = request.admittedExecution
    if (admitted == null) return resolver.resolve(changedPaths)
    val inputs = admitted.effectiveInputs
    return inputs.declaration?.let { ValidationGateResolution.Declared(requireNotNull(inputs.packSlug), it) }
      ?: ValidationGateResolution.Absent(inputs.packSlug)
  }
}
