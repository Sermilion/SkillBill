package skillbill.engine.goalrunner.persist.quality

import skillbill.error.core.SkillBillRuntimeException
import skillbill.goalrunner.model.quality.GoalQualityAcceptedSourceObservation
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionPlans
import skillbill.goalrunner.model.quality.GoalQualityExecutionProgress
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.goalrunner.model.quality.GoalQualityPreQaBuildState
import skillbill.goalrunner.model.quality.GoalQualityValidationDisposition
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction.BLOCKED
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction.COMPLETE
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction.START
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GoalQualityInitialBuildEvidenceTest {
  @Test
  fun `a completed initial build recorded by both phase state and observability is accepted`() {
    val execution = runningBuild()

    validateExecutionDisposition(execution, currentContract = true)

    val terminal = acceptedTerminalEvent(requireNotNull(execution.progress).build, PHASE, 1)
    assertEquals(4, terminal?.sequenceNumber)
  }

  @Test
  fun `a single terminal entry for the attempt is still accepted`() {
    val execution =
      runningBuild(ledger = listOf(entry(START, 1), entry(COMPLETE, 2)), completionSequenceNumber = 2)

    validateExecutionDisposition(execution, currentContract = true)
  }

  @Test
  fun `an observation bound to the earlier of two completions is rejected`() {
    val execution = runningBuild(completionSequenceNumber = 3)

    val error = assertFailsWith<SkillBillRuntimeException> { validateExecutionDisposition(execution, true) }
    assertEquals("Running initial build has incomplete or conflicting accepted terminal evidence.", error.message)
  }

  @Test
  fun `an attempt that both completed and blocked has no accepted terminal event`() {
    val progress =
      GoalQualityPhaseProgress(
        phaseLedger = listOf(entry(START, 1), entry(COMPLETE, 2), entry(BLOCKED, 3)),
      )

    assertNull(acceptedTerminalEvent(progress, PHASE, 1))
  }

  private fun runningBuild(
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry> =
      listOf(entry(START, 1), entry(START, 2), entry(COMPLETE, 3), entry(COMPLETE, 4)),
    completionSequenceNumber: Int = ledger.last().sequenceNumber,
  ): GoalQualityExecution {
    val observation =
      GoalQualityAcceptedSourceObservation(
        phaseId = PHASE,
        attempt = 1,
        contentFingerprint = FINGERPRINT,
        completionSequenceNumber = completionSequenceNumber,
        progressRevision = 6,
      )
    val build =
      GoalQualityPhaseProgress(
        phaseRecords =
          mapOf(
            PHASE to
              FeatureTaskRuntimePhaseRecord(
                phaseId = PHASE,
                status = WorkflowStepStatus.COMPLETED,
                attemptCount = 1,
                startedAt = NOW,
                finishedAt = NOW,
                resolvedAgentId = AGENT,
              ),
          ),
        phaseLedger = ledger,
        acceptedSourceObservations = listOf(observation),
        buildGateProgress =
          FeatureTaskRuntimeValidationGateProgress(
            gateRunCount = 1,
            gateRuns =
              listOf(
                FeatureTaskRuntimeValidationGateRunRecord(
                  durationMs = 1,
                  outcome = ValidationGateRunOutcome.PASSED,
                  cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
                  executedWorkUnits = 0,
                  command = "gradlew compileKotlin",
                  exitCode = 0,
                  repositoryCheckpoint = "checkpoint",
                ),
              ),
          ),
        buildGateProgressKey = "$PARENT:$INVOCATION:${SkeletonDefinition.GOAL_BUILD.id}:$PHASE:1:build",
        terminalCompleted = true,
        revision = 7,
      )
    return GoalQualityExecution(
      parentWorkflowId = PARENT,
      invocationId = INVOCATION,
      baselineContentFingerprint = "baseline",
      selectionFingerprint = "selection",
      governingInputFingerprint = "inputs",
      governingInputs = null,
      executionPlans = GoalQualityExecutionPlans("build-plan", "qa-plan", "validation-plan"),
      progress = GoalQualityExecutionProgress(build = build),
      status = GoalQualityExecutionStatus.QA_PENDING,
      validation = GoalQualityValidationDisposition.DECISION_PENDING,
      preQaBuild = GoalQualityPreQaBuildState.RUNNING,
      preQaBuildContentFingerprint = FINGERPRINT,
    )
  }

  private fun entry(
    action: FeatureTaskRuntimePhaseLedgerAction,
    sequenceNumber: Int,
  ) = FeatureTaskRuntimePhaseLedgerEntry(
    action = action,
    sequenceNumber = sequenceNumber,
    timestamp = NOW,
    phaseId = PHASE,
    attemptCount = 1,
    resolvedAgentId = AGENT,
  )

  private companion object {
    const val PHASE = "build"
    const val AGENT = "codex"
    const val PARENT = "wftr-parent"
    const val INVOCATION = "phr-invocation"
    const val FINGERPRINT = "source-fingerprint"
    val NOW: Instant = Instant.parse("2026-11-07T12:04:35Z")
  }
}
