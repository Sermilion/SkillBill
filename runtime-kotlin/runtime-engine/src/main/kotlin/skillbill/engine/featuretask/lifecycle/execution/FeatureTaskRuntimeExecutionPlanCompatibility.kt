package skillbill.engine.featuretask.lifecycle.execution

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.model.execution.RecordedMonitorExecutionSemantics
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.error.featuretask.CorruptFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeExecutionPlanSchemaError
import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
import skillbill.error.featuretask.MissingFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.UnsupportedFeatureTaskRuntimeExecutionPlanError
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedExecutionPolicy
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.skeleton.SkeletonTraversalResult
import skillbill.workflow.taskruntime.phase.task.traversalOrViolation

@Inject
public class FeatureTaskRuntimeExecutionPlanCompatibility(
  private val codec: FeatureTaskRuntimeExecutionPlanCodec,
  private val strategies: PhaseStrategyLookup,
) {
  internal data class SupportedExecution(
    val plan: ResolvedPhaseExecutionPlan,
    val recordedMonitorSemantics: RecordedMonitorExecutionSemantics,
  )

  internal fun requireSupportedExecution(
    encoded: ByteArray?,
    effectiveInputs: EffectiveGatePolicyInputs,
    onMapping: (() -> Unit)? = null,
  ): ResolvedPhaseExecutionPlan {
    return requireSupportedExecutionWithRecordedSemantics(encoded, effectiveInputs, onMapping).plan
  }

  internal fun requireSupportedExecutionWithRecordedSemantics(
    encoded: ByteArray?,
    effectiveInputs: EffectiveGatePolicyInputs,
    onMapping: (() -> Unit)? = null,
  ): SupportedExecution {
    val composition = requireSupportedCompositionWithRecordedSemantics(encoded)
    val plan = composition.plan
    val supported = FeatureTaskRuntimeEffectivePolicies.resolve(plan, effectiveInputs).sortedBy { it.id }
    val supportedById = supported.associateBy { it.id }
    if (plan.effectivePolicies.any { supportedById[it.id]?.semanticRevision != it.semanticRevision }) {
      throw UnsupportedFeatureTaskRuntimeExecutionPlanError()
    }
    if (plan.effectivePolicies != supported) incompatible()
    if (!codec.encode(plan).contentEquals(codec.encode(decodePlan(requireNotNull(encoded))))) onMapping?.invoke()
    return SupportedExecution(plan, composition.recordedMonitorSemantics)
  }

  internal fun requireCompatibleExecution(
    encoded: ByteArray?,
    expectedDescriptor: ValidatedFeatureTaskRuntimeExecutionPlan?,
  ): ResolvedPhaseExecutionPlan {
    val plan = requireSupportedComposition(encoded)
    val expected =
      expectedDescriptor
        ?: throw MissingFeatureTaskRuntimeExecutionPlanError()
    val expectedPlan = requireSupportedComposition(expected.encoded())
    requireSupportedPolicies(expectedPlan.effectivePolicies)
    if (!codec.encode(plan).contentEquals(codec.encode(expectedPlan))) incompatible()
    return plan
  }

  internal fun requireSupportedRecovery(
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

  internal fun requireSupportedComposition(encoded: ByteArray?): ResolvedPhaseExecutionPlan {
    return requireSupportedCompositionWithRecordedSemantics(encoded).plan
  }

  private fun requireSupportedCompositionWithRecordedSemantics(encoded: ByteArray?): SupportedComposition {
    if (encoded == null) throw MissingFeatureTaskRuntimeExecutionPlanError()
    val recorded = decodePlan(encoded)
    val recordedMonitorSemantics = RecordedMonitorExecutionSemantics.from(recorded)
    val mapping = strategies.executionPlanMapping(recorded)
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
      } ?: throw UnsupportedFeatureTaskRuntimeExecutionPlanError()
    if (plan.selectedSlots != definition.slots) incompatible()
    requireSupportedSelections(plan, definition)
    val traversal =
      when (val result = definition.traversalOrViolation(plan.selectedStepIds, plan.selectedEntryStepIds)) {
        is SkeletonTraversalResult.Ready -> result.declaration
        is SkeletonTraversalResult.Rejected -> incompatible()
      }
    if (plan.traversal != traversal) incompatible()
    requireSupportedPolicies(plan.effectivePolicies)
    return SupportedComposition(plan, recordedMonitorSemantics)
  }

  private fun requireSupportedSelections(
    plan: ResolvedPhaseExecutionPlan,
    definition: SkeletonDefinition,
  ) {
    val featureRun = plan.definitionId in setOf(SkeletonDefinition.GOAL_CHILD.id, SkeletonDefinition.STANDALONE.id)
    if (featureRun && (PhaseSlot.QA in definition.slots) != (plan.qaSelection != null)) {
      throw UnsupportedFeatureTaskRuntimeExecutionPlanError()
    }
    requireSupportedStrategies(plan, definition)
    val selectionMatches =
      try {
        strategies.matchesRecordedSelection(plan, definition)
      } catch (_: InvalidPhaseStrategyCompositionError) {
        incompatible()
      }
    if (!selectionMatches) incompatible()
  }

  private data class SupportedComposition(
    val plan: ResolvedPhaseExecutionPlan,
    val recordedMonitorSemantics: RecordedMonitorExecutionSemantics,
  )

  private fun decodePlan(encoded: ByteArray): ResolvedPhaseExecutionPlan =
    try {
      codec.decode(encoded)
    } catch (error: InvalidFeatureTaskRuntimeExecutionPlanSchemaError) {
      throw CorruptFeatureTaskRuntimeExecutionPlanError().also { it.addSuppressed(error) }
    }

  private fun requireSupportedStrategies(
    plan: ResolvedPhaseExecutionPlan,
    definition: SkeletonDefinition,
  ) {
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
          plan.stepPolicyIdentities[step] != strategy.stepPolicyIdentity(step, identity.strategyId) ||
          plan.resumeInterpretationIdentities[step] != strategy.resumeInterpretationIdentity(step, identity.strategyId)
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
      throw UnsupportedFeatureTaskRuntimeExecutionPlanError()
    }
  }

  private fun incompatible(): Nothing = throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
}
