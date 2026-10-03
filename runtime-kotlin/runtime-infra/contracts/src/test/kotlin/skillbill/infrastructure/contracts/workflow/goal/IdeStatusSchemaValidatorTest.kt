package skillbill.infrastructure.contracts.workflow.goal

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import skillbill.contracts.workflow.identity.status.GOAL_PLANNING_WAVE_CAP
import skillbill.contracts.workflow.identity.status.IDE_STATUS_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.contracts.locator.IdeStatusSchemaPaths

class IdeStatusSchemaValidatorTest {
  @Test
  fun `valid idle snapshot passes`() {
    IdeStatusSchemaValidator.validate(validIdleSnapshot(), "test-idle")
  }

  @Test
  fun `valid active feature-goal snapshot passes`() {
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
        "started_at" to "2026-08-06T10:00:00Z",
        "current_subtask" to linkedMapOf("id" to "1", "started_at" to "2026-08-06T10:05:00Z"),
        "updated_at" to "2026-08-06T10:10:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-148 is active on implement.",
      ),
      "test-goal",
    )
  }

  @Test
  fun `unknown lifecycle state fails loudly with typed error`() {
    val malformed = validIdleSnapshot().toMutableMap()
    malformed["lifecycle_state"] = "exploded"
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(malformed, "test-bad-lifecycle")
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
  }

  @Test
  fun `unknown problem code fails loudly with typed error`() {
    val malformed = validIdleSnapshot().toMutableMap()
    malformed["problem"] =
      linkedMapOf(
        "code" to "not_a_code",
        "message" to "bad",
      )
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(malformed, "test-bad-problem")
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
  }

  @Test
  fun `wrong contract version fails loudly with typed error`() {
    val malformed = validIdleSnapshot().toMutableMap()
    malformed["contract_version"] = "9.9"
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(malformed, "test-bad-version")
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
  }

  @Test
  fun `unknown additional property fails loudly with typed error`() {
    val malformed = validIdleSnapshot().toMutableMap()
    malformed["sqlite_row"] = "nope"
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(malformed, "test-additional-property")
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
  }

  @Test
  fun `malformed nested current_step fails loudly with typed error`() {
    val malformed = validIdleSnapshot().toMutableMap()
    malformed["current_step"] = linkedMapOf("id" to "idle")
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(malformed, "test-malformed-step")
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
  }

  @Test
  fun `goal snapshot with optional subtask active duration fields passes`() {
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
        "started_at" to "2026-08-06T10:00:00Z",
        "current_subtask" to
          linkedMapOf(
            "id" to "1",
            "started_at" to "2026-08-06T10:05:00Z",
            "active_duration_ms" to 45_000,
            "active_duration_as_of" to "2026-08-06T10:10:00Z",
          ),
        "updated_at" to "2026-08-06T10:10:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-148 is active on implement.",
      ),
      "test-subtask-active-duration",
    )
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
        "started_at" to "2026-08-06T10:00:00Z",
        "current_subtask" to linkedMapOf("id" to "1", "started_at" to "2026-08-06T10:05:00Z"),
        "updated_at" to "2026-08-06T10:10:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-148 is active on implement.",
      ),
      "test-subtask-without-active-duration",
    )
  }

  @Test
  fun `goal snapshot with a fully populated planning object passes`() {
    IdeStatusSchemaValidator.validate(
      goalSnapshotWithPlanning(
        linkedMapOf(
          "state" to "partially_planned",
          "shared_preplan_prepared" to true,
          "planned_subtask_count" to 2,
          "total_subtask_count" to 5,
          "current_planning_subtask_id" to "3",
          "reason" to "Planning subtask 3.",
        ),
      ),
      "test-planning-valid",
    )
  }

  @Test
  fun `planning wave subtask ids accept a bounded set and reject every out-of-contract shape`() {
    IdeStatusSchemaValidator.validate(
      goalSnapshotWithPlanning(
        linkedMapOf(
          "state" to "partially_planned",
          "shared_preplan_prepared" to true,
          "planned_subtask_count" to 2,
          "total_subtask_count" to 8,
          "current_planning_subtask_id" to "3",
          "planning_wave_subtask_ids" to listOf("3", "4", "5", "6", "7"),
        ),
      ),
      "test-planning-wave-valid",
    )
    val rejected =
      mapOf(
        "not-an-array" to "3",
        "empty" to emptyList<String>(),
        "duplicate" to listOf("3", "3"),
        "over-cap" to (1..GOAL_PLANNING_WAVE_CAP + 1).map(Int::toString),
      )
    rejected.forEach { (label, wave) ->
      assertFailsWith<SkillBillRuntimeException>("planning_wave_subtask_ids accepted $label") {
        IdeStatusSchemaValidator.validate(
          goalSnapshotWithPlanning(
            linkedMapOf(
              "state" to "partially_planned",
              "shared_preplan_prepared" to true,
              "planned_subtask_count" to 2,
              "total_subtask_count" to 8,
              "planning_wave_subtask_ids" to wave,
            ),
          ),
          "test-planning-wave-$label",
        )
      }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
    }
  }

  @Test
  fun `goal snapshot with only required planning properties passes`() {
    IdeStatusSchemaValidator.validate(
      goalSnapshotWithPlanning(
        linkedMapOf(
          "state" to "not_started",
          "shared_preplan_prepared" to false,
          "planned_subtask_count" to 0,
          "total_subtask_count" to 0,
        ),
      ),
      "test-planning-minimal",
    )
  }

  @Test
  fun `unknown planning property fails loudly with typed error`() {
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(
        goalSnapshotWithPlanning(
          linkedMapOf(
            "state" to "preplanned",
            "shared_preplan_prepared" to true,
            "planned_subtask_count" to 1,
            "total_subtask_count" to 4,
            "planning_notes" to "nope",
          ),
        ),
        "test-planning-unknown-property",
      )
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
  }

  @Test
  fun `negative planning counts fail loudly with typed error`() {
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(
        goalSnapshotWithPlanning(
          linkedMapOf(
            "state" to "preplanned",
            "shared_preplan_prepared" to true,
            "planned_subtask_count" to -1,
            "total_subtask_count" to 4,
          ),
        ),
        "test-planning-negative-planned",
      )
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(
        goalSnapshotWithPlanning(
          linkedMapOf(
            "state" to "preplanned",
            "shared_preplan_prepared" to true,
            "planned_subtask_count" to 1,
            "total_subtask_count" to -4,
          ),
        ),
        "test-planning-negative-total",
      )
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
  }

  @Test
  fun `invalid planning state fails loudly with typed error`() {
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(
        goalSnapshotWithPlanning(
          linkedMapOf(
            "state" to "half_planned",
            "shared_preplan_prepared" to true,
            "planned_subtask_count" to 1,
            "total_subtask_count" to 4,
          ),
        ),
        "test-planning-bad-state",
      )
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
  }

  @Test
  fun `planning object missing a required property fails loudly with typed error`() {
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(
        goalSnapshotWithPlanning(
          linkedMapOf(
            "state" to "preplanned",
            "planned_subtask_count" to 1,
            "total_subtask_count" to 4,
          ),
        ),
        "test-planning-missing-required",
      )
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
  }

  @Test
  fun `goal snapshot carrying both pause signals passes`() {
    val snapshot = validGoalSnapshot()
    snapshot["pause_requested"] = true
    snapshot["paused_at"] = "2026-08-06T10:08:00Z"
    IdeStatusSchemaValidator.validate(snapshot, "test-pause-signals-present")
  }

  @Test
  fun `an otherwise identical goal snapshot carrying neither pause signal passes`() {
    IdeStatusSchemaValidator.validate(validGoalSnapshot(), "test-pause-signals-absent")
  }

  @Test
  fun `blank paused_at fails loudly with typed error`() {
    val malformed = validGoalSnapshot()
    malformed["paused_at"] = ""
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(malformed, "test-blank-paused-at")
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
  }

  @Test
  fun `neither pause signal is in the schema required list`() {
    val required = schemaRequiredNames()
    assertFalse(required.contains("pause_requested"), "pause_requested must stay optional; required=$required")
    assertFalse(required.contains("paused_at"), "paused_at must stay optional; required=$required")
  }

  @Test
  fun `valid current_phase_execution object passes and remains optional`() {
    val snapshot = validGoalSnapshot()
    snapshot["current_phase_execution"] =
      linkedMapOf(
        "phase_id" to "review",
        "kind" to "pass",
        "count" to 3,
      )
    IdeStatusSchemaValidator.validate(snapshot, "test-current-phase-execution-valid")
    IdeStatusSchemaValidator.validate(validGoalSnapshot(), "test-current-phase-execution-absent")
  }

  @Test
  fun `malformed current_phase_execution fails loudly with typed error`() {
    val badKind = validGoalSnapshot()
    badKind["current_phase_execution"] =
      linkedMapOf(
        "phase_id" to "audit",
        "kind" to "loop",
        "count" to 1,
      )
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(badKind, "test-current-phase-execution-bad-kind")
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
    val zeroCount = validGoalSnapshot()
    zeroCount["current_phase_execution"] =
      linkedMapOf(
        "phase_id" to "audit",
        "kind" to "pass",
        "count" to 0,
      )
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(zeroCount, "test-current-phase-execution-zero-count")
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
    val unknownProperty = validGoalSnapshot()
    unknownProperty["current_phase_execution"] =
      linkedMapOf(
        "phase_id" to "audit",
        "kind" to "pass",
        "count" to 1,
        "loop_id" to "audit_gap",
      )
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(unknownProperty, "test-current-phase-execution-unknown-property")
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
    val totalOnPass = validGoalSnapshot()
    totalOnPass["current_phase_execution"] =
      linkedMapOf(
        "phase_id" to "review",
        "kind" to "pass",
        "count" to 2,
        "total" to 3,
      )
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(totalOnPass, "test-current-phase-execution-total-on-pass")
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
    val totalOnSemanticLoop = validGoalSnapshot()
    totalOnSemanticLoop["current_phase_execution"] =
      linkedMapOf(
        "phase_id" to "audit",
        "kind" to "semantic_loop",
        "count" to 1,
        "total" to 2,
      )
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(totalOnSemanticLoop, "test-current-phase-execution-total-on-loop")
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
    val boundedWithTotal = validGoalSnapshot()
    boundedWithTotal["current_phase_execution"] =
      linkedMapOf(
        "phase_id" to "plan",
        "kind" to "bounded_edge",
        "count" to 1,
        "total" to 2,
      )
    IdeStatusSchemaValidator.validate(boundedWithTotal, "test-current-phase-execution-bounded-total")
  }

  @Test
  fun `current_phase_execution is not in the schema required list`() {
    assertFalse(
      schemaRequiredNames().contains("current_phase_execution"),
      "current_phase_execution must stay optional so older producers remain valid.",
    )
  }

  private fun schemaRequiredNames(): List<String> {
    val resourceStream =
      IdeStatusSchemaValidator::class.java.classLoader
        .getResourceAsStream(IdeStatusSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(resourceStream, "Canonical IDE status schema is missing from the classpath.")
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    return YAMLMapper().readTree(yamlText).path("required").map { it.asText() }
  }

  private fun validGoalSnapshot(): LinkedHashMap<String, Any?> =
    linkedMapOf(
      "contract_version" to IDE_STATUS_CONTRACT_VERSION,
      "repository_identity" to "repo-root-realpath-v1:/repo",
      "issue_key" to "SKILL-148",
      "workflow_id" to "goal-1",
      "workflow_family" to "feature-goal",
      "lifecycle_state" to "active",
      "current_step" to linkedMapOf("id" to "implement", "label" to "Implement"),
      "updated_at" to "2026-08-06T10:10:00Z",
      "freshness" to "fresh",
      "summary" to "Goal SKILL-148 is active on implement.",
    )

  private fun goalSnapshotWithPlanning(planning: Map<String, Any?>): LinkedHashMap<String, Any?> =
    linkedMapOf(
      "contract_version" to IDE_STATUS_CONTRACT_VERSION,
      "repository_identity" to "repo-root-realpath-v1:/repo",
      "issue_key" to "SKILL-165",
      "workflow_id" to "goal-1",
      "workflow_family" to "feature-goal",
      "lifecycle_state" to "active",
      "current_step" to linkedMapOf("id" to "planning", "label" to "Planning"),
      "planning" to planning,
      "updated_at" to "2026-08-06T10:10:00Z",
      "freshness" to "fresh",
      "summary" to "Goal SKILL-165 is planning subtasks (2/5 planned).",
    )

  private fun validIdleSnapshot(): LinkedHashMap<String, Any?> =
    linkedMapOf(
      "contract_version" to IDE_STATUS_CONTRACT_VERSION,
      "repository_identity" to "repo-root-realpath-v1:/repo",
      "lifecycle_state" to "idle",
      "current_step" to linkedMapOf("id" to "none", "label" to "No matching work"),
      "updated_at" to "2026-08-06T10:00:00Z",
      "freshness" to "fresh",
      "summary" to "No matching Skill Bill work for this repository.",
      "problem" to
        linkedMapOf(
          "code" to "no_matching_work",
          "message" to "No matching Skill Bill work for this repository.",
        ),
    )

  @Test
  fun `partial agent activity pair fails loudly`() {
    val onlyAt =
      validIdleSnapshot().apply {
        put("last_agent_activity_at", "2026-08-30T10:00:00Z")
      }
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(onlyAt, "test-agent-activity-partial-at")
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
    val badLabel =
      validIdleSnapshot().apply {
        put("last_agent_activity_at", "2026-08-30T10:00:00Z")
        put("last_agent_activity_label", "grep")
      }
    assertFailsWith<SkillBillRuntimeException> {
      IdeStatusSchemaValidator.validate(badLabel, "test-agent-activity-bad-label")
    }.also { assertEquals(InstallFailureCode.INVALID_IDE_STATUS_SCHEMA, it.code) }
  }

  @Test
  fun `blocked feature-goal snapshot with operator pause reason passes`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-228",
        "workflow_id" to "goal-blocked",
        "workflow_family" to "feature-goal",
        "lifecycle_state" to "blocked",
        "current_step" to linkedMapOf("id" to "validate", "label" to "Validate"),
        "progress" to linkedMapOf("completed" to 1, "total" to 3),
        "pause_reason" to
          linkedMapOf(
            "code" to "awaiting_operator_decision",
            "label" to "Configure GITHUB_REGISTRY_AUTH",
          ),
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-228 is blocked: Configure GITHUB_REGISTRY_AUTH",
      ),
      "test-goal-blocked-pause-reason",
    )
  }
}
