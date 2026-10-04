package skillbill.di.goal

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.contracts.workflow.goal.GoalPlanningPreparationSchemaValidator
import skillbill.infrastructure.sqlite.withGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.foundCheckpoint
import skillbill.ports.goalrunner.foundPlan
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GoalSubtaskPlanLookupResult
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanLookupResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
      assertEquals(shared.provenance, store.findSharedPreplan(identity()).foundCheckpoint()?.provenance)
      assertEquals(
        plan.subSpecHash,
        store.findSubtaskPlan(identity(), 1, plan.governedSubSpecPath).foundPlan()?.subSpecHash,
      )
    }
  }

  @Test
  fun `normalized contract version const is enforced by schema and store`() {
    assertSharedRejected(
      sharedCheckpoint().copy(contractVersion = "0.1"),
      InstallFailureCode.GOAL_PLANNING_PREPARATION_CONTRACT_INCOMPATIBLE,
    )
    assertPlanRejected(
      planCheckpoint().copy(contractVersion = "0.1"),
      InstallFailureCode.GOAL_PLANNING_PREPARATION_CONTRACT_INCOMPATIBLE,
    )
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
    assertSharedRejected(
      sharedCheckpoint().copy(provenance = provenance().copy(planningContractId = "wrong")),
      InstallFailureCode.GOAL_PLANNING_PREPARATION_CONTRACT_INCOMPATIBLE,
    )
    assertSharedRejected(
      sharedCheckpoint().copy(provenance = provenance().copy(planningContractVersion = "9.9")),
      InstallFailureCode.GOAL_PLANNING_PREPARATION_CONTRACT_INCOMPATIBLE,
    )
  }

  @Test
  fun `normalized phase output provenance is enforced by schema and store`() {
    assertPlanRejected(
      planCheckpoint().copy(provenance = provenance().copy(phaseOutputContractId = "wrong")),
      InstallFailureCode.GOAL_PLANNING_PREPARATION_CONTRACT_INCOMPATIBLE,
    )
    assertPlanRejected(
      planCheckpoint().copy(provenance = provenance().copy(phaseOutputContractVersion = "9.9")),
      InstallFailureCode.GOAL_PLANNING_PREPARATION_CONTRACT_INCOMPATIBLE,
    )
  }

  private fun assertSharedRejected(
    violating: SharedGoalPreplanCheckpoint,
    storeCode: InstallFailureCode = InstallFailureCode.INVALID_GOAL_PLANNING_PREPARATION_SCHEMA,
  ) {
    assertFailsWith<SkillBillRuntimeException> {
      GoalPlanningPreparationSchemaValidator.validate(sharedEnvelope(violating), "shared")
    }.also { assertEquals(InstallFailureCode.INVALID_GOAL_PLANNING_PREPARATION_SCHEMA, it.code) }
    withStore { store ->
      assertEquals(
        storeCode,
        assertFailsWith<SkillBillRuntimeException> { store.checkpointSharedPreplan(violating) }.code,
      )
      assertNull(assertIs<SharedGoalPreplanLookupResult.Found>(store.findSharedPreplan(identity())).checkpoint)
    }
  }

  private fun assertPlanRejected(
    violating: GoalSubtaskPlanCheckpoint,
    storeCode: InstallFailureCode = InstallFailureCode.INVALID_GOAL_PLANNING_PREPARATION_SCHEMA,
  ) {
    assertFailsWith<SkillBillRuntimeException> {
      GoalPlanningPreparationSchemaValidator.validate(planEnvelope(violating), "plan")
    }.also { assertEquals(InstallFailureCode.INVALID_GOAL_PLANNING_PREPARATION_SCHEMA, it.code) }
    withStore { store ->
      store.checkpointSharedPreplan(sharedCheckpoint())
      assertEquals(
        storeCode,
        assertFailsWith<SkillBillRuntimeException> { store.checkpointSubtaskPlan(violating) }.code,
      )
      assertNull(
        assertIs<GoalSubtaskPlanLookupResult.Found>(
          store.findSubtaskPlan(identity(), violating.subtaskId, violating.governedSubSpecPath),
        ).plan,
      )
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
