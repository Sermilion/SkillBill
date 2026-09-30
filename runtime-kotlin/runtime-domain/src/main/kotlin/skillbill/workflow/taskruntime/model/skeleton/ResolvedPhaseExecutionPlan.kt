package skillbill.workflow.taskruntime.model.skeleton

import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import java.util.Collections

enum class RuntimeReviewSelection(val wireValue: String) {
  AUTO("auto"),
  INLINE("inline"),
  DELEGATED("delegated"),
}

data class ResolvedPhaseStrategyIdentity(
  val slot: PhaseSlot,
  val strategyId: String,
  val semanticRevision: Int,
  val steps: List<String>,
  val entryStep: String,
) {
  init {
    require(strategyId.isNotBlank())
    require(semanticRevision > 0)
    require(steps.isNotEmpty() && steps.distinct().size == steps.size)
    require(entryStep in steps)
  }
}

data class ResolvedPhaseStrategyDispatch(
  val slot: PhaseSlot,
  val strategyId: String,
  val semanticRevision: Int,
) {
  init {
    require(strategyId.isNotBlank())
    require(semanticRevision > 0)
  }
}

class ResolvedPhaseExecutionPlan(
  val definitionId: String,
  val definitionSemanticRevision: Int,
  selectedStrategies: List<ResolvedPhaseStrategyIdentity>,
  val reviewSelection: RuntimeReviewSelection?,
  val qualityGateSelection: FeatureTaskRuntimeQualityGateSelection?,
  traversal: FeatureTaskRuntimeTransitionDeclaration,
  dispatchStrategyByStep: Map<String, ResolvedPhaseStrategyDispatch>,
  stepPolicyIdentities: Map<String, String>,
  resumeInterpretationIdentities: Map<String, String>,
  effectivePolicies: List<ResolvedExecutionPolicy> = emptyList(),
  val effectivePolicySettings: ResolvedFeatureTaskRuntimeExecutionSettings? = null,
) {
  val effectivePolicies: List<ResolvedExecutionPolicy> = immutableList(effectivePolicies.sortedBy { it.id })
  val selectedStrategies: List<ResolvedPhaseStrategyIdentity> =
    Collections.unmodifiableList(selectedStrategies.map { it.copy(steps = immutableList(it.steps)) })
  val selectedSlots: List<PhaseSlot> =
    Collections.unmodifiableList(this.selectedStrategies.map(ResolvedPhaseStrategyIdentity::slot))
  val selectedEntryStepIds: Set<String> =
    Collections.unmodifiableSet(this.selectedStrategies.mapTo(linkedSetOf(), ResolvedPhaseStrategyIdentity::entryStep))
  val dispatchStrategyByStep: Map<String, ResolvedPhaseStrategyDispatch> = immutableMap(dispatchStrategyByStep)
  val stepPolicyIdentities: Map<String, String> = immutableMap(stepPolicyIdentities)
  val resumeInterpretationIdentities: Map<String, String> = immutableMap(resumeInterpretationIdentities)
  val selectedStepIds: Set<String> = Collections.unmodifiableSet(this.dispatchStrategyByStep.keys.toSet())
  val unselectedStepIds: Set<String> =
    Collections.unmodifiableSet(PhaseSlot.entries.flatMap(PhaseSlot::steps).toSet() - selectedStepIds)
  val traversal: FeatureTaskRuntimeTransitionDeclaration =
    traversal.copy(
      forwardPhaseIds = immutableList(traversal.forwardPhaseIds),
      backwardEdges = immutableList(traversal.backwardEdges),
      loopOnlyPhaseIds = Collections.unmodifiableSet(traversal.loopOnlyPhaseIds.toSet()),
      entryGates = immutableList(traversal.entryGates),
      loopOnlySuccessors = immutableMap(traversal.loopOnlySuccessors),
    )

  fun withTraversal(value: FeatureTaskRuntimeTransitionDeclaration): ResolvedPhaseExecutionPlan =
    ResolvedPhaseExecutionPlan(
      definitionId = definitionId,
      definitionSemanticRevision = definitionSemanticRevision,
      selectedStrategies = selectedStrategies,
      reviewSelection = reviewSelection,
      qualityGateSelection = qualityGateSelection,
      traversal = value,
      dispatchStrategyByStep = dispatchStrategyByStep,
      stepPolicyIdentities = stepPolicyIdentities,
      resumeInterpretationIdentities = resumeInterpretationIdentities,
      effectivePolicies = effectivePolicies,
      effectivePolicySettings = effectivePolicySettings,
    )

  fun withEffectivePolicies(value: List<ResolvedExecutionPolicy>): ResolvedPhaseExecutionPlan =
    ResolvedPhaseExecutionPlan(
      definitionId, definitionSemanticRevision, selectedStrategies, reviewSelection, qualityGateSelection,
      traversal, dispatchStrategyByStep, stepPolicyIdentities, resumeInterpretationIdentities, value,
      effectivePolicySettings,
    )

  init {
    require(definitionId.isNotBlank())
    require(this.effectivePolicies.map { it.id }.distinct().size == this.effectivePolicies.size)
    require(definitionSemanticRevision > 0)
    require(this.selectedSlots.distinct().size == this.selectedSlots.size)
    require(this.selectedStrategies.all { it.strategyId.isNotBlank() })
    require(this.dispatchStrategyByStep.isNotEmpty())
    validateResolvedTraversal(selectedStepIds, selectedEntryStepIds, this.traversal)
    val selectedSteps = this.selectedStrategies.flatMap(ResolvedPhaseStrategyIdentity::steps)
    require(selectedSteps.size == selectedSteps.toSet().size)
    require(selectedSteps.toSet() == this.dispatchStrategyByStep.keys)
    require(
      this.selectedStrategies.all { identity ->
        identity.entryStep in identity.steps &&
          identity.steps.all {
            this.dispatchStrategyByStep[it] ==
              ResolvedPhaseStrategyDispatch(identity.slot, identity.strategyId, identity.semanticRevision)
          }
      },
    )
    require(this.dispatchStrategyByStep.keys.containsAll(this.stepPolicyIdentities.keys))
    require(this.dispatchStrategyByStep.keys == this.stepPolicyIdentities.keys)
    require(this.dispatchStrategyByStep.keys.containsAll(this.resumeInterpretationIdentities.keys))
    require(this.dispatchStrategyByStep.keys == this.resumeInterpretationIdentities.keys)
    require(this.stepPolicyIdentities.values.all(String::isNotBlank))
    require(this.resumeInterpretationIdentities.values.all(String::isNotBlank))
    require(this.traversal.entryGates.all { it.phaseId in selectedStepIds && it.requiredPhaseId in selectedStepIds })
    require(
      this.traversal.backwardEdges.all {
        it.fromPhaseId in selectedStepIds && it.destinationPhaseId in selectedStepIds
      },
    )
    require(
      this.traversal.loopOnlySuccessors.all {
          (source, successor) ->
        source in selectedStepIds && successor in selectedStepIds
      },
    )
  }

  private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())

  private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
}
