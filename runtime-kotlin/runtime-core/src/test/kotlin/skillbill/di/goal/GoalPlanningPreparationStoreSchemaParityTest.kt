package skillbill.di.goal

import skillbill.error.core.ShellContentContractException
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.infrastructure.contracts.workflow.goal.GoalPlanningPreparationSchemaValidator
import skillbill.infrastructure.sqlite.withGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GoalPlanningPreparationStoreSchemaParityTest {
  @Test
  fun `canonical schema and store accept normalized shared preplan and subtask plan`() {
    val shared = sharedCheckpoint()
    val plan = planCheckpoint()

    GoalPlanningPreparationSchemaValidator.validate(sharedEnvelope(shared), "goal-1")
    GoalPlanningPreparationSchemaValidator.validate(planEnvelope(plan), "goal-1#1")
    withStore { store ->
      store.checkpointSharedPreplan(shared)
      store.checkpointSubtaskPlan(plan)
      assertEquals(shared.provenance, store.findSharedPreplan(identity())?.provenance)
      assertEquals(plan.subSpecHash, store.findSubtaskPlan(identity(), 1, plan.governedSubSpecPath)?.subSpecHash)
    }
  }

  @Test
  fun `normalized contract version const is enforced by schema and store`() {
    assertSharedRejected(sharedCheckpoint().copy(contractVersion = "0.1"))
    assertPlanRejected(planCheckpoint().copy(contractVersion = "0.1"))
  }

  @Test
  fun `normalized subtask id minimum is enforced by schema and store`() {
    assertPlanRejected(planCheckpoint().copy(subtaskId = 0))
  }

  @Test
  fun `normalized identity fields are enforced by schema and store`() {
    assertSharedRejected(sharedCheckpoint().copy(identity = identity().copy(parentGoalWorkflowId = "")))
    assertSharedRejected(sharedCheckpoint().copy(identity = identity().copy(normalizedIssueKey = "")))
    assertSharedRejected(sharedCheckpoint().copy(identity = identity().copy(repositoryIdentity = "")))
  }

  @Test
  fun `normalized governed path and sub spec hash are enforced by schema and store`() {
    assertPlanRejected(planCheckpoint().copy(governedSubSpecPath = ""))
    assertPlanRejected(planCheckpoint().copy(subSpecHash = "not-a-hash"))
  }

  @Test
  fun `normalized provenance hashes are enforced by schema and store`() {
    assertSharedRejected(sharedCheckpoint().copy(provenance = provenance().copy(parentSpecHash = "")))
    assertSharedRejected(sharedCheckpoint().copy(provenance = provenance().copy(decompositionManifestHash = "")))
  }

  @Test
  fun `normalized preparation status must be prepared in schema and store`() {
    assertSharedRejected(sharedCheckpoint().copy(preparationStatus = GoalPlanningPreparationState.PENDING))
    assertPlanRejected(planCheckpoint().copy(preparationStatus = GoalPlanningPreparationState.PENDING))
  }

  @Test
  fun `normalized planning contract provenance is enforced by schema and store`() {
    assertSharedRejected(sharedCheckpoint().copy(provenance = provenance().copy(planningContractId = "wrong")))
    assertSharedRejected(sharedCheckpoint().copy(provenance = provenance().copy(planningContractVersion = "9.9")))
  }

  @Test
  fun `normalized phase output provenance is enforced by schema and store`() {
    assertPlanRejected(planCheckpoint().copy(provenance = provenance().copy(phaseOutputContractId = "wrong")))
    assertPlanRejected(planCheckpoint().copy(provenance = provenance().copy(phaseOutputContractVersion = "9.9")))
  }

  private fun assertSharedRejected(violating: SharedGoalPreplanCheckpoint) {
    assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
      GoalPlanningPreparationSchemaValidator.validate(sharedEnvelope(violating), "shared")
    }
    withStore { store ->
      assertFailsWith<ShellContentContractException> { store.checkpointSharedPreplan(violating) }
      assertNull(store.findSharedPreplan(identity()))
    }
  }

  private fun assertPlanRejected(violating: GoalSubtaskPlanCheckpoint) {
    assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
      GoalPlanningPreparationSchemaValidator.validate(planEnvelope(violating), "plan")
    }
    withStore { store ->
      store.checkpointSharedPreplan(sharedCheckpoint())
      assertFailsWith<ShellContentContractException> { store.checkpointSubtaskPlan(violating) }
      assertNull(store.findSubtaskPlan(identity(), violating.subtaskId, violating.governedSubSpecPath))
    }
  }

  private fun withStore(block: (GoalPlanningPreparationRepository) -> Unit) {
    val dbPath = tempDb()
    withGoalPlanningPreparationRepository(dbPath.parent, dbPath, block)
  }

  private fun sharedEnvelope(checkpoint: SharedGoalPreplanCheckpoint): Map<String, Any?> =
    linkedMapOf(
      "contract_version" to checkpoint.contractVersion,
      "record_type" to "shared_preplan",
      "identity" to identityEnvelope(checkpoint.identity),
      "preparation_status" to checkpoint.preparationStatus.wireValue,
      "provenance" to provenanceEnvelope(checkpoint.provenance),
      "payload_sha256" to checkpoint.payloadSha256,
      "preplan_payload" to checkpoint.preplanPayload,
    )

  private fun planEnvelope(checkpoint: GoalSubtaskPlanCheckpoint): Map<String, Any?> =
    linkedMapOf(
      "contract_version" to checkpoint.contractVersion,
      "record_type" to "subtask_plan",
      "identity" to identityEnvelope(checkpoint.identity),
      "subtask_id" to checkpoint.subtaskId,
      "manifest_order" to checkpoint.manifestOrder,
      "governed_sub_spec_path" to checkpoint.governedSubSpecPath,
      "sub_spec_hash" to checkpoint.subSpecHash,
      "preparation_status" to checkpoint.preparationStatus.wireValue,
      "provenance" to provenanceEnvelope(checkpoint.provenance),
      "payload_sha256" to checkpoint.payloadSha256,
      "plan_payload" to checkpoint.planPayload,
    )

  private fun identityEnvelope(identity: GoalPlanningIdentity): Map<String, Any?> =
    linkedMapOf(
      "parent_goal_workflow_id" to identity.parentGoalWorkflowId,
      "normalized_issue_key" to identity.normalizedIssueKey,
      "repository_identity" to identity.repositoryIdentity,
    )

  private fun provenanceEnvelope(provenance: GoalPlanningContractProvenance): Map<String, Any?> =
    linkedMapOf(
      "parent_spec_hash" to provenance.parentSpecHash,
      "decomposition_manifest_hash" to provenance.decompositionManifestHash,
      "planning_contract_id" to provenance.planningContractId,
      "planning_contract_version" to provenance.planningContractVersion,
      "phase_output_contract_id" to provenance.phaseOutputContractId,
      "phase_output_contract_version" to provenance.phaseOutputContractVersion,
    )

  private fun identity(): GoalPlanningIdentity =
    GoalPlanningIdentity("goal-1", "SKILL-128", "repo-root-realpath-v1:/repository")

  private fun provenance(): GoalPlanningContractProvenance =
    GoalPlanningContractProvenance(
      parentSpecHash = "a".repeat(64),
      decompositionManifestHash = "b".repeat(64),
      planningContractId = "https://skill-bill.dev/contracts/goal-planning-preparation-schema.yaml",
    )

  private fun sharedCheckpoint(): SharedGoalPreplanCheckpoint =
    SharedGoalPreplanCheckpoint(
      identity = identity(),
      provenance = provenance(),
      payloadSha256 = "c".repeat(64),
      preplanPayload = payload("preplan"),
    )

  private fun planCheckpoint(): GoalSubtaskPlanCheckpoint =
    GoalSubtaskPlanCheckpoint(
      identity = identity(),
      subtaskId = 1,
      manifestOrder = 0,
      governedSubSpecPath = ".feature-specs/SKILL-128/spec_subtask_1.md",
      subSpecHash = "d".repeat(64),
      provenance = provenance(),
      payloadSha256 = "e".repeat(64),
      planPayload = payload("plan"),
    )

  private fun payload(phase: String): String =
    """{"contract_version":"0.7","phase_id":"$phase","status":"completed",
    "summary":"planning", "produced_outputs":{"value":"planning prose"}}"""

  private fun tempDb(): Path =
    Files.createTempDirectory("runtime-kotlin-goal-planning-preparation-parity").resolve("metrics.db")
}
