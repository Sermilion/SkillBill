package skillbill.engine.featuretask.lifecycle.execution

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_RUNTIME_EXECUTION_PLAN_PREVIOUS_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseStepRecords
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as Keys

internal object ArchivedAuditExecutionPlanFixture {
  fun archive(
    fixture: ExecutionPlanAdmissionFixture,
    mode: String,
    revision: Int = 1,
  ): ByteArray {
    val prefix = if (revision == 1) "audit-mapping" else "audit-mapping-v$revision"
    return restoreDigests(fixture, resourcePayload("featuretask/$prefix-$mode.json"))
  }

  fun currentArchive(
    fixture: ExecutionPlanAdmissionFixture,
    mode: String,
  ): ByteArray {
    return restoreDigests(fixture, resourcePayload("featuretask/audit-mapping-current-$mode.json"))
  }

  fun legacyAuditPlan(
    fixture: ExecutionPlanAdmissionFixture,
    revision: Int,
  ): ByteArray {
    val current = fixture.plan
    val audit =
      fixture.strategies.registry.strategy(
        PhaseSlot.AUDIT,
        current.selectedStrategies.single {
          it.slot == PhaseSlot.AUDIT
        }.strategyId,
      )
    val planStep = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX
    val repairStep = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX
    val steps =
      if (revision == 1) {
        listOf(
          repairStep,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        )
      } else {
        audit.steps
      }
    val omitted = if (revision == 1) setOf(planStep) else emptySet<String>()
    val traversal =
      if (revision == 1) {
        current.traversal.copy(
          forwardPhaseIds = current.traversal.forwardPhaseIds - planStep,
          backwardEdges =
            current.traversal.backwardEdges.map { edge ->
              if (edge.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_REPAIR_LOOP_ID) {
                edge.copy(destinationPhaseId = repairStep)
              } else {
                edge
              }
            },
          loopOnlyPhaseIds = current.traversal.loopOnlyPhaseIds - planStep,
          loopOnlySuccessors = current.traversal.loopOnlySuccessors - planStep,
        )
      } else {
        current.traversal
      }
    val recorded =
      ResolvedPhaseExecutionPlan(
        current.definitionId,
        current.definitionSemanticRevision,
        current.selectedStrategies.map { identity ->
          if (identity.slot == PhaseSlot.AUDIT) identity.copy(semanticRevision = revision, steps = steps) else identity
        },
        current.reviewSelection,
        current.qualityGateSelection,
        traversal,
        ResolvedPhaseStepRecords(
          (current.stepPolicyIdentities - omitted).mapValues { (step, identity) ->
            if (step in steps) audit.policyFor(step).semanticIdentity(audit.strategyId, revision, step) else identity
          },
          (current.resumeInterpretationIdentities - omitted).mapValues { (step, identity) ->
            if (step in steps) "${audit.strategyId}/$revision:$step" else identity
          },
          current.stepLaunchAssignments - omitted,
        ),
        current.effectivePolicies,
        current.effectivePolicySettings,
        FEATURE_TASK_RUNTIME_EXECUTION_PLAN_PREVIOUS_CONTRACT_VERSION,
        current.qaSelection,
      )
    val supportedPolicies = FeatureTaskRuntimeEffectivePolicies.resolve(recorded, fixture.inputs)
    return fixture.codec.encode(recorded.withEffectivePolicies(supportedPolicies))
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
