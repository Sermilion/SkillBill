package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.work.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.persistence.task.runtime.run.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy

abstract class PhaseStrategy {
  abstract val slot: PhaseSlot

  abstract val strategyId: String

  open val semanticRevision: Int = 1

  open fun stepPolicyIdentity(stepId: String): String =
    policyFor(stepId).semanticIdentity(strategyId, semanticRevision, stepId)

  open fun resumeInterpretationIdentity(stepId: String): String = "$strategyId/$semanticRevision:$stepId"

  internal open val qualityGateOperation: PhaseQualityGateOperation? = null

  internal open fun acceptsAttemptStrategy(attemptStrategyId: String): Boolean = attemptStrategyId == strategyId

  abstract val steps: List<String>

  open val optionalSteps: Set<String> = emptySet()

  abstract val entryStep: String

  abstract val runner: PhaseRunner

  abstract fun policyFor(stepId: String): PhaseStepPolicy

  abstract fun directiveFor(stepId: String): String

  open fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections = PhaseStepPromptSections(taskDirective = directiveFor(stepId))

  open fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> =
    FeatureTaskRuntimeRunInvariantPromptAllowlist.ACCEPTANCE_CONTRACT_PHASES

  internal abstract fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome

  internal open fun stepHooks(stepId: String): PhaseStepHooks = PhaseStepHooks.None

  internal open fun verdictRule(
    stepId: String,
    diagnostics: RuntimeDiagnostics,
  ): FeatureTaskRuntimeStepVerdictRule? = null

  internal open fun resumeRules(stepId: String): PhaseResumeRules = PhaseResumeRules.None

  internal open val loopRules: PhaseLoopRules?
    get() = null
}

abstract class PhaseStrategyStatusProjection : PhaseStrategy() {
  internal abstract fun currentExecution(
    stepId: String,
    context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
  ): IdeStatusCurrentPhaseExecution?

  internal open fun reportedGate(stepId: String): PhaseReportedGate? = null
}

internal enum class PhaseReportedGate { BUILD, VALIDATION }

internal fun jsonValueContent(
  innerJsonExample: String,
  notes: String,
): String =
  "Carry this JSON object as the value text; the runtime does not validate its shape and the next phase reads\n" +
    "it as structured prose:\n" +
    "```json\n" +
    innerJsonExample +
    "```\n" +
    notes

internal sealed interface PhaseQualityGateOperation {
  data class PackGate(
    val commandFamily: ValidationGateCommandFamily,
  ) : PhaseQualityGateOperation

  data object AgentValidation : PhaseQualityGateOperation
}
