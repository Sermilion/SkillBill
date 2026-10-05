package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PROMPT_COMPOSER_ISSUE_KEY
import skillbill.engine.featuretask.phase.prompt.compose.promptComposerBriefingFor
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.audit.opus.AcceptanceAuditOpus55Strategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.skeleton.EffectiveLaunchModel
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.LaunchModelProvenance
import skillbill.workflow.taskruntime.model.skeleton.LaunchProviderNamespace
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfile
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfileClassifier
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.skeleton.StepLaunchAssignment
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PhaseModelProfileSelectionTest {
  @Test
  fun `the classifier accepts only documented exact Opus 5_5 identities`() {
    val cases =
      listOf(
        Triple(
          LaunchProviderNamespace.ANTHROPIC_API,
          PhaseModelProfileClassifier.ANTHROPIC_OPUS_55,
          PhaseModelProfile.OPUS_5_5,
        ),
        Triple(
          LaunchProviderNamespace.GOOGLE,
          PhaseModelProfileClassifier.ANTHROPIC_OPUS_55,
          PhaseModelProfile.OPUS_5_5,
        ),
        Triple(
          LaunchProviderNamespace.CLAUDE_PLATFORM_AWS,
          PhaseModelProfileClassifier.ANTHROPIC_OPUS_55,
          PhaseModelProfile.OPUS_5_5,
        ),
        Triple(
          LaunchProviderNamespace.BEDROCK,
          PhaseModelProfileClassifier.BEDROCK_OPUS_55,
          PhaseModelProfile.OPUS_5_5,
        ),
        Triple(LaunchProviderNamespace.ANTHROPIC_API, "opus", PhaseModelProfile.CANONICAL),
        Triple(LaunchProviderNamespace.ANTHROPIC_API, "claude-opus-4-6", PhaseModelProfile.CANONICAL),
        Triple(LaunchProviderNamespace.ANTHROPIC_API, "claude-opus-5", PhaseModelProfile.CANONICAL),
        Triple(LaunchProviderNamespace.ANTHROPIC_API, "best", PhaseModelProfile.CANONICAL),
        Triple(
          LaunchProviderNamespace.OPAQUE,
          PhaseModelProfileClassifier.ANTHROPIC_OPUS_55,
          PhaseModelProfile.CANONICAL,
        ),
        Triple(
          LaunchProviderNamespace.BEDROCK,
          PhaseModelProfileClassifier.ANTHROPIC_OPUS_55,
          PhaseModelProfile.CANONICAL,
        ),
        Triple(LaunchProviderNamespace.ANTHROPIC_API, null, PhaseModelProfile.CANONICAL),
      )
    cases.forEach { (namespace, model, expected) ->
      assertEquals(expected, PhaseModelProfileClassifier.classify(namespace, model), "$namespace/$model")
    }
  }

  @Test
  fun `an unselected opus validate step does not specialize a goal-child build plan`() {
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
  fun `a mixed audit selects the opus variant and specializes only the qualifying repair step`() {
    val lookup = testPhaseStrategies({ error("unused") }, NoopWorkflowGitOperations)
    val facts =
      PhaseStrategySelectionFacts(
        SkeletonDefinition.STANDALONE,
        setOf(CodeReviewExecutionMode.INLINE),
        mapOf(
          PHASE_AUDIT to canonicalAssignment(PHASE_AUDIT),
          PHASE_AUDIT_IMPLEMENT_FIX to opusAssignment(PHASE_AUDIT_IMPLEMENT_FIX),
        ),
      )
    val plan = lookup.executionPlan(facts)
    assertEquals(
      AcceptanceAuditOpus55Strategy.ID,
      plan.selectedStrategies.single { it.slot.wireValue == "audit" }.strategyId,
    )
    val strategy = lookup.strategyFor(PHASE_AUDIT, plan)
    val auditInputs =
      FeatureTaskRuntimePhasePromptComposeInputs(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(PHASE_AUDIT),
        stepProfile = PhaseModelProfile.CANONICAL,
      )
    val repairInputs = auditInputs.copy(stepProfile = PhaseModelProfile.OPUS_5_5)
    assertEquals(
      AcceptanceAuditStrategy().promptSections(PHASE_AUDIT, auditInputs).taskDirective,
      strategy.promptSections(PHASE_AUDIT, auditInputs).taskDirective,
    )
    assertTrue(
      strategy.promptSections(PHASE_AUDIT_IMPLEMENT_FIX, repairInputs).taskDirective.contains("Opus"),
    )
  }

  @Test
  fun `mutating caller assignments after resolution cannot reuse another plan`() {
    val lookup = testPhaseStrategies({ error("unused") }, NoopWorkflowGitOperations)
    val mutating = mutableMapOf(PHASE_AUDIT to canonicalAssignment(PHASE_AUDIT))
    val facts = setOf(CodeReviewExecutionMode.INLINE)
    val first =
      lookup.executionPlan(PhaseStrategySelectionFacts(SkeletonDefinition.STANDALONE, facts, mutating))
    mutating[PHASE_AUDIT] = opusAssignment(PHASE_AUDIT)
    val second =
      lookup.executionPlan(PhaseStrategySelectionFacts(SkeletonDefinition.STANDALONE, facts, mutating))
    assertNotEquals(
      first.selectedStrategies.single { it.slot.wireValue == "audit" }.strategyId,
      second.selectedStrategies.single { it.slot.wireValue == "audit" }.strategyId,
    )
    assertEquals(
      AcceptanceAuditStrategy.ID,
      first.selectedStrategies.single { it.slot.wireValue == "audit" }.strategyId,
    )
    assertEquals(
      AcceptanceAuditOpus55Strategy.ID,
      second.selectedStrategies.single { it.slot.wireValue == "audit" }.strategyId,
    )
  }
}

private fun opusAssignment(stepId: String): StepLaunchAssignment =
  StepLaunchAssignment(
    stepId,
    "claude",
    EffectiveLaunchModel(
      requestedModel = PhaseModelProfileClassifier.ANTHROPIC_OPUS_55,
      requestedEffort = "high",
      effectiveModel = PhaseModelProfileClassifier.ANTHROPIC_OPUS_55,
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
