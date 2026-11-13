package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PROMPT_COMPOSER_ISSUE_KEY
import skillbill.engine.featuretask.phase.prompt.compose.promptComposerBriefingFor
import skillbill.engine.featuretask.slot.attempt.promptSource
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.monitor.MonitorStrategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorStrategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.skeleton.EffectiveLaunchModel
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.LaunchModelProvenance
import skillbill.workflow.taskruntime.model.skeleton.LaunchProviderNamespace
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfile
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.skeleton.StepLaunchAssignment
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PhaseModelProfileSelectionTest {
  @Test
  fun `historical model profiles use the same shared audit prompt`() {
    val lookup = testPhaseStrategies({ error("unused") }, NoopWorkflowGitOperations)
    val inputs =
      FeatureTaskRuntimePhasePromptComposeInputs(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(PHASE_AUDIT),
      )
    val rendered =
      listOf(canonicalAssignment(PHASE_AUDIT), opusAssignment(PHASE_AUDIT)).map { assignment ->
        val plan =
          lookup.executionPlan(
            PhaseStrategySelectionFacts(
              SkeletonDefinition.STANDALONE,
              setOf(CodeReviewExecutionMode.INLINE),
              mapOf(PHASE_AUDIT to assignment),
            ),
          )
        lookup.strategyFor(PHASE_AUDIT, plan).promptSource(PHASE_AUDIT).sections(inputs)
      }
    assertEquals(rendered.first(), rendered.last())
    assertFalse("Opus" in rendered.last().taskDirective)
  }

  @Test
  fun `an unselected opus validate step does not change a goal-child build plan`() {
    val lookup = testPhaseStrategies({ error("unused") }, NoopWorkflowGitOperations)
    val facts =
      PhaseStrategySelectionFacts(
        SkeletonDefinition.GOAL_CHILD,
        setOf(FeatureTaskRuntimeQualityGateSelection.BUILD, CodeReviewExecutionMode.INLINE),
        mapOf(PHASE_VALIDATE to opusAssignment(PHASE_VALIDATE)),
      )
    val plan = lookup.executionPlan(facts)
    assertEquals(
      PackBuildStrategy.ID,
      plan.selectedStrategies.single { it.slot.wireValue == "quality_gate" }.strategyId,
    )
    assertFalse(PHASE_VALIDATE in plan.selectedStepIds)
  }

  @Test
  fun `an opus monitor repair selects the canonical monitor owners and receives no supplement`() {
    val lookup = testPhaseStrategies({ error("unused") }, NoopWorkflowGitOperations)

    fun monitorStrategy(definition: SkeletonDefinition): PhaseStrategy {
      val plan =
        lookup.executionPlan(
          PhaseStrategySelectionFacts(
            definition,
            emptySet(),
            mapOf(PHASE_MONITOR_FIX to opusAssignment(PHASE_MONITOR_FIX)),
          ),
        )
      return lookup.strategyFor(PHASE_MONITOR_FIX, plan)
    }

    assertEquals(StandaloneMonitorStrategy.ID, monitorStrategy(SkeletonDefinition.STANDALONE_MONITOR).strategyId)
    assertEquals(MonitorStrategy.ID, monitorStrategy(SkeletonDefinition.MONITOR).strategyId)
    assertEquals(MonitorStrategy.ID, monitorStrategy(SkeletonDefinition.PR).strategyId)
  }

  @Test
  fun `mutating caller assignments after resolution cannot reuse another plans launch assignments`() {
    val lookup = testPhaseStrategies({ error("unused") }, NoopWorkflowGitOperations)
    val mutating = mutableMapOf(PHASE_AUDIT to canonicalAssignment(PHASE_AUDIT))
    val facts = setOf(CodeReviewExecutionMode.INLINE)
    val first =
      lookup.executionPlan(PhaseStrategySelectionFacts(SkeletonDefinition.STANDALONE, facts, mutating))
    mutating[PHASE_AUDIT] = opusAssignment(PHASE_AUDIT)
    val second =
      lookup.executionPlan(PhaseStrategySelectionFacts(SkeletonDefinition.STANDALONE, facts, mutating))
    assertEquals(PhaseModelProfile.CANONICAL, first.stepLaunchAssignments.getValue(PHASE_AUDIT).profile)
    assertEquals(PhaseModelProfile.OPUS_5_5, second.stepLaunchAssignments.getValue(PHASE_AUDIT).profile)
    assertEquals(
      AcceptanceAuditStrategy.ID,
      second.selectedStrategies.single { it.slot.wireValue == "audit" }.strategyId,
    )
  }
}

private fun opusAssignment(stepId: String): StepLaunchAssignment =
  StepLaunchAssignment(
    stepId,
    "claude",
    EffectiveLaunchModel(
      requestedModel = "claude-opus-5-5",
      requestedEffort = "high",
      effectiveModel = "claude-opus-5-5",
      namespace = LaunchProviderNamespace.ANTHROPIC_API,
      provenance = LaunchModelProvenance.REQUESTED_EXACT,
      unknownReason = null,
      profile = PhaseModelProfile.OPUS_5_5,
    ),
  )

private fun canonicalAssignment(stepId: String): StepLaunchAssignment =
  StepLaunchAssignment(
    stepId,
    "claude",
    EffectiveLaunchModel(
      requestedModel = "claude-sonnet-4-5",
      requestedEffort = null,
      effectiveModel = "claude-sonnet-4-5",
      namespace = LaunchProviderNamespace.ANTHROPIC_API,
      provenance = LaunchModelProvenance.REQUESTED_EXACT,
      unknownReason = null,
      profile = PhaseModelProfile.CANONICAL,
    ),
  )
