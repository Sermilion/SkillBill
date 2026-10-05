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
        val slot = planSlot(strategy)
        val strategyId = planString(strategy, Keys.STRATEGY_ID)
        val revision = planRevision(strategy)
        val steps = planStrings(strategy, Keys.SELECTED_STEPS)
        val entryStep = planString(strategy, Keys.ENTRY_STEP)
        if (ResolvedPhaseStrategyIdentity.violation(strategyId, revision, steps, entryStep) != null) invalidPlanValue()
        ResolvedPhaseStrategyIdentity(
          slot = slot,
          strategyId = strategyId,
          semanticRevision = revision,
          steps = steps,
          entryStep = entryStep,
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
        val strategyId = planString(dispatch, Keys.STRATEGY_ID)
        val revision = planRevision(dispatch)
        if (ResolvedPhaseStrategyDispatch.violation(strategyId, revision) != null) invalidPlanValue()
        planString(dispatch, Keys.STEP) to ResolvedPhaseStrategyDispatch(planSlot(dispatch), strategyId, revision)
      },
    stepPolicyIdentities = decodePolicies(payload, Keys.STEP_POLICIES),
    resumeInterpretationIdentities = decodePolicies(payload, Keys.RESUME_INTERPRETATIONS),
    effectivePolicies =
      planObjects(payload, Keys.EFFECTIVE_POLICIES).map { policy ->
        val id = planString(policy, Keys.ID)
        val revision = planRevision(policy)
        val digest = planString(policy, Keys.SEMANTIC_DIGEST)
        if (ResolvedExecutionPolicy.violation(id, revision, digest) != null) {
          throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError("execution plan cannot be reconstructed")
        }
        ResolvedExecutionPolicy(id, revision, digest)
      },
    effectivePolicySettings =
      payload[Keys.EFFECTIVE_POLICY_SETTINGS]?.let { raw ->
        val settings = planObject(raw)
        val rawDepth = planString(settings, Keys.VALIDATION_DEPTH)
        val validationDepth =
          ValidationDepth.fromWireOrNull(rawDepth)
            ?: invalidSettings(ValidationDepth.unknownWireValueMessage(rawDepth))
        val phaseTimeoutMillis = (settings[Keys.PHASE_TIMEOUT_MILLIS] as? Number)?.toLong()
        val violation = ResolvedFeatureTaskRuntimeExecutionSettings.violation(phaseTimeoutMillis)
        if (violation != null) invalidSettings(violation)
        ResolvedFeatureTaskRuntimeExecutionSettings(validationDepth, phaseTimeoutMillis)
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

private fun invalidSettings(reason: String): Nothing =
  throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError("execution plan settings are invalid: $reason")
