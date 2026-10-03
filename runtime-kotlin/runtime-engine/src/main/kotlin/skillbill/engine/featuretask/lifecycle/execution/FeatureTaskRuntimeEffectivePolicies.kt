package skillbill.engine.featuretask.lifecycle.execution

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeAttemptBudgets
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeExecutionPlanSchemaError
import skillbill.workflow.taskruntime.model.skeleton.ResolvedExecutionPolicy
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.security.MessageDigest

private const val EFFECTIVE_POLICY_CANONICAL_BYTE_LIMIT = 65536

internal object FeatureTaskRuntimeEffectivePolicies {
  fun resolve(
    plan: ResolvedPhaseExecutionPlan,
    gate: EffectiveGatePolicyInputs,
  ): List<ResolvedExecutionPolicy> =
    listOf(
      policy("gate-commands", gate.canonicalInputs()),
      policy(
        "receipt-interpretation",
        listOf(
          FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION,
          FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION,
          "checkpoint-and-command-bound",
          "preserve-quarantined-evidence",
        ),
      ),
      policy(
        "retry-budgets",
        retryInputs(plan),
      ),
      policy(
        "resume-budgets",
        resumeInputs(plan),
      ),
      policy("acceptance-audit", listOf("stateless-full-scope", "empty-list-only", "unchanged-remaining-blocks")),
      policy("review-invalidation", listOf("generation-tombstone", "retain-review-baseline")),
      policy(
        "checkpoint-ownership",
        listOf(
          FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION,
          "same-branch-commit-per-subtask",
          "owned-head-amend",
          "prune-after-push-and-manifest-commit",
        ),
      ),
      policy("finalization", listOf("runtime-owned-commit-push", "retain-uncertain-effects", "terminal-refusal")),
    )

  fun mapStepIdentityPolicies(
    recorded: ResolvedPhaseExecutionPlan,
    mapped: ResolvedPhaseExecutionPlan,
  ): List<ResolvedExecutionPolicy> {
    val previous =
      listOf(
        policy("retry-budgets", retryInputs(recorded)),
        policy("resume-budgets", resumeInputs(recorded)),
      )
    if (previous.any { expected -> recorded.effectivePolicies.singleOrNull { it.id == expected.id } != expected }) {
      throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
    }
    val replacements =
      listOf(
        policy("retry-budgets", retryInputs(mapped)),
        policy("resume-budgets", resumeInputs(mapped)),
      )
    return recorded.effectivePolicies.map { existing -> replacements.singleOrNull { it.id == existing.id } ?: existing }
  }

  private fun retryInputs(plan: ResolvedPhaseExecutionPlan): List<Any> =
    listOf(
      FeatureTaskRuntimeAttemptBudgets.MAX_OUTPUT_GATE_RETRY_ATTEMPTS,
      FeatureTaskRuntimeAttemptBudgets.MAX_PROCESS_FAILURE_ATTEMPTS,
      FeatureTaskRuntimeBuildGateCoordinator.MAX_REPAIR_TURNS,
      FeatureTaskRuntimePhaseWorkflowDefinition.MAX_RECORD_REGENERATION_ATTEMPTS,
      plan.stepPolicyIdentities.toSortedMap(),
    )

  private fun resumeInputs(plan: ResolvedPhaseExecutionPlan): List<Any> =
    listOf(
      "new-process-failure-budget-per-invocation",
      "preserve-ordinary-attempt-attribution",
      plan.resumeInterpretationIdentities.toSortedMap(),
    )

  private fun policy(
    id: String,
    inputs: Any?,
  ): ResolvedExecutionPolicy = ResolvedExecutionPolicy(id, 1, effectivePolicyDigest(inputs))
}

internal fun effectivePolicyDigest(inputs: Any?): String {
  val encoded = JsonCodec.valueToJsonString(inputs).toByteArray(Charsets.UTF_8)
  if (encoded.size > EFFECTIVE_POLICY_CANONICAL_BYTE_LIMIT) {
    throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError(
      "effective policy inputs exceed $EFFECTIVE_POLICY_CANONICAL_BYTE_LIMIT UTF-8 bytes",
    )
  }
  return MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
}
