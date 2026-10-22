package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseStepRecords
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PhaseStrategyHistoricalAliasTest {
  private val lookup = testPhaseStrategies({ error("unused") }, NoopWorkflowGitOperations)
  private val facts =
    PhaseStrategySelectionFacts(
      SkeletonDefinition.GOAL_CHILD,
      setOf(FeatureTaskRuntimeQualityGateSelection.BUILD, CodeReviewExecutionMode.INLINE),
    )
  private val canonicalPlan = lookup.executionPlan(facts)

  @Test
  fun `a plan recorded under a historical alias matches the current selection and dispatches to the canonical owner`() {
    val recorded = recordedUnder(ALIAS_ID, recomputeIdentities = true)

    assertTrue(lookup.matchesRecordedSelection(recorded, SkeletonDefinition.GOAL_CHILD))
    assertSame(lookup.strategyFor(PHASE_BUILD, canonicalPlan), lookup.selectedOwnerOf(PHASE_BUILD, recorded))
    assertEquals(ALIAS_ID, recorded.selectedStrategies.single { it.slot == PhaseSlot.QUALITY_GATE }.strategyId)
  }

  @Test
  fun `an alias still refuses policy and revision drift`() {
    val staleIdentities = recordedUnder(ALIAS_ID, recomputeIdentities = false)
    assertFailsWith<InvalidPhaseStrategyCompositionError> { lookup.selectedOwnerOf(PHASE_BUILD, staleIdentities) }

    val canonicalRevision = canonicalPlan.selectedStrategies.single { it.slot == PhaseSlot.QUALITY_GATE }
    val drifted =
      recordedUnder(ALIAS_ID, recomputeIdentities = true, semanticRevision = canonicalRevision.semanticRevision + 1)
    assertFailsWith<InvalidPhaseStrategyCompositionError> { lookup.selectedOwnerOf(PHASE_BUILD, drifted) }
  }

  @Test
  fun `an alias recorded for a slot that never owned it is not part of the selection`() {
    val foreign = recordedUnder("inline-opus-5-5", recomputeIdentities = false)

    assertTrue(!lookup.registry.contains(PhaseSlot.QUALITY_GATE, "inline-opus-5-5"))
    assertFailsWith<RuntimeException> { lookup.selectedOwnerOf(PHASE_BUILD, foreign) }
  }

  private fun recordedUnder(
    aliasId: String,
    recomputeIdentities: Boolean,
    semanticRevision: Int? = null,
  ): ResolvedPhaseExecutionPlan {
    val canonical = canonicalPlan.selectedStrategies.single { it.slot == PhaseSlot.QUALITY_GATE }
    val strategy = lookup.registry.strategy(PhaseSlot.QUALITY_GATE, PackBuildStrategy.ID)
    val policies =
      if (recomputeIdentities) {
        canonicalPlan.stepPolicyIdentities +
          canonical.steps.associateWith { strategy.stepPolicyIdentity(it, aliasId) }
      } else {
        canonicalPlan.stepPolicyIdentities
      }
    val resume =
      if (recomputeIdentities) {
        canonicalPlan.resumeInterpretationIdentities +
          canonical.steps.associateWith { strategy.resumeInterpretationIdentity(it, aliasId) }
      } else {
        canonicalPlan.resumeInterpretationIdentities
      }
    return ResolvedPhaseExecutionPlan(
      definitionId = canonicalPlan.definitionId,
      definitionSemanticRevision = canonicalPlan.definitionSemanticRevision,
      selectedStrategies =
        canonicalPlan.selectedStrategies.map {
          if (it.slot == PhaseSlot.QUALITY_GATE) {
            it.copy(strategyId = aliasId, semanticRevision = semanticRevision ?: it.semanticRevision)
          } else {
            it
          }
        },
      reviewSelection = canonicalPlan.reviewSelection,
      qualityGateSelection = canonicalPlan.qualityGateSelection,
      traversal = canonicalPlan.traversal,
      stepRecords = ResolvedPhaseStepRecords(policies, resume, canonicalPlan.stepLaunchAssignments),
    )
  }

  private companion object {
    const val ALIAS_ID = "pack-build-opus-5-5"
  }
}
