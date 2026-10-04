package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.slot.state.PhaseHistoricalInterpreter
import skillbill.engine.featuretask.slot.state.PhaseHistoricalPolicy
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseStrategyDispatch
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseStrategyIdentity
import skillbill.workflow.taskruntime.model.skeleton.RuntimeReviewSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.phase.task.traversal
import java.util.concurrent.ConcurrentHashMap

class PhaseStrategyLookup(
  val registry: PhaseStrategyRegistry,
  private val selection: PhaseStrategySelection,
) {
  private val plans = ConcurrentHashMap<PlanKey, ResolvedPhaseExecutionPlan>()
  private val history = PhaseHistoricalInterpreter(PhaseHistoricalPolicy.REVISION_1)

  fun strategyFor(
    stepId: String,
    facts: PhaseStrategySelectionFacts,
  ): PhaseStrategy {
    return strategyFor(stepId, executionPlan(facts))
  }

  fun strategyFor(
    stepId: String,
    plan: ResolvedPhaseExecutionPlan,
  ): PhaseStrategy =
    selectedOwnerOf(stepId, plan)
      ?: invalidComposition("step $stepId is not selected for ${plan.definitionId}")

  internal fun runnerFor(
    stepId: String,
    plan: ResolvedPhaseExecutionPlan,
  ): PhaseRunner {
    val strategy = strategyFor(stepId, plan)
    return registry.runner(strategy.slot, strategy.strategyId)
  }

  fun selectedOwnerOf(
    stepId: String,
    plan: ResolvedPhaseExecutionPlan,
  ): PhaseStrategy? {
    val dispatch = plan.dispatchStrategyByStep[stepId] ?: return null
    val identity =
      plan.selectedStrategies.singleOrNull {
        it.slot == dispatch.slot && it.strategyId == dispatch.strategyId &&
          it.semanticRevision == dispatch.semanticRevision && stepId in it.steps
      }
        ?: invalidComposition("plan has no unique strategy identity for $stepId")
    val strategy = registry.strategy(identity.slot, identity.strategyId)
    if (strategy.semanticRevision != identity.semanticRevision) {
      invalidComposition(
        "strategy ${identity.strategyId} revision does not match the resolved plan",
      )
    }
    if (
      strategy.stepPolicyIdentity(stepId) != plan.stepPolicyIdentities[stepId] ||
      strategy.resumeInterpretationIdentity(stepId) != plan.resumeInterpretationIdentities[stepId]
    ) {
      invalidComposition(
        "strategy ${identity.strategyId} policies do not match the resolved plan",
      )
    }
    return strategy
  }

  fun selectedStepIds(facts: PhaseStrategySelectionFacts): Set<String> = executionPlan(facts).selectedStepIds

  internal fun resumeRules(plan: ResolvedPhaseExecutionPlan): (String) -> PhaseResumeRules =
    { stepId ->
      selectedOwnerOf(stepId, plan)?.resumeRules(stepId) ?: history.resumeRules(stepId)
    }

  internal fun verdictRule(
    stepId: String,
    plan: ResolvedPhaseExecutionPlan,
    diagnostics: RuntimeDiagnostics,
  ) = selectedOwnerOf(stepId, plan)?.verdictRule(stepId, diagnostics)

  fun executionPlan(facts: PhaseStrategySelectionFacts): ResolvedPhaseExecutionPlan = resolve(facts)

  internal fun matchesRecordedSelection(
    plan: ResolvedPhaseExecutionPlan,
    definition: SkeletonDefinition,
  ): Boolean {
    val recordedFacts =
      PhaseStrategySelectionFacts(
        definition,
        buildSet {
          plan.reviewSelection?.let { review ->
            add(CodeReviewExecutionMode.entries.single { it.wireValue == review.wireValue })
          }
          plan.qualityGateSelection?.let(::add)
        },
      )
    return plan.selectedStrategies.all { identity ->
      selection.binds(identity.slot, recordedFacts) &&
        selection.strategyIdFor(identity.slot, recordedFacts) == identity.strategyId
    }
  }

  internal fun executionPlanMapping(recorded: ResolvedPhaseExecutionPlan): PhaseExecutionPlanMapping? {
    val definition =
      SkeletonDefinition.entries.singleOrNull {
        it.id == recorded.definitionId && it.semanticRevision == recorded.definitionSemanticRevision
      } ?: return null
    val facts =
      PhaseStrategySelectionFacts(
        definition,
        buildSet {
          recorded.reviewSelection?.let { add(CodeReviewExecutionMode.valueOf(it.name)) }
          recorded.qualityGateSelection?.let(::add)
        },
      )
    val current = executionPlan(facts)
    return recorded.selectedStrategies.mapNotNull { identity ->
      if (!registry.contains(identity.slot, identity.strategyId)) return@mapNotNull null
      registry.strategy(identity.slot, identity.strategyId).mapRecordedExecutionPlan(recorded, current)
    }.singleOrNull()
  }

  fun validateTraversalOverride(
    facts: PhaseStrategySelectionFacts,
    declaration: FeatureTaskRuntimeTransitionDeclaration,
  ): FeatureTaskRuntimeTransitionDeclaration {
    val plan = executionPlan(facts)
    return plan.withTraversal(declaration).traversal
  }

  private fun resolve(facts: PhaseStrategySelectionFacts): ResolvedPhaseExecutionPlan {
    val key =
      PlanKey(
        facts.definition.id,
        facts.definition.slots.map(PhaseSlot::wireValue),
        facts.definition.stepIds.toList(),
        facts.definition.runStateKind.wireValue,
        facts.definition.intake.wireValue,
        facts.definition.semanticRevision,
        facts.values.map { "${it.javaClass.name}:${it.name}" }.sorted(),
      )
    return plans.computeIfAbsent(key) { resolveUncached(facts.copy(values = facts.values.toSet())) }
  }

  private fun resolveUncached(facts: PhaseStrategySelectionFacts): ResolvedPhaseExecutionPlan {
    val selected = facts.definition.slots.map { slot -> registry.strategy(slot, selection.strategyIdFor(slot, facts)) }
    val selectedSteps = linkedMapOf<String, PhaseStrategy>()
    populateSelectedSteps(facts, selected, selectedSteps)
    val entrySteps = selected.map { it.entryStep }.toSet()
    validateSelectedTraversalReferences(facts, selectedSteps.keys)
    val declaration =
      try {
        facts.definition.traversal(selectedSteps.keys, entrySteps)
      } catch (error: IllegalArgumentException) {
        invalidComposition(
          "definition ${facts.definition.id} has incoherent traversal: ${error.message}",
        )
      }
    val missingEntries = entrySteps - declaration.forwardPhaseIds.toSet()
    if (missingEntries.isNotEmpty()) {
      invalidComposition(
        "selected entries are unreachable: ${missingEntries.sorted().joinToString()}",
      )
    }
    val identities =
      selected.map { strategy ->
        ResolvedPhaseStrategyIdentity(
          strategy.slot,
          strategy.strategyId,
          strategy.semanticRevision,
          strategy.steps.filter { it in selectedSteps },
          strategy.entryStep,
        )
      }
    val policies = selectedSteps.mapValues { (step, strategy) -> strategy.stepPolicyIdentity(step) }
    val resume = selectedSteps.mapValues { (step, strategy) -> strategy.resumeInterpretationIdentity(step) }
    val plan =
      ResolvedPhaseExecutionPlan(
        definitionId = facts.definition.id,
        definitionSemanticRevision = facts.definition.semanticRevision,
        selectedStrategies = identities,
        reviewSelection = reviewSelection(facts),
        qualityGateSelection = singleQualityGateSelection(facts),
        traversal = declaration,
        dispatchStrategyByStep =
          selectedSteps.mapValues { (_, strategy) ->
            ResolvedPhaseStrategyDispatch(strategy.slot, strategy.strategyId, strategy.semanticRevision)
          },
        stepPolicyIdentities = policies,
        resumeInterpretationIdentities = resume,
      )
    return plan
  }

  private fun populateSelectedSteps(
    facts: PhaseStrategySelectionFacts,
    selected: List<PhaseStrategy>,
    selectedSteps: MutableMap<String, PhaseStrategy>,
  ) {
    selected.forEach { strategy ->
      strategy.steps.forEach { step ->
        val slot = PhaseSlot.slotForStep(step)
        if (slot != strategy.slot) {
          invalidComposition(
            "${strategy.strategyId} claims $step outside ${strategy.slot.wireValue}",
          )
        }
        if (step !in facts.definition.stepIds && step !in strategy.optionalSteps) {
          invalidComposition(
            "selected step $step is absent from ${facts.definition.id} and is not optional",
          )
        }
        if (step in facts.definition.stepIds) {
          if (selectedSteps.put(step, strategy) != null) {
            invalidComposition("multiple selected strategies own $step")
          }
        }
      }
      if (strategy.entryStep !in facts.definition.stepIds) {
        invalidComposition(
          "selected entry ${strategy.entryStep} is outside ${facts.definition.id}",
        )
      }
    }
  }

  private fun validateSelectedTraversalReferences(
    facts: PhaseStrategySelectionFacts,
    selectedStepIds: Set<String>,
  ) {
    val definitionStepIds = facts.definition.stepIds.toSet()
    val transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions
    transitions.entryGates.forEach { gate ->
      if (
        gate.phaseId in selectedStepIds && gate.requiredPhaseId in definitionStepIds &&
        gate.requiredPhaseId !in selectedStepIds
      ) {
        invalidComposition(
          "entry gate ${gate.phaseId} requires unselected ${gate.requiredPhaseId}",
        )
      }
    }
    transitions.backwardEdges.forEach { edge ->
      if (
        edge.fromPhaseId in selectedStepIds && edge.destinationPhaseId in definitionStepIds &&
        unselectedRemediationTarget(edge.destinationPhaseId, selectedStepIds)
      ) {
        invalidComposition(
          "remediation edge ${edge.fromPhaseId} targets unselected ${edge.destinationPhaseId}",
        )
      }
    }
    transitions.loopOnlySuccessors.forEach { (source, successor) ->
      if (source in selectedStepIds && successor in definitionStepIds && successor !in selectedStepIds) {
        invalidComposition("loop-only step $source requires unselected successor $successor")
      }
    }
  }

  private fun unselectedRemediationTarget(
    destination: String,
    selected: Set<String>,
  ): Boolean = destination !in selected && !isUnselectedRegenerationAlternative(destination, selected)

  private fun isUnselectedRegenerationAlternative(
    destinationStepId: String,
    selectedStepIds: Set<String>,
  ): Boolean {
    val alternatives = FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_LOOP_ID_BY_PRODUCER.keys
    return destinationStepId in alternatives && alternatives.any { it != destinationStepId && it in selectedStepIds }
  }

  private fun reviewSelection(facts: PhaseStrategySelectionFacts): RuntimeReviewSelection? =
    facts.values.filterIsInstance<CodeReviewExecutionMode>().let { modes ->
      if (modes.size > 1) {
        invalidComposition("multiple review selections were supplied")
      }
      modes.singleOrNull()
    }?.let { mode ->
      when (mode) {
        CodeReviewExecutionMode.AUTO -> RuntimeReviewSelection.AUTO
        CodeReviewExecutionMode.INLINE -> RuntimeReviewSelection.INLINE
        CodeReviewExecutionMode.DELEGATED -> RuntimeReviewSelection.DELEGATED
      }
    }

  private fun singleQualityGateSelection(facts: PhaseStrategySelectionFacts): FeatureTaskRuntimeQualityGateSelection? {
    val selections = facts.values.filterIsInstance<FeatureTaskRuntimeQualityGateSelection>()
    if (selections.size > 1) {
      invalidComposition("multiple quality gate selections were supplied")
    }
    return selections.singleOrNull()
  }

  private data class PlanKey(
    val definitionId: String,
    val slots: List<String>,
    val steps: List<String>,
    val runStateKind: String,
    val intake: String,
    val semanticRevision: Int,
    val facts: List<String>,
  )

  private fun invalidComposition(reason: String): Nothing = throw InvalidPhaseStrategyCompositionError(reason)
}
