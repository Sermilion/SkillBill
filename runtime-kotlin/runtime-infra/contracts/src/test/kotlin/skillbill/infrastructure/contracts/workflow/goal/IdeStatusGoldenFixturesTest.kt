package skillbill.infrastructure.contracts.workflow.goal

import skillbill.contracts.workflow.identity.status.IDE_STATUS_CONTRACT_VERSION
import kotlin.test.Test

class IdeStatusGoldenFixturesTest {
  @Test
  fun `standalone phase with workflow correlation preserves decimal execution identity`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "workflow_id" to "workflow-411",
        "execution_scope" to "standalone_phase",
        "execution_id" to "execution-411",
        "status_store_id" to "store-1",
        "run_sequence" to "90071992547409931234567890",
        "status_revision" to "90071992547409931234567891",
        "invocation_id" to "invocation-411",
        "phase_id" to "review",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "review", "label" to "Review"),
        "current_activity" to "Checking findings",
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "Standalone review is active.",
      ),
      "golden-standalone-phase",
    )
  }

  @Test
  fun `feature-task-runtime golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-148",
        "workflow_id" to "wfl-runtime-1",
        "workflow_family" to "feature-task-runtime",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "implement", "label" to "Implement"),
        "progress" to linkedMapOf("completed" to 3, "total" to 9),
        "started_at" to "2026-08-06T08:00:00Z",
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "feature-task-runtime SKILL-148 is active on Implement.",
      ),
      "golden-runtime",
    )
  }

  @Test
  fun `feature-task-runtime current-model golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-183",
        "workflow_id" to "wfl-runtime-2",
        "workflow_family" to "feature-task-runtime",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "implement", "label" to "Implement"),
        "progress" to linkedMapOf("completed" to 3, "total" to 9),
        "started_at" to "2026-08-06T08:00:00Z",
        "current_model" to linkedMapOf("model" to "claude-opus-4-8", "effort" to "high"),
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "feature-task-runtime SKILL-183 is active on Implement.",
      ),
      "golden-runtime-current-model",
    )
  }

  @Test
  fun `feature-task-runtime current-phase-execution golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-184",
        "workflow_id" to "wfl-runtime-3",
        "workflow_family" to "feature-task-runtime",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "audit", "label" to "Completeness Audit"),
        "progress" to linkedMapOf("completed" to 3, "total" to 10),
        "started_at" to "2026-08-06T08:00:00Z",
        "current_phase_execution" to
          linkedMapOf(
            "phase_id" to "audit",
            "kind" to "semantic_loop",
            "count" to 2,
          ),
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "feature-task-runtime SKILL-184 is active on Completeness Audit.",
      ),
      "golden-runtime-current-phase-execution",
    )
  }

  @Test
  fun `feature-goal current-model without effort golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-183",
        "workflow_id" to "goal-6",
        "workflow_family" to "feature-goal",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "planning", "label" to "Planning"),
        "progress" to linkedMapOf("completed" to 1, "total" to 3),
        "started_at" to "2026-08-06T08:00:00Z",
        "current_subtask" to linkedMapOf("id" to "2"),
        "current_model" to
          linkedMapOf(
            "model" to "claude-opus-4-8[effort=high]",
            "phase_id" to "implement",
          ),
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-183 is active on Planning.",
      ),
      "golden-goal-current-model",
    )
  }

  @Test
  fun `feature-verify golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-148",
        "workflow_id" to "wfl-verify-1",
        "workflow_family" to "feature-verify",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "verify", "label" to "Verify"),
        "progress" to linkedMapOf("completed" to 1, "total" to 4),
        "started_at" to "2026-08-06T08:00:00Z",
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "feature-verify SKILL-148 is active on Verify.",
      ),
      "golden-verify",
    )
  }

  @Test
  fun `feature-goal golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-148",
        "workflow_id" to "goal-1",
        "workflow_family" to "feature-goal",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "implement", "label" to "Implement"),
        "progress" to linkedMapOf("completed" to 1, "total" to 3),
        "started_at" to "2026-08-06T08:00:00Z",
        "current_subtask" to linkedMapOf("id" to "2", "started_at" to "2026-08-06T09:00:00Z"),
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-148 is active on Implement.",
      ),
      "golden-goal",
    )
  }

  @Test
  fun `feature-goal mid-planning golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-165",
        "workflow_id" to "goal-2",
        "workflow_family" to "feature-goal",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "planning", "label" to "Planning"),
        "progress" to linkedMapOf("completed" to 0, "total" to 5),
        "started_at" to "2026-08-06T08:00:00Z",
        "planning" to
          linkedMapOf(
            "state" to "partially_planned",
            "shared_preplan_prepared" to true,
            "planned_subtask_count" to 2,
            "total_subtask_count" to 5,
            "current_planning_subtask_id" to "3",
            "reason" to "Planning subtask 3.",
          ),
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-165 is planning subtasks (2/5 planned).",
      ),
      "golden-goal-planning",
    )
  }

  @Test
  fun `feature-goal concurrent-planning-wave golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-230",
        "workflow_id" to "goal-wave",
        "workflow_family" to "feature-goal",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "planning", "label" to "Planning"),
        "progress" to linkedMapOf("completed" to 0, "total" to 8),
        "started_at" to "2026-08-06T08:00:00Z",
        "planning" to
          linkedMapOf(
            "state" to "partially_planned",
            "shared_preplan_prepared" to true,
            "planned_subtask_count" to 1,
            "total_subtask_count" to 8,
            "current_planning_subtask_id" to "2",
            "planning_wave_subtask_ids" to listOf("2", "3", "4", "5", "6"),
            "reason" to "Planning subtask 2.",
          ),
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to
          "Goal SKILL-230 is planning subtasks (1/8 planned). 5 subtasks are being planned now.",
      ),
      "golden-goal-planning-wave",
    )
  }

  @Test
  fun `feature-goal pause-requested-not-consumed golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-168",
        "workflow_id" to "goal-3",
        "workflow_family" to "feature-goal",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "implement", "label" to "Implement"),
        "progress" to linkedMapOf("completed" to 1, "total" to 3),
        "started_at" to "2026-08-06T08:00:00Z",
        "pause_requested" to true,
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-168 is active on Implement.",
      ),
      "golden-goal-pause-requested",
    )
  }

  @Test
  fun `feature-goal pause-consumed-with-timestamp golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-168",
        "workflow_id" to "goal-4",
        "workflow_family" to "feature-goal",
        "lifecycle_state" to "paused",
        "current_step" to linkedMapOf("id" to "implement", "label" to "Implement"),
        "progress" to linkedMapOf("completed" to 1, "total" to 3),
        "started_at" to "2026-08-06T08:00:00Z",
        "paused_at" to "2026-08-06T09:45:00Z",
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-168 is paused.",
      ),
      "golden-goal-pause-consumed",
    )
  }

  @Test
  fun `feature-goal lease-expired-without-timestamp golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-168",
        "workflow_id" to "goal-5",
        "workflow_family" to "feature-goal",
        "lifecycle_state" to "idle",
        "current_step" to linkedMapOf("id" to "implement", "label" to "Implement"),
        "progress" to linkedMapOf("completed" to 1, "total" to 3),
        "started_at" to "2026-08-06T08:00:00Z",
        "updated_at" to "2026-08-06T09:50:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-168 is idle.",
      ),
      "golden-goal-lease-expired",
    )
  }

  @Test
  fun `typed problem goldens validate`() {
    listOf(
      "missing_repository_identity",
      "absent_database",
      "no_matching_work",
      "incompatible_record",
      "invalid_repository_input",
      "schema_incompatible",
    ).forEach { code ->
      IdeStatusSchemaValidator.validate(
        linkedMapOf(
          "contract_version" to IDE_STATUS_CONTRACT_VERSION,
          "repository_identity" to "repo-root-realpath-v1:/repo",
          "lifecycle_state" to "idle",
          "current_step" to linkedMapOf("id" to "none", "label" to "Unavailable"),
          "updated_at" to "2026-08-06T10:00:00Z",
          "freshness" to "unknown",
          "summary" to "Unavailable: $code",
          "problem" to linkedMapOf("code" to code, "message" to "Unavailable: $code"),
        ),
        "golden-problem-$code",
      )
    }
  }

  @Test
  fun `feature-goal blocked with operator pause reason golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-228",
        "workflow_id" to "goal-blocked-operator",
        "workflow_family" to "feature-goal",
        "lifecycle_state" to "blocked",
        "current_step" to linkedMapOf("id" to "validate", "label" to "Validate"),
        "progress" to linkedMapOf("completed" to 1, "total" to 3),
        "started_at" to "2026-08-06T08:00:00Z",
        "current_subtask" to linkedMapOf("id" to "2"),
        "pause_reason" to
          linkedMapOf(
            "code" to "awaiting_operator_decision",
            "label" to "Configure GITHUB_REGISTRY_AUTH then run npm ci:safe",
          ),
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-228 is blocked: Configure GITHUB_REGISTRY_AUTH then run npm ci:safe",
      ),
      "golden-goal-blocked-operator-pause",
    )
  }

  @Test
  fun `agent activity pair golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-225",
        "workflow_id" to "wfl-runtime-activity",
        "workflow_family" to "feature-task-runtime",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "implement", "label" to "Implement"),
        "last_agent_activity_at" to "2026-08-30T10:15:00Z",
        "last_agent_activity_label" to "stdout",
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "feature-task-runtime SKILL-225 is active on Implement.",
      ),
      "golden-runtime-agent-activity",
    )
  }
}
