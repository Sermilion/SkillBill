package skillbill.engine.featuretask.slot

import skillbill.engine.directive.directiveResource
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyStrategy
import skillbill.engine.featuretask.slot.plan.PlanFanOutStrategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateStrategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.engine.featuretask.slot.standalonereview.DelegatedStandaloneReviewStrategy
import skillbill.engine.featuretask.slot.standalonereview.InlineStandaloneReviewStrategy
import skillbill.engine.featuretask.slot.state.PhaseHistoricalInterpreter
import skillbill.engine.featuretask.slot.state.PhaseHistoricalPolicy
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.writehistory.BoundaryHistoryStrategy
import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfile
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseStepRecords
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseStrategyIdentity
import skillbill.workflow.taskruntime.model.skeleton.RuntimeReviewSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.skeleton.SkeletonTraversalResult
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.phase.task.traversalOrViolation
import java.util.concurrent.ConcurrentHashMap

class PhaseStrategyLookup(
  val registry: PhaseStrategyRegistry,
  private val selection: PhaseStrategySelection,
  val modelDirectiveSupplements: ModelDirectiveSupplements,
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
      strategy.stepPolicyIdentity(stepId, identity.strategyId) != plan.stepPolicyIdentities[stepId] ||
      strategy.resumeInterpretationIdentity(stepId, identity.strategyId) != plan.resumeInterpretationIdentities[stepId]
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
      selectedOwnerOf(stepId, plan)?.let { owner ->
        (owner as? PhaseResumeCapability)?.resumeRules(stepId) ?: PhaseResumeRules.None
      } ?: history.resumeRules(stepId)
    }

  internal fun verdictRule(
    stepId: String,
    plan: ResolvedPhaseExecutionPlan,
    diagnostics: RuntimeDiagnostics,
  ) = (selectedOwnerOf(stepId, plan) as? PhaseVerdictCapability)?.verdictRule(stepId, diagnostics)

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
          plan.qaSelection?.let(::add)
        },
        plan.stepLaunchAssignments,
      )
    return plan.selectedStrategies.all { identity ->
      selection.binds(identity.slot, recordedFacts) &&
        selection.strategyIdFor(identity.slot, recordedFacts) ==
        registry.canonicalStrategyId(identity.slot, identity.strategyId)
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
          recorded.qaSelection?.let(::add)
        },
        recorded.stepLaunchAssignments,
      )
    val current = executionPlan(facts).recordedAs(recorded)
    return recorded.selectedStrategies.mapNotNull { identity ->
      if (!registry.contains(identity.slot, identity.strategyId)) return@mapNotNull null
      val owner = registry.strategy(identity.slot, identity.strategyId)
      (owner as? PhaseRecordedPlanMappingCapability)?.mapRecordedExecutionPlan(owner, recorded, current)
    }.singleOrNull()
  }

  private fun ResolvedPhaseExecutionPlan.recordedAs(recorded: ResolvedPhaseExecutionPlan): ResolvedPhaseExecutionPlan {
    val recordedIds = recorded.selectedStrategies.associate { it.slot to it.strategyId }
    val aliasedSteps = linkedMapOf<String, Pair<PhaseStrategy, String>>()
    val identities =
      selectedStrategies.map { identity ->
        val recordedId = recordedIds[identity.slot]
        if (
          recordedId == null || recordedId == identity.strategyId ||
          registry.canonicalStrategyId(identity.slot, recordedId) != identity.strategyId
        ) {
          return@map identity
        }
        val strategy = registry.strategy(identity.slot, identity.strategyId)
        identity.steps.forEach { step -> aliasedSteps[step] = strategy to recordedId }
        identity.copy(strategyId = recordedId)
      }
    if (aliasedSteps.isEmpty()) return this
    return ResolvedPhaseExecutionPlan(
      definitionId = definitionId,
      definitionSemanticRevision = definitionSemanticRevision,
      selectedStrategies = identities,
      reviewSelection = reviewSelection,
      qualityGateSelection = qualityGateSelection,
      traversal = traversal,
      stepRecords =
        ResolvedPhaseStepRecords(
          stepPolicyIdentities.mapValues { (step, identity) ->
            aliasedSteps[step]?.let { (strategy, id) -> strategy.stepPolicyIdentity(step, id) } ?: identity
          },
          resumeInterpretationIdentities.mapValues { (step, identity) ->
            aliasedSteps[step]?.let { (strategy, id) -> strategy.resumeInterpretationIdentity(step, id) } ?: identity
          },
          stepLaunchAssignments,
        ),
      effectivePolicies = effectivePolicies,
      effectivePolicySettings = effectivePolicySettings,
      contractVersion = contractVersion,
      qaSelection = qaSelection,
    )
  }

  fun validateTraversalOverride(
    facts: PhaseStrategySelectionFacts,
    declaration: FeatureTaskRuntimeTransitionDeclaration,
  ): FeatureTaskRuntimeTransitionDeclaration {
    val plan = executionPlan(facts)
    return plan.withTraversal(declaration).traversal
  }

  private fun resolve(facts: PhaseStrategySelectionFacts): ResolvedPhaseExecutionPlan {
    val copied = facts.copy(values = facts.values.toSet(), stepAssignments = facts.copiedAssignments())
    val key =
      PlanKey(
        copied.definition.id,
        copied.definition.slots.map(PhaseSlot::wireValue),
        copied.definition.stepIds.toList(),
        copied.definition.runStateKind.wireValue,
        copied.definition.intake.wireValue,
        copied.definition.semanticRevision,
        copied.values.map { "${it.javaClass.name}:${it.name}" }.sorted(),
        copied.stepAssignments.map { (step, assignment) ->
          listOf(
            step,
            assignment.agentId,
            assignment.launch.requestedModel.orEmpty(),
            assignment.launch.requestedEffort.orEmpty(),
            assignment.launch.effectiveModel.orEmpty(),
            assignment.launch.namespace.wireValue,
            assignment.launch.provenance.wireValue,
            assignment.launch.unknownReason?.wireValue.orEmpty(),
            assignment.launch.profile.wireValue,
          ).joinToString("\u0001")
        }.sorted(),
      )
    return plans.computeIfAbsent(key) { resolveUncached(copied) }
  }

  private fun resolveUncached(facts: PhaseStrategySelectionFacts): ResolvedPhaseExecutionPlan {
    val selected = facts.definition.slots.map { slot -> registry.strategy(slot, selection.strategyIdFor(slot, facts)) }
    val selectedSteps = linkedMapOf<String, PhaseStrategy>()
    populateSelectedSteps(facts, selected, selectedSteps)
    val entrySteps = selected.map { it.entryStep }.toSet()
    validateSelectedTraversalReferences(facts, selectedSteps.keys)
    val declaration =
      when (val result = facts.definition.traversalOrViolation(selectedSteps.keys, entrySteps)) {
        is SkeletonTraversalResult.Ready -> result.declaration
        is SkeletonTraversalResult.Rejected ->
          invalidComposition("definition ${facts.definition.id} has incoherent traversal: ${result.reason}")
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
        qaSelection = singleQaSelection(facts),
        traversal = declaration,
        stepRecords =
          ResolvedPhaseStepRecords(
            policies,
            resume,
            facts.stepAssignments.filterKeys { it in selectedSteps },
          ),
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

  private fun singleQaSelection(facts: PhaseStrategySelectionFacts): FeatureTaskRuntimeQaSelection? {
    val selections = facts.values.filterIsInstance<FeatureTaskRuntimeQaSelection>()
    if (selections.size > 1) invalidComposition("multiple QA selections were supplied")
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
    val assignments: List<String>,
  )

  private fun invalidComposition(reason: String): Nothing = throw InvalidPhaseStrategyCompositionError(reason)
}

class ModelDirectiveSupplements(val enabled: Boolean) {
  private val loaded = ConcurrentHashMap<String, String>()

  internal fun directiveFor(
    owner: PhaseStrategy,
    stepId: String,
    profile: PhaseModelProfile,
  ): String? {
    if (!enabled || profile != PhaseModelProfile.OPUS_5_5) return null
    val path =
      if (owner.slot == PhaseSlot.PLAN &&
        owner.strategyId == PlanFanOutStrategy.ID &&
        stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS
      ) {
        PLAN_FAN_OUT_SUBTASKS_RESOURCE
      } else {
        RESOURCES[owner.slot to owner.strategyId]
      }
    return path?.let { resource -> loaded.computeIfAbsent(resource) { directiveResource(it).trimEnd() } }
      ?.takeIf(String::isNotBlank)
  }

  internal fun directiveFor(
    run: PhaseRun,
    owner: PhaseStrategy,
  ): String? = directiveFor(owner, run.phaseId, run.launchAssignment?.profile ?: PhaseModelProfile.CANONICAL)

  internal fun supplement(
    sections: PhaseStepPromptSections,
    owner: PhaseStrategy,
    stepId: String,
    profile: PhaseModelProfile,
  ): PhaseStepPromptSections {
    val block = directiveFor(owner, stepId, profile) ?: return sections
    return sections.copy(
      taskDirective = "${sections.taskDirective}\n\n$block",
      retryFocus = if (sections.retryFocus.isBlank()) sections.retryFocus else "${sections.retryFocus}\n\n$block",
      outputContract = sections.outputContract?.let { "$it\n\n$block" },
    )
  }

  private companion object {
    const val BASE = "/skillbill/engine/featuretask/slot"
    const val PLAN_FAN_OUT_SUBTASKS_RESOURCE = "$BASE/plan/opus-5-5-goal-plan-fan-out.md"

    val RESOURCES: Map<Pair<PhaseSlot, String>, String> =
      mapOf(
        (PhaseSlot.PREPLAN to AgentPreplanStrategy.ID) to "$BASE/preplan/opus-5-5-agent-preplan.md",
        (PhaseSlot.PLAN to PlanFanOutStrategy.ID) to "$BASE/plan/opus-5-5-agent-plan.md",
        (PhaseSlot.IMPLEMENTATION to ImplementThenSimplifyStrategy.ID) to
          "$BASE/implementation/opus-5-5-implement-then-simplify.md",
        (PhaseSlot.AUDIT to AcceptanceAuditStrategy.ID) to "$BASE/audit/opus-5-5-acceptance-audit.md",
        (PhaseSlot.CODE_REVIEW to InlineReviewStrategy.ID) to "$BASE/codereview/opus-5-5-inline.md",
        (PhaseSlot.STANDALONE_REVIEW to InlineStandaloneReviewStrategy.ID) to
          "$BASE/standalonereview/opus-5-5-inline.md",
        (PhaseSlot.STANDALONE_REVIEW to DelegatedStandaloneReviewStrategy.ID) to
          "$BASE/standalonereview/opus-5-5-delegated.md",
        (PhaseSlot.QUALITY_GATE to PackBuildStrategy.ID) to "$BASE/qualitygate/opus-5-5-pack-build.md",
        (PhaseSlot.QUALITY_GATE to AgentValidateStrategy.ID) to "$BASE/qualitygate/opus-5-5-agent-validate.md",
        (PhaseSlot.WRITE_HISTORY to BoundaryHistoryStrategy.ID) to "$BASE/writehistory/opus-5-5-boundary-history.md",
        (PhaseSlot.PULL_REQUEST to PrDescriptionStrategy.ID) to "$BASE/pullrequest/opus-5-5-pr-description.md",
      )
  }
}
