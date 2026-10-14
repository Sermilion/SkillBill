package skillbill.infrastructure.contracts.workflow.featuretask

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_ADOPTION_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.text.sha256HexUtf8
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asCheckpointIdentitiesArtifactEntry
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.time.Instant
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys as DecompositionKeys
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys
import skillbill.contracts.workflow.goal.GoalPlanningPreparationPayloadKeys as GoalKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as PlanKeys

internal fun validSerialRepairAdoption(): Map<String, Any?> = SerialRepairAdoptionFixture().receipt()

private class SerialRepairAdoptionFixture {
  val validator = FeatureTaskRuntimeExecutionPlanSchemaValidator()
  val bytes =
    requireNotNull(
      validator.javaClass.classLoader.getResourceAsStream("featuretask/serial-adoption/standalone-r3.json"),
    ).use { it.readBytes() }
  val descriptor = validator.read(bytes, "archived-standalone")
  val policies =
    (descriptor[PlanKeys.STEP_POLICIES] as List<*>).associate { raw ->
      val policy = raw as Map<*, *>
      policy[PlanKeys.STEP] as String to policy[PlanKeys.IDENTITY] as String
    }
  val criteria = listOf("AC-001. Preserve repair attempts.")
  val source = "# Test spec\n\n## Acceptance Criteria\n\n1. ${criteria.single()}\n"
  val path = ".feature-specs/SKILL-426/spec.md"
  val checkpoint = "refs/skill-bill/checkpoints/SKILL-426/standalone/0"
  val commit = "a".repeat(40)
  val tree = "d".repeat(64)
  val instant = Instant.parse("2026-10-14T09:00:00Z")
  val output =
    JsonCodec.valueToJsonString(
      mapOf(
        SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        SharedPayloadKeys.PHASE_ID to "audit",
        SharedPayloadKeys.STATUS to WorkflowStepStatus.COMPLETED.wireValue,
        SharedPayloadKeys.SUMMARY to "Initial source audit",
        SharedPayloadKeys.PRODUCED_OUTPUTS to
          mapOf(
            SharedPayloadKeys.VALUE to "Source repair remains unresolved.",
            Keys.TREE_DIGEST to tree,
            Keys.CHECKPOINT_ID to checkpoint,
          ),
      ),
    )
  val original =
    mapOf(
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(descriptor),
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
        mapOf(
          "audit" to
            FeatureTaskRuntimePhaseRecord(
              phaseId = "audit",
              status = WorkflowStepStatus.COMPLETED,
              attemptCount = 1,
              startedAt = instant,
              finishedAt = instant,
              resolvedAgentId = "codex",
              outputArtifact = output,
            ).asWorkflowArtifactEntry(),
        ),
      ),
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RUN_INVARIANTS.entry(
        FeatureTaskRuntimeRunInvariants(
          specReference = "/repo/$path",
          acceptanceCriteria = criteria,
          mandatesAndOverrides = emptyList(),
        ).asWorkflowArtifactEntry(),
      ),
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES.entry(
        listOf(
          FeatureTaskRuntimeCheckpointIdentity(
            0, "SKILL-426", "standalone", checkpoint, "feature/SKILL-426", "audit", 0,
            "a".repeat(64), 1, commit, instant.toString(),
          ),
        ).asCheckpointIdentitiesArtifactEntry(),
      ),
    )
  val originalDigest = serialFixtureDigest(original)
  fun receipt(): Map<String, Any?> = mapOf(
    Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_REPAIR_ADOPTION_CONTRACT_VERSION,
    Keys.COMPOSITION to "standalone",
    Keys.SEMANTIC_REVISION to 3,
    Keys.DESCRIPTOR_DIGEST to serialFixtureDigest(descriptor),
    Keys.POLICY_DIGEST to serialFixtureDigest(policies),
    Keys.SKELETON_DIGEST to sha256HexUtf8(validator.canonicalize(bytes, "archive").toString(Charsets.UTF_8)),
    Keys.DECLARED_CONTRACT_VERSIONS to mapOf(
      Keys.CONTRACT_VERSION to descriptor[PlanKeys.CONTRACT_VERSION],
      Keys.PHASE_OUTPUT_CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
    ),
    Keys.CATALOG_ID to serialFixtureDigest(criteria),
    Keys.SPEC_DIGEST to sha256HexUtf8(source),
    Keys.EXECUTION_ID to "execution",
    Keys.TREE_DIGEST to tree,
    Keys.CHECKPOINT_ID to checkpoint,
    Keys.STOPPED_OWNER_GENERATION to 0,
    Keys.STOPPED_OWNER_EVIDENCE_REF to "ownership_absent:$originalDigest",
    Keys.PRISTINE_HISTORY_DIGEST to originalDigest,
    Keys.RETAINED_EVIDENCE_REFS to listOf("original_artifacts:$originalDigest", checkpoint),
    Keys.PROSPECTIVE_BUDGET to true,
    Keys.STRUCTURED_DISCOVERY_REQUIRED to true,
    Keys.ORIGINAL_ARTIFACTS to original,
    Keys.STANDALONE_SOURCE_PROOF to mapOf(
      GoalKeys.REPOSITORY_IDENTITY to "${FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX}/repo",
      GoalKeys.NORMALIZED_ISSUE_KEY to "SKILL-426",
      DecompositionKeys.SPEC_PATH to path,
      Keys.CHECKPOINT_COMMIT to commit,
      Keys.SOURCE_TEXT to source,
    ),
  )
}

private fun serialFixtureDigest(value: Any): String = sha256HexUtf8(JsonCodec.valueToJsonString(value))
