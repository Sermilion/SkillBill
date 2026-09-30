package skillbill.engine.featuretask.lifecycle.execution

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeExecutionPlanSchemaError
import skillbill.error.featuretask.UnsupportedFeatureTaskRuntimeExecutionPlanError
import skillbill.ports.taskruntime.FeatureTaskRuntimeExecutionPlanValidator
import skillbill.workflow.taskruntime.model.skeleton.ResolvedFeatureTaskRuntimeExecutionSettings
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.traversal
import java.security.MessageDigest
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as Keys

@Inject
class FeatureTaskRuntimeExecutionPlanCodec(
  private val validator: FeatureTaskRuntimeExecutionPlanValidator,
) {
  fun encodeExecution(
    plan: ResolvedPhaseExecutionPlan,
    effectiveInputs: EffectiveGatePolicyInputs,
  ): ByteArray {
    val definition =
      SkeletonDefinition.entries.singleOrNull {
        it.id == plan.definitionId && it.semanticRevision == plan.definitionSemanticRevision
      } ?: throw UnsupportedFeatureTaskRuntimeExecutionPlanError()
    if (plan.traversal != definition.traversal(plan.selectedStepIds, plan.selectedEntryStepIds)) {
      throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
    }
    return encode(
      plan.withEffectivePolicies(FeatureTaskRuntimeEffectivePolicies.resolve(plan, effectiveInputs)),
      effectiveInputs,
    )
  }

  fun encode(plan: ResolvedPhaseExecutionPlan): ByteArray = encode(plan, null)

  private fun encode(
    plan: ResolvedPhaseExecutionPlan,
    effectiveInputs: EffectiveGatePolicyInputs?,
  ): ByteArray =
    validator.canonicalize(
      JsonCodec.mapToJsonString(
        linkedMapOf(
          Keys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION,
          Keys.DEFINITION to
            mapOf(
              Keys.ID to plan.definitionId,
              Keys.SEMANTIC_REVISION to plan.definitionSemanticRevision,
            ),
          Keys.SELECTED_STRATEGIES to
            plan.selectedStrategies.map { strategy ->
              mapOf(
                Keys.SLOT to strategy.slot.wireValue,
                Keys.STRATEGY_ID to strategy.strategyId,
                Keys.SEMANTIC_REVISION to strategy.semanticRevision,
                Keys.SELECTED_STEPS to strategy.steps,
                Keys.ENTRY_STEP to strategy.entryStep,
              )
            },
          Keys.REVIEW_SELECTION to plan.reviewSelection?.wireValue,
          Keys.QUALITY_GATE_SELECTION to plan.qualityGateSelection?.wireValue,
          Keys.TRAVERSAL to encodeExecutionPlanTraversal(plan.traversal),
          Keys.DISPATCH_OWNERSHIP to
            plan.dispatchStrategyByStep.map { (step, owner) ->
              mapOf(
                Keys.STEP to step,
                Keys.SLOT to owner.slot.wireValue,
                Keys.STRATEGY_ID to owner.strategyId,
                Keys.SEMANTIC_REVISION to owner.semanticRevision,
              )
            },
          Keys.STEP_POLICIES to encodePolicies(plan.stepPolicyIdentities),
          Keys.RESUME_INTERPRETATIONS to encodePolicies(plan.resumeInterpretationIdentities),
          Keys.EFFECTIVE_POLICIES to
            plan.effectivePolicies.map { policy ->
              mapOf(
                Keys.ID to policy.id,
                Keys.SEMANTIC_REVISION to policy.semanticRevision,
                Keys.SEMANTIC_DIGEST to policy.semanticDigest,
              )
            },
        ).apply {
          (
            effectiveInputs?.let {
              ResolvedFeatureTaskRuntimeExecutionSettings(it.validationDepth, it.phaseTimeoutMillis)
            } ?: plan.effectivePolicySettings
          )?.let { settings ->
            put(
              Keys.EFFECTIVE_POLICY_SETTINGS,
              mapOf(
                Keys.VALIDATION_DEPTH to settings.validationDepth.wireValue,
                Keys.PHASE_TIMEOUT_MILLIS to settings.phaseTimeoutMillis,
              ),
            )
          }
        },
      ).toByteArray(Charsets.UTF_8),
      SOURCE,
    )

  fun decode(encoded: ByteArray): ResolvedPhaseExecutionPlan {
    val payload =
      requireNotNull(
        JsonCodec.anyToStringAnyMap(
          JsonCodec.parseValue(validator.canonicalize(encoded, SOURCE).toString(Charsets.UTF_8)),
        ),
      )
    return try {
      decodeExecutionPlan(payload)
    } catch (_: IllegalArgumentException) {
      throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError("execution plan cannot be reconstructed")
    }
  }

  private fun encodePolicies(policies: Map<String, String>): List<Map<String, Any?>> =
    policies.map { (step, identity) ->
      mapOf(Keys.STEP to step, Keys.IDENTITY to identity, Keys.SEMANTIC_DIGEST to executionPolicyDigest(identity))
    }

  private companion object {
    const val SOURCE = "durable execution plan"
  }
}

internal fun executionPolicyDigest(identity: String): String =
  MessageDigest.getInstance("SHA-256")
    .digest(JsonCodec.valueToJsonString(identity).toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
