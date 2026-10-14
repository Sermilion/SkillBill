package skillbill.engine.featuretask.lifecycle.execution

import skillbill.contracts.JsonCodec
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as Keys

internal object ArchivedAuditExecutionPlanFixture {
  fun historicalAdoptionArchive(
    mode: String,
    revision: Int,
  ): ByteArray = resource("featuretask/serial-adoption/$mode-r$revision.json")

  fun archive(
    fixture: ExecutionPlanAdmissionFixture,
    mode: String,
    revision: Int = 1,
  ): ByteArray {
    val prefix = if (revision == 1) "audit-mapping" else "audit-mapping-v2"
    return restoreDigests(fixture, resourcePayload("featuretask/$prefix-$mode.json"))
  }

  fun currentArchive(
    fixture: ExecutionPlanAdmissionFixture,
    mode: String,
  ): ByteArray {
    val snapshot = resourcePayload("featuretask/slotbaseline/$mode/workflow-snapshot.json")
    val artifacts = JsonCodec.anyToStringAnyMap(snapshot["artifacts_json"])!!
    val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(artifacts)
    return restoreDigests(fixture, JsonCodec.anyToStringAnyMap(descriptor)!!)
  }

  private fun restoreDigests(
    fixture: ExecutionPlanAdmissionFixture,
    payload: Map<String, Any?>,
  ): ByteArray {
    val restored = payload.toMutableMap()
    val dispatch =
      (payload[Keys.DISPATCH_OWNERSHIP] as List<*>)
        .map { JsonCodec.anyToStringAnyMap(it)!! }.associateBy { it[Keys.STEP] }
    listOf(Keys.STEP_POLICIES, Keys.RESUME_INTERPRETATIONS).forEach { family ->
      restored[family] =
        (payload[family] as List<*>).map { item ->
          val policy = JsonCodec.anyToStringAnyMap(item)!!.toMutableMap()
          val step = policy[Keys.STEP] as String
          val owner = dispatch.getValue(step)
          val strategy =
            fixture.strategies.registry.strategy(
              PhaseSlot.entries.single { it.wireValue == owner[Keys.SLOT] },
              owner[Keys.STRATEGY_ID] as String,
            )
          val identity =
            if (family == Keys.STEP_POLICIES) {
              strategy.policyFor(step).semanticIdentity(
                strategy.strategyId,
                (owner[Keys.SEMANTIC_REVISION] as Number).toInt(),
                step,
              )
            } else {
              policy[Keys.IDENTITY] as String
            }
          policy[Keys.IDENTITY] = identity
          policy[Keys.SEMANTIC_DIGEST] = executionPolicyDigest(identity)
          policy
        }
    }
    restored[Keys.EFFECTIVE_POLICIES] =
      (payload[Keys.EFFECTIVE_POLICIES] as List<*>).map { item ->
        JsonCodec.anyToStringAnyMap(item)!! + (Keys.SEMANTIC_DIGEST to "0".repeat(64))
      }
    val plan = fixture.codec.decode(JsonCodec.mapToJsonString(restored).encodeToByteArray())
    val policies = FeatureTaskRuntimeEffectivePolicies.resolve(plan, fixture.inputs)
    return fixture.codec.encode(plan.withEffectivePolicies(policies))
  }

  private fun resourcePayload(path: String): Map<String, Any?> =
    JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(resource(path).decodeToString()))!!

  private fun resource(path: String): ByteArray =
    requireNotNull(javaClass.classLoader.getResourceAsStream(path)).use { it.readBytes() }
}
