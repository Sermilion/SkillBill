package skillbill.engine.featuretask.lifecycle.execution

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanAdmissionCode
import skillbill.error.featuretask.PhaseSlotFailureCode
import skillbill.error.featuretask.executionPlanRefused
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.ResolvedExecutionPolicy
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.traversal

@Inject
class FeatureTaskRuntimeExecutionPlanCompatibility(
  private val codec: FeatureTaskRuntimeExecutionPlanCodec,
  private val strategies: PhaseStrategyLookup,
) {
  fun requireSupportedExecution(
    encoded: ByteArray?,
    effectiveInputs: EffectiveGatePolicyInputs,
    onMapping: (() -> Unit)? = null,
  ): ResolvedPhaseExecutionPlan {
    val plan = requireSupportedComposition(encoded)
    val supported = FeatureTaskRuntimeEffectivePolicies.resolve(plan, effectiveInputs).sortedBy { it.id }
    val supportedById = supported.associateBy { it.id }
    if (plan.effectivePolicies.any { supportedById[it.id]?.semanticRevision != it.semanticRevision }) {
      throw executionPlanRefused(FeatureTaskRuntimeExecutionPlanAdmissionCode.UNSUPPORTED_DESCRIPTOR)
    }
    if (plan.effectivePolicies != supported) incompatible()
    if (!codec.encode(plan).contentEquals(codec.encode(decodePlan(requireNotNull(encoded))))) onMapping?.invoke()
    return plan
  }

  fun requireCompatibleExecution(
    encoded: ByteArray?,
    expectedDescriptor: ValidatedFeatureTaskRuntimeExecutionPlan?,
  ): ResolvedPhaseExecutionPlan {
    val plan = requireSupportedComposition(encoded)
    val expected =
      expectedDescriptor
        ?: throw executionPlanRefused(FeatureTaskRuntimeExecutionPlanAdmissionCode.MISSING_DESCRIPTOR)
    val expectedPlan = requireSupportedComposition(expected.encoded())
    requireSupportedPolicies(expectedPlan.effectivePolicies)
    if (!codec.encode(plan).contentEquals(codec.encode(expectedPlan))) incompatible()
    return plan
  }

  fun requireSupportedRecovery(
    encoded: ByteArray?,
    effectiveInputs: EffectiveGatePolicyInputs?,
  ): ResolvedPhaseExecutionPlan {
    val plan = requireSupportedComposition(encoded)
    val inputs = effectiveInputs ?: incompatible()
    val supported =
      FeatureTaskRuntimeEffectivePolicies.resolve(
        plan,
        inputs,
      ).sortedBy { it.id }
    if (plan.effectivePolicies != supported) incompatible()
    return plan
  }

  fun requireSupportedComposition(encoded: ByteArray?): ResolvedPhaseExecutionPlan {
    if (encoded == null) throw executionPlanRefused(FeatureTaskRuntimeExecutionPlanAdmissionCode.MISSING_DESCRIPTOR)
    val recorded = decodePlan(encoded)
    val mapping =
      try {
        strategies.executionPlanMapping(recorded)
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.code == PhaseSlotFailureCode.INVALID_STRATEGY_COMPOSITION)
        incompatible()
      }
    val plan =
      if (mapping == null) {
        recorded
      } else {
        if (!codec.encode(mapping.previous).contentEquals(codec.encode(recorded))) incompatible()
        mapping.supported.withEffectivePolicies(
          FeatureTaskRuntimeEffectivePolicies.mapStepIdentityPolicies(recorded, mapping.supported),
        )
      }
    val definition =
      SkeletonDefinition.entries.singleOrNull {
        it.id == plan.definitionId && it.semanticRevision == plan.definitionSemanticRevision
      } ?: throw executionPlanRefused(FeatureTaskRuntimeExecutionPlanAdmissionCode.UNSUPPORTED_DESCRIPTOR)
    if (plan.selectedSlots != definition.slots) incompatible()
    requireSupportedStrategies(plan, definition)
    val selectionMatches =
      try {
        strategies.matchesRecordedSelection(plan, definition)
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.code == PhaseSlotFailureCode.INVALID_STRATEGY_COMPOSITION)
        incompatible()
      }
    if (!selectionMatches) incompatible()
    val traversal =
      try {
        definition.traversal(plan.selectedStepIds, plan.selectedEntryStepIds)
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.code == PhaseSlotFailureCode.INVALID_STRATEGY_COMPOSITION)
        incompatible()
      }
    if (plan.traversal != traversal) incompatible()
    requireSupportedPolicies(plan.effectivePolicies)
    return plan
  }

  private fun decodePlan(encoded: ByteArray): ResolvedPhaseExecutionPlan =
    try {
      codec.decode(encoded)
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.code == FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_PLAN_SCHEMA)
      throw executionPlanRefused(FeatureTaskRuntimeExecutionPlanAdmissionCode.CORRUPT_DESCRIPTOR).also {
        it.addSuppressed(error)
      }
    }

  private fun requireSupportedStrategies(
    plan: ResolvedPhaseExecutionPlan,
    definition: SkeletonDefinition,
  ) {
    plan.selectedStrategies.forEach { identity ->
      if (!strategies.registry.contains(identity.slot, identity.strategyId)) {
        throw executionPlanRefused(FeatureTaskRuntimeExecutionPlanAdmissionCode.UNSUPPORTED_DESCRIPTOR)
      }
      val strategy = strategies.registry.strategy(identity.slot, identity.strategyId)
      if (strategy.semanticRevision != identity.semanticRevision) {
        throw executionPlanRefused(FeatureTaskRuntimeExecutionPlanAdmissionCode.UNSUPPORTED_DESCRIPTOR)
      }
      if (
        identity.entryStep != strategy.entryStep ||
        identity.steps != strategy.steps.filter { it in definition.stepIds }
      ) {
        incompatible()
      }
      identity.steps.forEach { step ->
        if (
          plan.stepPolicyIdentities[step] != strategy.stepPolicyIdentity(step) ||
          plan.resumeInterpretationIdentities[step] != strategy.resumeInterpretationIdentity(step)
        ) {
          incompatible()
        }
      }
    }
  }

  private fun requireSupportedPolicies(policies: List<ResolvedExecutionPolicy>) {
    val supported =
      setOf(
        "gate-commands",
        "receipt-interpretation",
        "retry-budgets",
        "resume-budgets",
        "acceptance-audit",
        "review-invalidation",
        "checkpoint-ownership",
        "finalization",
      )
    if (policies.map { it.id }.toSet() != supported || policies.any { it.semanticRevision != 1 }) {
      throw executionPlanRefused(FeatureTaskRuntimeExecutionPlanAdmissionCode.UNSUPPORTED_DESCRIPTOR)
    }
  }

  private fun incompatible(): Nothing =
    throw executionPlanRefused(FeatureTaskRuntimeExecutionPlanAdmissionCode.INCOMPATIBLE_DESCRIPTOR)
}
