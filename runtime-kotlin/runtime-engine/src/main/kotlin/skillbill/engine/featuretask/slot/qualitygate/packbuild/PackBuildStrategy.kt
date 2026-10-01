package skillbill.engine.featuretask.slot.qualitygate.packbuild

import skillbill.engine.directive.directiveResource
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
import skillbill.engine.featuretask.slot.qualitygate.BUILD_VALUE_CONTENT
import skillbill.engine.featuretask.slot.qualitygate.QUALITY_GATE_STEP_POLICY
import skillbill.engine.featuretask.slot.qualitygate.buildGateFindingsDirective
import skillbill.engine.featuretask.slot.qualitygate.buildGateTriagePhaseTask
import skillbill.engine.featuretask.slot.qualitygate.gateCurrentExecution
import skillbill.engine.featuretask.slot.qualitygate.gateRepairNoOutputSchemaDirective
import skillbill.engine.featuretask.slot.qualitygate.runtimeOwnedBuildPhaseTask
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseQualityGateStepBinding
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class PackBuildStrategy : PhaseStrategyStatusProjection() {
  private val policies: Map<String, PhaseStepPolicy> =
    mapOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD to QUALITY_GATE_STEP_POLICY)

  override val qualityGateOperation = PhaseQualityGateOperation.PackGate(ValidationGateCommandFamily.BUILD)

  override val slot: PhaseSlot = PhaseSlot.QUALITY_GATE
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD

  override fun resumeRules(stepId: String): PhaseResumeRules =
    if (stepId in policies) BuildReceiptResumeRules else super.resumeRules(stepId)

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun directiveFor(stepId: String): String {
    policies.policyOf(stepId)
    return runtimeOwnedBuildPhaseTask(null)
  }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections {
    policies.policyOf(stepId)
    val repairTurn = inputs.validationGateRepair || inputs.validationGateTriage
    return PhaseStepPromptSections(
      taskDirective =
        if (inputs.validationGateTriage) {
          buildGateTriagePhaseTask(inputs.packBuildCommand)
        } else {
          runtimeOwnedBuildPhaseTask(inputs.packBuildCommand)
        },
      runsValidationGate = true,
      runsBuildGate = true,
      stepContext =
        listOfNotNull(
          directiveResource(QUALITY_CHECK_DIRECTIVE_RESOURCE)
            .trim()
            .takeIf { inputs.validationGateRepair && !inputs.validationGateTriage },
          buildGateFindingsDirective(inputs.validationGateFindings, inputs.validationGateTriagePlan),
        ).filter(String::isNotBlank).joinToString("\n\n"),
      valueContent = BUILD_VALUE_CONTENT,
      settles = false,
      outputContract = gateRepairNoOutputSchemaDirective(stepId, inputs.validationGateTriage).takeIf { repairTurn },
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
    if (stepId in policies) PackBuildStepHooks else PhaseStepHooks.None

  override fun currentExecution(
    stepId: String,
    context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
  ): IdeStatusCurrentPhaseExecution? = gateCurrentExecution(stepId, context)

  override fun reportedGate(stepId: String): PhaseReportedGate? = PhaseReportedGate.BUILD.takeIf { stepId in policies }

  companion object {
    const val ID = "pack-build"
  }
}

private const val QUALITY_CHECK_DIRECTIVE_RESOURCE =
  "/skillbill/engine/featuretask/slot/qualitygate/packbuild/quality-check-directive.md"

private object BuildReceiptResumeRules : PhaseResumeRules {
  override val requiresValidCompletedOutput = true
}
