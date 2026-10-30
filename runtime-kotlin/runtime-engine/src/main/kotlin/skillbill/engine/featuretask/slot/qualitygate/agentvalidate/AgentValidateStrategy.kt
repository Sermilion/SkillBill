package skillbill.engine.featuretask.slot.qualitygate.agentvalidate

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.slot.PhaseBindingFamily
import skillbill.engine.featuretask.slot.PhaseQualityGateOperation
import skillbill.engine.featuretask.slot.PhaseResumeCapability
import skillbill.engine.featuretask.slot.PhaseStatusCapability
import skillbill.engine.featuretask.slot.PhaseStepDescriptor
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.stepCall
import skillbill.engine.featuretask.slot.qualitygate.QUALITY_GATE_STEP_POLICY
import skillbill.engine.featuretask.slot.qualitygate.VALIDATE_PROBE_VALUE_CONTENT
import skillbill.engine.featuretask.slot.qualitygate.VALIDATE_VALUE_CONTENT
import skillbill.engine.featuretask.slot.qualitygate.gateCurrentExecution
import skillbill.engine.featuretask.slot.qualitygate.runtimeOwnedValidateAgentPhaseTask
import skillbill.engine.featuretask.slot.qualitygate.validateGateTriagePhaseTask
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class AgentValidateStrategy :
  PhaseStrategy(),
  PhaseResumeCapability by AgentValidateCapabilities,
  PhaseStatusCapability by AgentValidateCapabilities {
  private val policies: Map<String, PhaseStepPolicy> = AGENT_VALIDATE_POLICIES

  override val slot: PhaseSlot = PhaseSlot.QUALITY_GATE
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun directiveFor(stepId: String): String {
    policies.policyOf(stepId)
    return runtimeOwnedValidateAgentPhaseTask()
  }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections {
    policies.policyOf(stepId)
    val probeScope = inputs.validateProbeScope
    return PhaseStepPromptSections(
      taskDirective =
        if (inputs.validationGateTriage) {
          validateGateTriagePhaseTask()
        } else {
          runtimeOwnedValidateAgentPhaseTask(probeScope)
        },
      runsValidationGate = true,
      runsBuildGate = true,
      valueContent = if (probeScope == null) VALIDATE_VALUE_CONTENT else VALIDATE_PROBE_VALUE_CONTENT,
    )
  }

  override fun stepDescriptor(stepId: String): PhaseStepDescriptor<*> {
    policies.policyOf(stepId)
    return PhaseStepDescriptor(AGENT_VALIDATE_FAMILY, AgentValidateStepHooks) { run, state ->
      state.runSelectedQualityGate(run, stepCall(run, state))
    }
  }

  companion object {
    const val ID = "agent-validate"
  }
}

private val AGENT_VALIDATE_POLICIES: Map<String, PhaseStepPolicy> =
  mapOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to
      QUALITY_GATE_STEP_POLICY.copy(
        singleAgentSession = true,
        extendsOwnedInventory = true,
      ),
  )

private object AgentValidateCapabilities : PhaseResumeCapability, PhaseStatusCapability {
  override fun resumeRules(stepId: String): PhaseResumeRules =
    if (stepId in AGENT_VALIDATE_POLICIES) AgentValidateResumeRules else PhaseResumeRules.None

  override fun currentExecution(
    stepId: String,
    context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
  ): IdeStatusCurrentPhaseExecution? = gateCurrentExecution(stepId, context)
}

private val AGENT_VALIDATE_FAMILY = PhaseBindingFamily.QualityGate(PhaseQualityGateOperation.AgentValidation)
