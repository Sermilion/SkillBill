package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairStepRoles
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.core.MonitorRepairStepRoles
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan

public abstract class PhaseStrategy {
  public abstract val slot: PhaseSlot

  public abstract val strategyId: String

  internal open val semanticRevision: Int = 1

  public abstract val steps: List<String>

  internal open val optionalSteps: Set<String> = emptySet()

  internal abstract val entryStep: String

  internal abstract fun policyFor(stepId: String): PhaseStepPolicy

  internal abstract fun directiveFor(stepId: String): String

  internal open fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections = PhaseStepPromptSections(taskDirective = directiveFor(stepId))

  internal open fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> =
    FeatureTaskRuntimeRunInvariantPromptAllowlist.ACCEPTANCE_CONTRACT_PHASES

  public abstract fun stepDescriptor(stepId: String): PhaseStepDescriptor<*>

  internal fun stepHooks(stepId: String): PhaseStepHooks<*> = stepDescriptor(stepId).hooks

  internal fun stepPolicyIdentity(
    stepId: String,
    recordedStrategyId: String = strategyId,
  ): String = policyFor(stepId).semanticIdentity(recordedStrategyId, semanticRevision, stepId)

  internal fun resumeInterpretationIdentity(
    stepId: String,
    recordedStrategyId: String = strategyId,
  ): String = "$recordedStrategyId/$semanticRevision:$stepId"
}

/** A strategy that owns backward-edge loop decisions and the forward checkpoints of its steps. */
internal interface PhaseLoopCapability {
  val loopRules: PhaseLoopRules
}

/** A strategy that interprets resumed history of its steps instead of using the historical interpreter. */
internal interface PhaseResumeCapability {
  fun resumeRules(stepId: String): PhaseResumeRules
}

/** A strategy that supplies the verdict rule judging the output of a step. */
internal interface PhaseVerdictCapability {
  /** The verdict rule of [stepId], built over [diagnostics] of the call, or null when the step has no verdict. */
  fun verdictRule(
    stepId: String,
    diagnostics: RuntimeDiagnostics,
  ): FeatureTaskRuntimeStepVerdictRule?
}

/** A strategy that projects the status of its steps for IDE and status consumers. */
internal interface PhaseStatusCapability {
  fun currentExecution(
    stepId: String,
    context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
  ): IdeStatusCurrentPhaseExecution?
}

/** A strategy whose steps own acceptance-criterion repair accounting. */
internal interface AcceptanceRepairCapability {
  val acceptanceRepairSteps: AcceptanceCriterionRepairStepRoles
}

/** A strategy whose steps own monitor repair accounting. */
internal interface MonitorRepairCapability {
  val monitorRepairSteps: MonitorRepairStepRoles
}

/** A strategy that prepares a run before the shared dispatch executes it. */
internal interface PhasePreparedRunCapability {
  /** Runs [execute] over the prepared form of [run] and returns its outcome. */
  fun withPreparedRun(
    run: PhaseRun,
    execute: (PhaseRun) -> PhaseOutcome,
  ): PhaseOutcome
}

/** A strategy that maps a recorded execution plan it no longer selects onto the plan it supports. */
internal interface PhaseRecordedPlanMappingCapability {
  /** Maps [recorded] onto [current] for [owner], the registered strategy this capability was discovered on. */
  fun mapRecordedExecutionPlan(
    owner: PhaseStrategy,
    recorded: ResolvedPhaseExecutionPlan,
    current: ResolvedPhaseExecutionPlan,
  ): PhaseExecutionPlanMapping?
}

public enum class PhaseReportedGate { BUILD, VALIDATION }

public sealed interface PhaseQualityGateOperation {
  public data class PackGate(
    val commandFamily: ValidationGateCommandFamily,
  ) : PhaseQualityGateOperation

  public data object AgentValidation : PhaseQualityGateOperation
}

internal data class PhaseExecutionPlanMapping(
  val previous: ResolvedPhaseExecutionPlan,
  val supported: ResolvedPhaseExecutionPlan,
)
