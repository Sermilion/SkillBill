package skillbill.engine.featuretask.slot.preplan

import skillbill.engine.directive.directiveResource
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.directives.ceremonyScalingOf
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class AgentPreplanStrategy : PhaseStrategy() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to
        PhaseStepPolicy(
          mutating = false,
          singleAgentSession = false,
          readOnlyIdle = false,
          fileMutating = false,
          generationScoped = false,
        ),
    )

  override val slot: PhaseSlot = PhaseSlot.PREPLAN
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun directiveFor(stepId: String): String {
    policies.policyOf(stepId)
    return DIRECTIVE
  }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = directiveFor(stepId),
      ceremonyLine =
        "Apply ${ceremonyScalingOf(inputs.briefing).preplanCeremony.promptLabel}. Keep the gate real: identify " +
          "concrete scope, affected boundaries, risks, and unknowns at the requested depth.",
      stepContext = if (inputs.suppressDecomposition) "" else featureSpecIntake,
    )

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome = runAgentStep(run, state)

  companion object {
    const val ID = "agent-preplan"

    private const val FEATURE_SPEC_INTAKE =
      "/skillbill/engine/featuretask/slot/preplan/feature-spec-intake.md"

    private val featureSpecIntake: String by lazy { directiveResource(FEATURE_SPEC_INTAKE).trimEnd() }

    private const val DIRECTIVE: String =
      "Produce the scaled pre-planning digest for the resolved feature size, as prose. Do not modify repository " +
        "files during this phase. This is the feature's only discovery: the plan phase plans every subtask " +
        "from this digest and never reads the repository. Write what the plan phase needs: the boundaries the " +
        "change touches, the patterns and decisions that apply, concrete risks, and rollout and validation " +
        "considerations. The digest must carry every fact the plan needs to specify this task: exact paths, " +
        "symbols, and signatures of the code this task changes, the tests that exercise that code, and the " +
        "patterns those files already follow. Decide that scope before you look anything up. A lookup is in " +
        "scope only when the plan cannot write this task without that fact. Every lookup must answer one " +
        "question this task still has open. If you cannot name that question, do not search or read. A lookup " +
        "that cannot change what the digest says about this task is waste, including a lookup made just in " +
        "case. Read only files this task directly touches. Do not inventory a type hierarchy, a sibling " +
        "feature, a shared client, a platform wrapper, or a dependency coordinate unless the plan needs that " +
        "fact to specify this task. A wrapper that only forwards a call you have already read is not a new " +
        "question. When the intake names a tracker issue and does not already carry its comments, fetch the " +
        "issue and its comments together: request every tool schema you need in one round, then make every " +
        "tracker call in the next, alongside your first code lookups. Do not fetch the tracker again after " +
        "that. Each tool round resends this whole conversation, so issue every lookup you can name " +
        "now in one round, and start a new round only for a lookup that depends on what the last round " +
        "returned. Batching never adds a lookup: each lookup in a round must still answer an open question. " +
        "Open each file once and read every part this task needs in that read. Read a file under 300 lines " +
        "whole. Do not search for a file you have already opened, and do not open it again. Scope each search " +
        "to the module or directory you have already located. Do not search the repository root for a common " +
        "word. When a file is out of scope, stop. Do not open it to confirm. Do " +
        "not read unrelated modules, docs, or tests, and do not read dependency jars, " +
        "Gradle caches, generated sources, or library internals. When this briefing includes a boundary " +
        "heading list, that list is headings only, in walk " +
        "order. Walk it from the start and judge each heading from its heading text alone. Stop when the three " +
        "headings you just read are all irrelevant to this task. Then read the body of each heading you judged " +
        "relevant: open its source file, find that heading, and read only that section. The heading list is " +
        "complete. Do not grep, search, or list a history or decisions file to rediscover headings. Do not " +
        "search those files for symbols: a content match returns the body. The only read of a history or " +
        "decisions file is that section read. Do not read the body of a heading you judged irrelevant. " +
        "Leave open any decision this task's own code cannot answer, including dependency, " +
        "account, and environment questions, each with the facts that bear on it and the option you recommend. " +
        "Name a cited heading by its heading_id exactly as the boundary catalog spells it. Do not forward " +
        "progress diagnostics or a generic summary."
  }
}
