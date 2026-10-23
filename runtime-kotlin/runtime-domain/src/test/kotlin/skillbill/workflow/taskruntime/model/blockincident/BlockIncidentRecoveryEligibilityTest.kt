package skillbill.workflow.taskruntime.model.blockincident

import skillbill.workflow.model.WorkflowStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class BlockIncidentRecoveryEligibilityTest {
  @Test
  fun `attempt limit and operator decisions make recovery unavailable`() {
    val incident = incident()
    val diagnosis = diagnosis()

    val exhausted = BlockIncidentRecoveryEligibility.evaluate(incident, diagnosis, facts(attemptLimitRemaining = false))
    val mandated =
      BlockIncidentRecoveryEligibility.evaluate(
        incident,
        diagnosis,
        facts(operatorDecisionForbidsRetry = true),
      )

    assertEquals(
      BlockIncidentRecoveryEligibilityResult.Unavailable(BlockIncidentRecoveryUnavailableReason.ATTEMPT_LIMIT_REACHED),
      exhausted,
    )
    assertEquals(
      BlockIncidentRecoveryEligibilityResult.Unavailable(BlockIncidentRecoveryUnavailableReason.OPERATOR_DECISION),
      mandated,
    )
  }

  @Test
  fun `unconfirmed prerequisites are returned with user instructions`() {
    val result = BlockIncidentRecoveryEligibility.evaluate(incident(), diagnosis(prerequisite = true), facts())

    assertEquals(
      BlockIncidentRecoveryEligibilityResult.NeedsPrerequisites(
        listOf(
          BlockIncidentRecoveryPrerequisiteInstruction(
            BlockIncidentPrerequisiteKind.CONFIGURATION_FIXED,
            "Confirm the configuration has been fixed.",
          ),
        ),
      ),
      result,
    )
  }

  private fun facts(
    attemptLimitRemaining: Boolean = true,
    operatorDecisionForbidsRetry: Boolean = false,
  ) = BlockIncidentRecoveryFacts(
    WorkflowStatus.BLOCKED, 2, 1, attemptLimitRemaining, operatorDecisionForbidsRetry,
    false, emptySet(), "wftr-target", null, "implement",
  )

  private fun incident() =
    BlockIncident(
      BlockIncidentOccurrence(
        "bi_${"d".repeat(64)}",
        BlockIncidentScope(workflowId = "wftr-target", phaseId = "implement", attempt = 2, generation = 1),
        BlockIncidentFailure(
          BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK,
          "blocked",
          reasonRetention = BlockIncidentReasonRetention.COMPLETE,
          exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
        ),
        Instant.parse("2026-10-21T12:00:00Z"),
        BlockIncidentEvidenceAvailability.DATABASE,
        BlockIncidentIdentityAvailability.AVAILABLE,
        emptyMap(),
      ),
      emptyList(),
      BlockIncidentState(
        BlockIncidentDiagnosis.COMPLETE,
        BlockIncidentRecovery.NOT_STARTED,
        BlockIncidentReport.NOT_REQUESTED,
        4,
        Instant.parse("2026-10-21T13:00:00Z"),
        0,
        null,
        null,
      ),
    )

  private fun diagnosis(prerequisite: Boolean = false) =
    BlockIncidentDiagnosisRecord(
      "bi_${"d".repeat(64)}", 4, "summary", "what happened", null, BlockIncidentCertainty.DIAGNOSED,
      null, BlockIncidentResponsibleCategory.USER_ENVIRONMENT, emptyList(),
      if (prerequisite) {
        listOf(
          BlockIncidentPrerequisite(
            BlockIncidentPrerequisiteKind.CONFIGURATION_FIXED,
            BlockIncidentPrerequisiteCheck.USER_CONFIRMED,
          ),
        )
      } else {
        emptyList()
      },
      BlockIncidentRecoveryAction.REOPEN_BLOCKED_PHASE, "Retry the phase", false, emptySet(),
      BlockIncidentInvestigatorKind.DETERMINISTIC, Instant.parse("2026-10-21T12:01:00Z"),
    )
}
