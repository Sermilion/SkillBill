package skillbill.engine.featuretask.slot.goalassessment

import skillbill.engine.featuretask.slot.PhaseBindingFamily
import skillbill.engine.featuretask.slot.PhaseStepDescriptor
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class GoalAssessmentStrategy : PhaseStrategy() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT to
        PhaseStepPolicy(
          mutating = false,
          singleAgentSession = false,
          readOnlyIdle = true,
          fileMutating = false,
          generationScoped = false,
        ),
    )

  override val slot: PhaseSlot = PhaseSlot.GOAL_ASSESSMENT
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun directiveFor(stepId: String): String {
    policies.policyOf(stepId)
    return DIRECTIVE
  }

  override fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> = emptySet()

  override fun stepDescriptor(stepId: String): PhaseStepDescriptor<*> {
    policies.policyOf(stepId)
    return PhaseStepDescriptor(PhaseBindingFamily.Agent, PhaseStepHooks.None) { run, state ->
      runAgentStep(run, state)
    }
  }

  companion object {
    const val ID = "goal-assessment"

    private const val DIRECTIVE: String =
      "Assess the finished goal as a read-only reviewer. Do not modify repository files, commit, or push. " +
        "The operator instructions carry the parent spec, every subtask spec, the declared interaction " +
        "scenarios and transitional regressions, and the commit range each subtask produced. They are the " +
        "only evidence about intent. Judge each criterion against its spec in the code at the head commit, " +
        "the checked-out feature branch head, not at the commit where a subtask landed; use a subtask's " +
        "commit range only to locate what it changed, because a later subtask can break or repair an earlier " +
        "one. Nothing else tells you what was reviewed, audited, validated, or accepted before you. " +
        "Report only a defect you can state as a concrete failure scenario: an input or state, the code path " +
        "that mishandles it, and the wrong result. Skip style, preference, and speculation. Answer with " +
        "plain lines in the grammar the role focus names, one record per line, and nothing else. " +
        "Findings: `Finding: <Blocker|Major|Minor> <path>:<line> scenario <failure scenario>`, or " +
        "`Finding: none` when you found no defect. " +
        "Tests that guard nothing: `Test value: <test path>:<line> guards <path>:<line> reason <why the test " +
        "would still pass if the guarded behavior broke>`. " +
        "Declared scenarios: `Scenario <IS-NNN>: <holds|broken> evidence <path:line citations>`. " +
        "Declared regressions: `Regression <TR-NNN>: <restored|not_restored> evidence <path:line citations>`. " +
        "Verification: `Verify <GA-NNN>: <confirmed|refuted> evidence <path:line citations>`, or " +
        "`Verify: none` when no candidate was listed. Cite file and line for every claim."
  }
}
