package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.runner.STATUS_RUNNING
import skillbill.goalrunner.model.quality.GoalQualityAcceptedSourceObservation
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryGoalQualityPhasePersistTest {
  private val clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)
  private val workflowId = "goal-parent:quality-invocation"

  @Test
  fun `completion metadata retains the observed source on its latest accepted ledger event`() {
    var observations = 0
    var last: GoalQualityPhaseProgress? = null
    val records =
      InMemoryPhaseRunRecords(
        clock,
        null,
        goalQualityPhaseProgress = GoalQualityPhaseProgress(),
        persistGoalQualityPhaseProgress = { progress, _ ->
          last = progress
          true
        },
        goalQualityWorkflowId = workflowId,
        qualitySourceObservation = { phase, attempt ->
          observations++
          GoalQualityAcceptedSourceObservation(phase, attempt, "validated-content", validationOccurrence = 2)
        },
      )
    records.recordRequiredPhaseStart(runningStart("validate", 3))
    records.recordCompletedPhase(phaseState("validate", "completed", 3))
    records.appendLedgerEntry(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId,
        FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
        "validate",
        3,
      ),
    )

    val progress = requireNotNull(last)
    val observation = progress.acceptedSourceObservations.single()
    assertEquals(progress.phaseLedger.last().sequenceNumber, observation.completionSequenceNumber)
    assertEquals(progress.revision, observation.progressRevision)
    assertEquals("validated-content", observation.contentFingerprint)
    assertEquals(2, observation.validationOccurrence)
    assertEquals(1, observations)
  }

  @Test
  fun `goal quality pause through recordPhaseState sinks paused not running`() {
    var last: GoalQualityPhaseProgress? = null
    val records =
      goalQualityRecords { progress, _ ->
        last = progress
        true
      }
    records.recordRequiredPhaseStart(runningStart("qa", 1))
    records.recordPhaseState(
      phaseState(
        phaseId = "qa",
        status = "paused",
        attempt = 1,
      ),
    )

    val snapshot = last ?: error("expected goal-quality progress persist")
    assertEquals(WorkflowStepStatus.PAUSED, snapshot.phaseRecords["qa"]?.status)
    assertEquals(
      FeatureTaskRuntimePhaseLedgerAction.START,
      snapshot.phaseLedger.single { it.phaseId == "qa" }.action,
    )
  }

  @Test
  fun `goal quality required start persists running and start ledger once`() {
    val revisions = mutableListOf<Long>()
    val records =
      goalQualityRecords { progress, _ ->
        revisions += progress.revision
        true
      }
    records.recordRequiredPhaseStart(runningStart("validate", 1))

    assertEquals(listOf(1L), revisions)
    val loaded = records.goalQualityProgress()
    assertEquals(WorkflowStepStatus.RUNNING, loaded.phaseRecords["validate"]?.status)
    assertEquals(1, loaded.phaseLedger.count { it.phaseId == "validate" })
  }

  @Test
  fun `sunk paused goal quality step is omitted from running admission filter`() {
    val records = goalQualityRecords { _, _ -> true }
    records.recordRequiredPhaseStart(runningStart("qa", 1))
    records.recordPhaseState(phaseState("qa", "paused", 1))

    val running =
      records.goalQualityProgress().phaseRecords.values.filter { it.status == WorkflowStepStatus.RUNNING }
    assertTrue(running.isEmpty())
  }

  private fun goalQualityRecords(sink: (GoalQualityPhaseProgress, String?) -> Boolean): InMemoryPhaseRunRecords =
    InMemoryPhaseRunRecords(
      clock,
      null,
      goalQualityPhaseProgress = GoalQualityPhaseProgress(),
      persistGoalQualityPhaseProgress = sink,
      goalQualityWorkflowId = workflowId,
    )

  private fun runningStart(
    phaseId: String,
    attempt: Int,
  ): FeatureTaskRuntimePhaseStateRequest = phaseState(phaseId, STATUS_RUNNING, attempt)

  private fun phaseState(
    phaseId: String,
    status: String,
    attempt: Int,
  ): FeatureTaskRuntimePhaseStateRequest =
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = workflowId,
      phaseId = phaseId,
      status = status,
      attemptCount = attempt,
      resolvedAgentId = "claude",
      finished = status == "completed",
    )
}
