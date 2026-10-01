package skillbill.engine.featuretask.slot.qualitygate.packvalidation

import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseQualityGateOperation
import skillbill.engine.featuretask.slot.PhaseReportedGate
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategyStatusProjection
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.stepCall
import skillbill.engine.featuretask.slot.qualitygate.QUALITY_GATE_STEP_POLICY
import skillbill.engine.featuretask.slot.qualitygate.VALIDATE_VALUE_CONTENT
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateResumeRules
import skillbill.engine.featuretask.slot.qualitygate.buildGateFindingsDirective
import skillbill.engine.featuretask.slot.qualitygate.gateCurrentExecution
import skillbill.engine.featuretask.slot.qualitygate.gateRepairNoOutputSchemaDirective
import skillbill.engine.featuretask.slot.qualitygate.packValidationGateTriagePhaseTask
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStepHooks
import skillbill.engine.featuretask.slot.qualitygate.runtimeOwnedPackValidationPhaseTask
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseQualityGateStepBinding
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class PackValidationStrategy : PhaseStrategyStatusProjection() {
  private val policies: Map<String, PhaseStepPolicy> =
    mapOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to QUALITY_GATE_STEP_POLICY)

  override val qualityGateOperation = PhaseQualityGateOperation.PackGate(ValidationGateCommandFamily.VALIDATION)

  override val slot: PhaseSlot = PhaseSlot.QUALITY_GATE
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun directiveFor(stepId: String): String {
    policies.policyOf(stepId)
    return runtimeOwnedPackValidationPhaseTask(null)
  }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections {
    policies.policyOf(stepId)
    val triage = inputs.validationGateTriage
    val repair = inputs.validationGateRepair || triage
    return PhaseStepPromptSections(
      taskDirective =
        if (triage) {
          packValidationGateTriagePhaseTask(inputs.packCollectAllCommand)
        } else {
          runtimeOwnedPackValidationPhaseTask(inputs.packCollectAllCommand)
        },
      runsValidationGate = true,
      stepContext =
        buildGateFindingsDirective(
          inputs.validationGateFindings,
          inputs.validationGateTriagePlan,
          gateLabel = "validation",
          commandLabel = null,
        ),
      valueContent = VALIDATE_VALUE_CONTENT,
      settles = false,
      outputContract = gateRepairNoOutputSchemaDirective(stepId, triage).takeIf { repair },
    )
  }

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome =
    (
      state as? PhaseQualityGateStepBinding
        ?: error("Quality gate requires its accepted execution binding.")
    ).runSelectedQualityGate(run, stepCall(run, state))

  override fun stepHooks(stepId: String): PhaseStepHooks =
    if (stepId in policies) PackValidationStepHooks else PhaseStepHooks.None

  override fun resumeRules(stepId: String) =
    if (stepId in policies) AgentValidateResumeRules else super.resumeRules(stepId)

  override fun currentExecution(
    stepId: String,
    context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
  ): IdeStatusCurrentPhaseExecution? = gateCurrentExecution(stepId, context)

  override fun reportedGate(stepId: String): PhaseReportedGate? =
    PhaseReportedGate.VALIDATION.takeIf { stepId in policies }

  companion object {
    const val ID = "pack-validation"
  }
}

internal object PackValidationStepHooks : PhaseStepHooks by PackBuildStepHooks {
  override val carriesPackBuildCommand: Boolean = false
  override val carriesPackValidationCommand: Boolean = true
}
