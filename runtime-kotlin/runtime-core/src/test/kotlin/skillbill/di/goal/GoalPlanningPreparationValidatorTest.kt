package skillbill.di.goal

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_ID
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_CONTRACT_VERSION
import skillbill.engine.goalplanning.GoalPlanningPreparationValidator
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.goalrunner.model.GoalPlanningPreparationProvenance
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.text.sha256HexUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalPlanningPreparationValidatorTest {
  private val validator = GoalPlanningPreparationValidator()

  @Test
  fun `a valid preplan and plan pair is accepted`() {
    validator.validate(validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1))
  }

  @Test
  fun `a projection-valid pair still checkpoints unchanged after the producer gate is added`() {
    validator.validate(validRecord(parentGoalWorkflowId = "goal-2", subtaskId = 3))
  }

  @Test
  fun `a payload with an unsupported status is rejected`() {
    val record =
      validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1).copy(
        planPayload = payloadJson(phaseId = "plan", status = "queued"),
      )

    assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { validator.validate(record) }
  }

  @Test
  fun `an envelope with an incompatible envelope contract version is rejected`() {
    val record = validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1).copy(contractVersion = "0.2")

    val error = assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { validator.validate(record) }
    assertEquals("goal-1#1", error.sourceLabel)
  }

  @Test
  fun `an envelope with pending status is rejected at the checkpoint seam`() {
    val record =
      validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1).copy(
        preparationStatus = GoalPlanningPreparationState.PENDING,
      )

    assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { validator.validate(record) }
  }

  @Test
  fun `sha256 of utf8 bytes is deterministic and stable across re-reads`() {
    val first = sha256HexUtf8("# SKILL-128 parent spec")
    val second = sha256HexUtf8("# SKILL-128 parent spec")

    assertEquals(64, first.length)
    assertEquals(first, second)
    assertEquals(first.lowercase(), first)
  }

  @Test
  fun `default provenance pins the reused phase output contract identity`() {
    val provenance =
      GoalPlanningPreparationProvenance(
        parentSpecHash = "p",
        subSpecHash = "s",
        decompositionManifestHash = "m",
      )

    assertEquals(FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_ID, provenance.phaseOutputContractId)
    assertEquals(FEATURE_TASK_RUNTIME_CONTRACT_VERSION, provenance.phaseOutputContractVersion)
    assertEquals(GOAL_PLANNING_PREPARATION_CONTRACT_VERSION, GOAL_PLANNING_PREPARATION_CONTRACT_VERSION)
  }

  private fun validRecord(
    parentGoalWorkflowId: String,
    subtaskId: Int,
  ): GoalPlanningPreparationRecord =
    GoalPlanningPreparationRecord(
      parentGoalWorkflowId = parentGoalWorkflowId,
      normalizedIssueKey = "SKILL-128",
      repositoryIdentity = "repo-root-realpath-v1:/repository",
      subtaskId = subtaskId,
      governedSubSpecPath = ".feature-specs/SKILL-128/spec_subtask_$subtaskId.md",
      preparationStatus = GoalPlanningPreparationState.PREPARED,
      provenance =
        GoalPlanningPreparationProvenance(
          parentSpecHash = sha256HexUtf8("# parent"),
          subSpecHash = sha256HexUtf8("# subtask $subtaskId"),
          decompositionManifestHash = sha256HexUtf8("# manifest"),
        ),
      preplanPayload = payloadJson(phaseId = "preplan"),
      planPayload = payloadJson(phaseId = "plan"),
    )

  private fun payloadJson(
    phaseId: String,
    contractVersion: String = FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
    status: String = "completed",
    producedOutputsJson: String = defaultProjectionJson(phaseId),
  ): String =
    """
    {"contract_version":"$contractVersion","phase_id":"$phaseId","status":"$status","summary":"s",
    "produced_outputs":$producedOutputsJson}
    """.trimIndent().replace("\n", "")

  private fun defaultProjectionJson(phaseId: String): String =
    if (phaseId == "preplan") preplanProjectionJson else planProjectionJson

  private val preplanProjectionJson =
    """{"value":"Producer may omit test obligations in prose."}"""

  private val planProjectionJson =
    """{"value":"Producer may omit test obligations in prose."}"""
}
