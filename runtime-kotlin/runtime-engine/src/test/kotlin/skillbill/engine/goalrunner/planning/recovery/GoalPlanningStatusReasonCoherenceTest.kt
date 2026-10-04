package skillbill.engine.goalrunner.planning.recovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_ID
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_SCHEMA_ID
import skillbill.engine.goalrunner.planning.remedies.alignPlanningStatusWithLaunchRecoverability
import skillbill.engine.goalrunner.planning.remedies.goalPlanningIncludeSharedPreplanRemedy
import skillbill.engine.goalrunner.planning.remedies.goalPlanningIncompatibleProvenanceStopReason
import skillbill.engine.goalrunner.planning.remedies.goalPlanningMissingSharedContextPacketStopReason
import skillbill.engine.goalrunner.planning.remedies.goalPlanningPreparationStateReadStopReason
import skillbill.engine.goalrunner.planning.remedies.statusRecoverabilityOrRefuse
import skillbill.engine.goalrunner.planning.sweep.phasePayload
import skillbill.error.shellcontent.incompatibleGoalPlanningPreparationRecoveryError
import skillbill.goalrunner.model.GoalPlanningStatusReasons
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationConflict
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.text.sha256HexUtf8

class GoalPlanningStatusReasonCoherenceTest {
  @Test
  fun `invalid provenance overlays resume claim with the exact replan remedy`() {
    val snapshot =
      GoalPlanningStatusSnapshot(
        state = GoalPlanningStatusState.PARTIALLY_PLANNED,
        sharedPreplanPrepared = true,
        plannedSubtaskCount = 1,
        totalSubtaskCount = 2,
        currentPlanningSubtaskId = 2,
        reason = GoalPlanningStatusReasons.partiallyPlannedResume(2),
      )

    val aligned =
      alignPlanningStatusWithLaunchRecoverability(
        snapshot = snapshot,
        recoverability = GoalPlanningProvenanceRecoverability.Irrecoverable(GoalPlanningRecoveryKind.SCOPED_REPLAN),
        issueKey = "WE-4719",
        remedySubtaskId = 2,
      )

    assertFalse(GoalPlanningStatusReasons.claimsResume(aligned.reason), aligned.reason)
    assertTrue(
      aligned.reason!!.contains(goalPlanningIncludeSharedPreplanRemedy("WE-4719", 2)),
      aligned.reason,
    )
  }

  @Test
  fun `non-resumable planning clears the advertised wave so status stops naming in-flight subtasks`() {
    val snapshot =
      GoalPlanningStatusSnapshot(
        state = GoalPlanningStatusState.PARTIALLY_PLANNED,
        sharedPreplanPrepared = true,
        plannedSubtaskCount = 1,
        totalSubtaskCount = 8,
        currentPlanningSubtaskId = 2,
        planningWaveSubtaskIds = listOf(2, 3, 4, 5, 6),
        reason = GoalPlanningStatusReasons.partiallyPlannedResume(2),
      )

    val aligned =
      alignPlanningStatusWithLaunchRecoverability(
        snapshot = snapshot,
        recoverability = GoalPlanningProvenanceRecoverability.Irrecoverable(GoalPlanningRecoveryKind.SCOPED_REPLAN),
        issueKey = "SKILL-230",
        remedySubtaskId = 2,
      )

    assertFalse(GoalPlanningStatusReasons.claimsResume(aligned.reason), aligned.reason)
    assertEquals(emptyList<Int>(), aligned.planningWaveSubtaskIds)
  }

  @Test
  fun `reuse keeps the resumable planning reason`() {
    val snapshot =
      GoalPlanningStatusSnapshot(
        state = GoalPlanningStatusState.PREPLANNED,
        sharedPreplanPrepared = true,
        plannedSubtaskCount = 0,
        totalSubtaskCount = 2,
        currentPlanningSubtaskId = 1,
        reason = GoalPlanningStatusReasons.preplannedResume(1),
      )
    val provenance =
      GoalPlanningContractProvenance(
        parentSpecHash = "a".repeat(64),
        decompositionManifestHash = "b".repeat(64),
        planningContractId = GOAL_PLANNING_PREPARATION_SCHEMA_ID,
      )

    val aligned =
      alignPlanningStatusWithLaunchRecoverability(
        snapshot = snapshot,
        recoverability = GoalPlanningProvenanceRecoverability.Reuse(provenance),
        issueKey = "SKILL-181",
        remedySubtaskId = 1,
      )

    assertEquals(snapshot.reason, aligned.reason)
  }

  @Test
  fun `stale-valid keeps resume reason because launch refreshes rather than refuses`() {
    val snapshot =
      GoalPlanningStatusSnapshot(
        state = GoalPlanningStatusState.PARTIALLY_PLANNED,
        sharedPreplanPrepared = true,
        plannedSubtaskCount = 1,
        totalSubtaskCount = 2,
        currentPlanningSubtaskId = 2,
        reason = GoalPlanningStatusReasons.partiallyPlannedResume(2),
      )
    val provenance =
      GoalPlanningContractProvenance(
        parentSpecHash = "a".repeat(64),
        decompositionManifestHash = "b".repeat(64),
        planningContractId = GOAL_PLANNING_PREPARATION_SCHEMA_ID,
      )

    val aligned =
      alignPlanningStatusWithLaunchRecoverability(
        snapshot = snapshot,
        recoverability = GoalPlanningProvenanceRecoverability.StaleValid(provenance),
        issueKey = "SKILL-181",
        remedySubtaskId = 2,
      )

    assertEquals(snapshot.reason, aligned.reason)
  }

  @Test
  fun `classify failure maps to Invalid so status cannot keep a resume claim`() {
    val recoverability =
      statusRecoverabilityOrRefuse {
        throw incompatibleGoalPlanningPreparationRecoveryError(
          "wfl",
          0,
          "stored goal or repository identity differs from expected identity",
        )
      }
    assertIs<GoalPlanningProvenanceRecoverability.Irrecoverable>(recoverability)

    val snapshot =
      GoalPlanningStatusSnapshot(
        state = GoalPlanningStatusState.PARTIALLY_PLANNED,
        sharedPreplanPrepared = true,
        plannedSubtaskCount = 1,
        totalSubtaskCount = 2,
        currentPlanningSubtaskId = 2,
        reason = GoalPlanningStatusReasons.partiallyPlannedResume(2),
      )
    val aligned =
      alignPlanningStatusWithLaunchRecoverability(
        snapshot = snapshot,
        recoverability = recoverability,
        issueKey = "SKILL-181",
        remedySubtaskId = 2,
      )
    assertFalse(GoalPlanningStatusReasons.claimsResume(aligned.reason), aligned.reason)
    assertTrue(
      aligned.reason!!.contains(goalPlanningIncludeSharedPreplanRemedy("SKILL-181", 2)),
      aligned.reason,
    )
  }

  @Test
  fun `classifier invalid for drifted decomposition aligns with stop remedy text`() {
    val parentSpec = "# Parent"
    val checkpoint =
      SharedGoalPreplanCheckpoint(
        identity = GoalPlanningIdentity("wfl", "SKILL-181", "repo-root-realpath-v1:/tmp"),
        preparationStatus = GoalPlanningPreparationState.PREPARED,
        provenance =
          GoalPlanningContractProvenance(
            parentSpecHash = sha256HexUtf8(parentSpec),
            decompositionManifestHash = "stored-manifest",
            planningContractId = GOAL_PLANNING_PREPARATION_SCHEMA_ID,
            phaseOutputContractId = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_ID,
          ),
        payloadSha256 = sha256HexUtf8(phasePayload("preplan")),
        preplanPayload = phasePayload("preplan"),
      )
    val current = checkpoint.provenance.copy(decompositionManifestHash = "drifted-manifest")

    val recoverability =
      classifyGoalPlanningProvenanceRecoverability(
        existing = checkpoint,
        current = current,
        savedParentSpec = parentSpec,
        currentParentSpec = parentSpec,
      )
    assertIs<GoalPlanningProvenanceRecoverability.Irrecoverable>(recoverability)
    assertEquals(GoalPlanningRecoveryKind.SCOPED_REPLAN, recoverability.recoveryKind)

    val stopReason =
      goalPlanningIncompatibleProvenanceStopReason("SKILL-181", 1, recoverability.recoveryKind)
    assertTrue(stopReason.contains(goalPlanningIncludeSharedPreplanRemedy("SKILL-181", 1)))
    assertFalse(stopReason.contains("cannot be recovered"))
  }

  @Test
  fun `preparation state read stop uses recovery reason not cannot-be-recovered message`() {
    val error =
      GoalPlanningPreparationConflict(
        "wfl",
        0,
        "stored goal or repository identity differs from expected identity",
        null,
      )
    val stopReason = goalPlanningPreparationStateReadStopReason(error, "SKILL-181", 1)
    assertTrue(stopReason.contains(goalPlanningIncludeSharedPreplanRemedy("SKILL-181", 1)), stopReason)
    assertTrue(stopReason.contains(error.reason), stopReason)
    assertFalse(stopReason.contains("cannot be recovered"), stopReason)
  }

  @Test
  fun `missing shared context packet stop names the include-shared-preplan remedy`() {
    val stopReason = goalPlanningMissingSharedContextPacketStopReason("SKILL-181", 2)
    assertTrue(stopReason.contains(goalPlanningIncludeSharedPreplanRemedy("SKILL-181", 2)), stopReason)
    assertTrue(stopReason.contains("does not contain a valid shared context packet"), stopReason)
  }

  private fun phasePayload(phase: String): String =
    """
    {
      "contract_version": "0.4",
      "phase_id": "$phase",
      "status": "completed",
      "summary": "fixture",
      "produced_outputs": { "ok": true }
    }
    """.trimIndent()
}
