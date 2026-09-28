package skillbill.engine.featuretask.lifecycle.execution

import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.error.featuretask.CorruptFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeExecutionPlanSchemaError
import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
import skillbill.error.featuretask.MissingFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.UnsupportedFeatureTaskRuntimeExecutionPlanError
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
  ): ResolvedPhaseExecutionPlan {
    val plan = requireSupportedComposition(encoded)
    val supported = FeatureTaskRuntimeEffectivePolicies.resolve(plan, effectiveInputs).sortedBy { it.id }
    val supportedById = supported.associateBy { it.id }
    if (plan.effectivePolicies.any { supportedById[it.id]?.semanticRevision != it.semanticRevision }) {
      throw UnsupportedFeatureTaskRuntimeExecutionPlanError()
    }
    if (plan.effectivePolicies != supported) incompatible()
    return plan
  }

  fun requireCompatibleExecution(
    encoded: ByteArray?,
    expectedDescriptor: ValidatedFeatureTaskRuntimeExecutionPlan?,
  ): ResolvedPhaseExecutionPlan {
    val plan = requireSupportedComposition(encoded)
    val expected = expectedDescriptor
      ?: throw MissingFeatureTaskRuntimeExecutionPlanError()
    val expectedPlan = codec.decode(expected.encoded())
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
    val supported = FeatureTaskRuntimeEffectivePolicies.resolve(
      plan,
      inputs,
    ).sortedBy { it.id }
    if (plan.effectivePolicies != supported) incompatible()
    return plan
  }

  fun requireSupportedComposition(encoded: ByteArray?): ResolvedPhaseExecutionPlan {
    if (encoded == null) throw MissingFeatureTaskRuntimeExecutionPlanError()
    val plan = try {
      codec.decode(encoded)
    } catch (_: InvalidFeatureTaskRuntimeExecutionPlanSchemaError) {
      throw CorruptFeatureTaskRuntimeExecutionPlanError()
    }
    val definition = SkeletonDefinition.entries.singleOrNull {
      it.id == plan.definitionId && it.semanticRevision == plan.definitionSemanticRevision
    } ?: throw UnsupportedFeatureTaskRuntimeExecutionPlanError()
    if (plan.selectedSlots != definition.slots) incompatible()
    plan.selectedStrategies.forEach { identity ->
      if (!strategies.registry.contains(identity.slot, identity.strategyId)) {
        throw UnsupportedFeatureTaskRuntimeExecutionPlanError()
      }
      val strategy = strategies.registry.strategy(identity.slot, identity.strategyId)
      if (strategy.semanticRevision != identity.semanticRevision) {
        throw UnsupportedFeatureTaskRuntimeExecutionPlanError()
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
    val selectionMatches = try {
      strategies.matchesRecordedSelection(plan, definition)
    } catch (_: InvalidPhaseStrategyCompositionError) {
      incompatible()
    }
    if (!selectionMatches) incompatible()
    val traversal = try {
      definition.traversal(plan.selectedStepIds, plan.selectedEntryStepIds)
    } catch (_: IllegalArgumentException) {
      incompatible()
    }
    if (plan.traversal != traversal) incompatible()
    requireSupportedPolicies(plan.effectivePolicies)
    return plan
  }

  private fun requireSupportedPolicies(policies: List<ResolvedExecutionPolicy>) {
    val supported = setOf(
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
      throw UnsupportedFeatureTaskRuntimeExecutionPlanError()
    }
  }

  private fun incompatible(): Nothing = throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
}
