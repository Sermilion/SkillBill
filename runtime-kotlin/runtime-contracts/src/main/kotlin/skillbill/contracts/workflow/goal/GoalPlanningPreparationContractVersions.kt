package skillbill.contracts.workflow.goal

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_SUPPORTED_PHASE_OUTPUT_MIGRATIONS

const val GOAL_SHARED_PREPLAN_DISCARDED_PAYLOAD: String = "shared-preplan-discarded"

const val GOAL_PLANNING_PREPARATION_CONTRACT_VERSION: String = "0.2"

const val GOAL_PLANNING_PREPARATION_HISTORICAL_PHASE_OUTPUT_VERSION: String = "0.6"

const val GOAL_PLANNING_PREPARATION_SCHEMA_ID: String =
  "https://skill-bill.dev/contracts/goal-planning-preparation-schema.yaml"

data class GoalPlanningPreparationMigrationPath(
  val sourcePreparationVersion: String,
  val sourcePlanningVersion: String,
  val sourcePhaseOutputVersion: String,
  val targetPreparationVersion: String,
  val targetPlanningVersion: String,
  val targetPhaseOutputVersion: String,
)

val GOAL_PLANNING_PREPARATION_MIGRATION_PATHS: List<GoalPlanningPreparationMigrationPath> =
  FEATURE_TASK_RUNTIME_SUPPORTED_PHASE_OUTPUT_MIGRATIONS.map { (source, target) ->
    GoalPlanningPreparationMigrationPath(
      sourcePreparationVersion = GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
      sourcePlanningVersion = GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
      sourcePhaseOutputVersion = source,
      targetPreparationVersion = GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
      targetPlanningVersion = GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
      targetPhaseOutputVersion = target,
    )
  }

object GoalPlanningPreparationPayloadKeys {
  const val RECORD_TYPE = "record_type"
  const val IDENTITY = "identity"
  const val PARENT_GOAL_WORKFLOW_ID = "parent_goal_workflow_id"
  const val NORMALIZED_ISSUE_KEY = "normalized_issue_key"
  const val REPOSITORY_IDENTITY = "repository_identity"
  const val PREPARATION_STATUS = "preparation_status"
  const val PAYLOAD_SHA256 = "payload_sha256"
  const val PREPLAN_PAYLOAD = "preplan_payload"
  const val PLAN_PAYLOAD = "plan_payload"
  const val REPAIR_EVIDENCE = "repair_evidence"
  const val MANIFEST_ORDER = "manifest_order"
  const val GOVERNED_SUB_SPEC_PATH = "governed_sub_spec_path"
  const val SUB_SPEC_HASH = "sub_spec_hash"
  const val PARENT_SPEC_HASH = "parent_spec_hash"
  const val DECOMPOSITION_MANIFEST_HASH = "decomposition_manifest_hash"
  const val PREPLAN_PAYLOAD_SHA256 = "preplan_payload_sha256"
  const val PLAN_PAYLOAD_SHA256 = "plan_payload_sha256"
  const val SOURCE_KIND = "source_kind"
  const val PROVENANCE = "provenance"
  const val PLANNING_CONTRACT_ID = "planning_contract_id"
  const val PLANNING_CONTRACT_VERSION = "planning_contract_version"
  const val PHASE_OUTPUT_CONTRACT_ID = "phase_output_contract_id"
  const val PHASE_OUTPUT_CONTRACT_VERSION = "phase_output_contract_version"
}
