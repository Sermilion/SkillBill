package skillbill.infrastructure.contracts.workflow.goal

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_ID
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.infrastructure.contracts.locator.GoalPlanningPreparationSchemaPaths
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalPlanningPreparationSchemaValidatorTest {
  @Test
  fun `both normalized envelope variants validate`() {
    GoalPlanningPreparationSchemaValidator.validate(sharedEnvelope(), "goal-1")
    GoalPlanningPreparationSchemaValidator.validate(planEnvelope(), "goal-1#1")
    GoalPlanningPreparationSchemaValidator.validate(
      sharedEnvelope() + ("identity" to identity("0AC-11")),
      "0AC-11",
    )
  }

  @Test
  fun `normalized envelopes reject missing fields additional properties and wrong discriminators`() {
    listOf(
      sharedEnvelope() - "identity",
      sharedEnvelope() + ("unexpected" to true),
      planEnvelope() + ("record_type" to "shared_preplan"),
    ).forEach { envelope ->
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        GoalPlanningPreparationSchemaValidator.validate(envelope, "fixture")
      }
    }
  }

  @Test
  fun `normalized envelopes reject incompatible version pending status malformed hashes and empty payload`() {
    listOf(
      sharedEnvelope() + ("contract_version" to "0.1"),
      sharedEnvelope() + ("preparation_status" to "pending"),
      sharedEnvelope() + ("payload_sha256" to "not-a-hash"),
      planEnvelope() + ("plan_payload" to ""),
    ).forEach { envelope ->
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        GoalPlanningPreparationSchemaValidator.validate(envelope, "fixture")
      }
    }
  }

  @Test
  fun `unsupported provenance fails without destructive reset guidance`() {
    val legacyProvenance = provenance() + ("phase_output_contract_version" to "0.2")
    val error =
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        GoalPlanningPreparationSchemaValidator.validate(
          sharedEnvelope() + ("provenance" to legacyProvenance),
          "goal-1",
        )
      }

    assertContains(error.message.orEmpty(), "contract validation failed")
    assertEquals(false, error.message.orEmpty().contains("--hard"))
  }

  private fun sharedEnvelope(): Map<String, Any?> =
    linkedMapOf(
      "contract_version" to "0.2",
      "record_type" to "shared_preplan",
      "identity" to identity(),
      "preparation_status" to "prepared",
      "provenance" to provenance(),
      "payload_sha256" to HASH,
      "preplan_payload" to payload("preplan"),
    )

  private fun planEnvelope(): Map<String, Any?> =
    linkedMapOf(
      "contract_version" to "0.2",
      "record_type" to "subtask_plan",
      "identity" to identity(),
      "subtask_id" to 1,
      "manifest_order" to 0,
      "governed_sub_spec_path" to ".feature-specs/SKILL-128/spec_subtask_1.md",
      "sub_spec_hash" to HASH,
      "preparation_status" to "prepared",
      "provenance" to provenance(),
      "payload_sha256" to HASH,
      "plan_payload" to payload("plan"),
    )

  private fun payload(phase: String): String =
    """{"contract_version":"0.7","phase_id":"$phase","status":"completed",
    "summary":"planning", "produced_outputs":{"value":"planning prose"}}"""

  private fun identity(normalizedIssueKey: String = "SKILL-128") =
    linkedMapOf(
      "parent_goal_workflow_id" to "goal-1",
      "normalized_issue_key" to normalizedIssueKey,
      "repository_identity" to "repo-root-realpath-v1:/repository",
    )

  private fun provenance() =
    linkedMapOf(
      "parent_spec_hash" to HASH,
      "decomposition_manifest_hash" to HASH,
      "planning_contract_id" to GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID,
      "planning_contract_version" to GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
      "phase_output_contract_id" to FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_ID,
      "phase_output_contract_version" to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
    )

  private companion object {
    const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
}
