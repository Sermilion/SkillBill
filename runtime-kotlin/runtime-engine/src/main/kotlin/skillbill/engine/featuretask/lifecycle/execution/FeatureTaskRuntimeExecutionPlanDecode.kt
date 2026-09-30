package skillbill.engine.featuretask.lifecycle.execution

import skillbill.contracts.JsonCodec
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeExecutionPlanSchemaError
import skillbill.error.featuretask.UnsupportedFeatureTaskRuntimeExecutionPlanError
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedExecutionPolicy
import skillbill.workflow.taskruntime.model.skeleton.ResolvedFeatureTaskRuntimeExecutionSettings
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseStrategyDispatch
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseStrategyIdentity
import skillbill.workflow.taskruntime.model.skeleton.RuntimeReviewSelection
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as Keys

internal fun decodeExecutionPlan(payload: Map<String, Any?>): ResolvedPhaseExecutionPlan {
  val definition = planObject(payload[Keys.DEFINITION])
  return ResolvedPhaseExecutionPlan(
    definitionId = planString(definition, Keys.ID),
    definitionSemanticRevision = planRevision(definition),
    selectedStrategies =
      planObjects(payload, Keys.SELECTED_STRATEGIES).map { strategy ->
        ResolvedPhaseStrategyIdentity(
          slot = planSlot(strategy),
          strategyId = planString(strategy, Keys.STRATEGY_ID),
          semanticRevision = planRevision(strategy),
          steps = planStrings(strategy, Keys.SELECTED_STEPS),
          entryStep = planString(strategy, Keys.ENTRY_STEP),
        )
      }.sortedBy { it.slot.ordinal },
    reviewSelection =
      payload[Keys.REVIEW_SELECTION]?.let { value ->
        RuntimeReviewSelection.entries.singleOrNull { it.wireValue == value } ?: invalidPlanValue()
      },
    qualityGateSelection =
      payload[Keys.QUALITY_GATE_SELECTION]?.let { value ->
        FeatureTaskRuntimeQualityGateSelection.entries.singleOrNull { it.wireValue == value } ?: invalidPlanValue()
      },
    traversal = decodeExecutionPlanTraversal(planObject(payload[Keys.TRAVERSAL])),
    dispatchStrategyByStep =
      planObjects(payload, Keys.DISPATCH_OWNERSHIP).associate { dispatch ->
        planString(dispatch, Keys.STEP) to
          ResolvedPhaseStrategyDispatch(
            slot = planSlot(dispatch),
            strategyId = planString(dispatch, Keys.STRATEGY_ID),
            semanticRevision = planRevision(dispatch),
          )
      },
    stepPolicyIdentities = decodePolicies(payload, Keys.STEP_POLICIES),
    resumeInterpretationIdentities = decodePolicies(payload, Keys.RESUME_INTERPRETATIONS),
    effectivePolicies =
      planObjects(payload, Keys.EFFECTIVE_POLICIES).map { policy ->
        ResolvedExecutionPolicy(
          planString(policy, Keys.ID),
          planRevision(policy),
          planString(policy, Keys.SEMANTIC_DIGEST),
        )
      },
    effectivePolicySettings =
      payload[Keys.EFFECTIVE_POLICY_SETTINGS]?.let { raw ->
        val settings = planObject(raw)
        try {
          ResolvedFeatureTaskRuntimeExecutionSettings(
            validationDepth =
              ValidationDepth.fromWire(
                planString(settings, Keys.VALIDATION_DEPTH),
              ),
            phaseTimeoutMillis =
              (settings[Keys.PHASE_TIMEOUT_MILLIS] as? Number)?.toLong(),
          )
        } catch (error: IllegalArgumentException) {
          throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError(
            "execution plan settings are invalid: ${error.message}",
          ).also { it.addSuppressed(error) }
        }
      },
  )
}

private fun decodePolicies(
  payload: Map<String, Any?>,
  key: String,
): Map<String, String> =
  planObjects(payload, key).associate { policy ->
    val identity = planString(policy, Keys.IDENTITY)
    if (executionPolicyDigest(identity) != planString(policy, Keys.SEMANTIC_DIGEST)) invalidPlanValue()
    planString(policy, Keys.STEP) to identity
  }

private fun planSlot(payload: Map<String, Any?>): PhaseSlot =
  PhaseSlot.entries.singleOrNull { it.wireValue == payload[Keys.SLOT] }
    ?: throw UnsupportedFeatureTaskRuntimeExecutionPlanError()

private fun planRevision(payload: Map<String, Any?>): Int =
  (payload[Keys.SEMANTIC_REVISION] as? Number)?.toInt() ?: invalidPlanValue()

internal fun planString(
  payload: Map<String, Any?>,
  key: String,
): String = payload[key] as? String ?: invalidPlanValue()

internal fun planObject(value: Any?): Map<String, Any?> = JsonCodec.anyToStringAnyMap(value) ?: invalidPlanValue()

internal fun planObjects(
  payload: Map<String, Any?>,
  key: String,
): List<Map<String, Any?>> = (payload[key] as? List<*>)?.map(::planObject) ?: invalidPlanValue()

internal fun planStrings(
  payload: Map<String, Any?>,
  key: String,
): List<String> = (payload[key] as? List<*>)?.map { it as? String ?: invalidPlanValue() } ?: invalidPlanValue()

private fun invalidPlanValue(): Nothing =
  throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError("execution plan contains an invalid semantic value or digest")
