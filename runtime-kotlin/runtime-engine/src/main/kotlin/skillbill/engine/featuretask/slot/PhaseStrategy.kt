package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.work.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.core.PhaseSlot
import skillbill.workflow.taskruntime.model.core.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.persistence.task.runtime.run.FeatureTaskRuntimeRunInvariantPromptField

abstract class PhaseStrategy {
  abstract val slot: PhaseSlot

  abstract val strategyId: String

  abstract val steps: List<String>

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
    context: FeatureTaskRuntimeRunLoopContext,
    state: PhaseRunState,
  ): PhaseOutcome

  internal open fun stepHooks(stepId: String): PhaseStepHooks = PhaseStepHooks.None

  internal open fun verdictRule(
    stepId: String,
    diagnostics: RuntimeDiagnostics,
  ): FeatureTaskRuntimeStepVerdictRule? = null

  internal open val loopRules: PhaseLoopRules?
    get() = null
}

abstract class PhaseStrategyStatusProjection : PhaseStrategy() {
  internal abstract fun currentExecution(
    stepId: String,
    context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
  ): IdeStatusCurrentPhaseExecution?
}

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
