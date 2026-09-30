package skillbill.engine.goalrunner.planning.context

import skillbill.engine.goalplanning.toEnvelopeMap
import skillbill.engine.goalplanning.toGoalPlanningPreparationRecord
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.goalrunner.model.GoalPlanningPreparationProvenance
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalPlanningPreparationRecordMappingTest {
  @Test
  fun `legacy pair round-trips through its quarantined 0_1 envelope map`() {
    val record =
      GoalPlanningPreparationRecord(
        parentGoalWorkflowId = "goal-1",
        normalizedIssueKey = "SKILL-128",
        repositoryIdentity = "repo-root-realpath-v1:/repository",
        subtaskId = 2,
        governedSubSpecPath = ".feature-specs/SKILL-128/spec_subtask_2.md",
        preparationStatus = GoalPlanningPreparationState.PREPARED,
        provenance =
          GoalPlanningPreparationProvenance(
            parentSpecHash = "parent-hash",
            subSpecHash = "sub-hash",
            decompositionManifestHash = "manifest-hash",
            phaseOutputContractId = "phase-output-contract-id-fixture",
            phaseOutputContractVersion = "phase-output-contract-version-fixture",
          ),
        preplanPayload = """{"phase_id":"preplan"}""",
        planPayload = """{"phase_id":"plan"}""",
      )

    val envelope = record.toEnvelopeMap()
    val roundTripped = envelope.toGoalPlanningPreparationRecord()

    assertEquals("goal-1", envelope["parent_goal_workflow_id"])
    assertEquals(2, envelope["subtask_id"])
    assertEquals("prepared", envelope["preparation_status"])
    assertEquals("0.1", envelope["contract_version"])
    val provenanceMap = envelope["provenance"] as Map<*, *>
    assertEquals(record.provenance.phaseOutputContractId, provenanceMap["phase_output_contract_id"])
    assertEquals(record.provenance.phaseOutputContractVersion, provenanceMap["phase_output_contract_version"])
    assertEquals(record.copy(createdAt = roundTripped.createdAt, updatedAt = roundTripped.updatedAt), roundTripped)
  }

  @Test
  fun `an unknown preparation status is rejected as a schema error, not a raw JDK exception`() {
    val envelope =
      mapOf<String, Any?>(
        "parent_goal_workflow_id" to "goal-1",
        "normalized_issue_key" to "SKILL-128",
        "repository_identity" to "repo-root-realpath-v1:/repository",
        "subtask_id" to 2,
        "governed_sub_spec_path" to ".feature-specs/SKILL-128/spec_subtask_2.md",
        "preparation_status" to "half-prepared",
        "provenance" to emptyMap<String, Any?>(),
        "preplan_payload" to "{}",
        "plan_payload" to "{}",
        "contract_version" to "0.1",
      )

    val error =
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { envelope.toGoalPlanningPreparationRecord() }

    assertEquals("preparation_status", error.fieldPath)
    assertEquals(".feature-specs/SKILL-128/spec_subtask_2.md", error.sourceLabel)
  }
}
