package skillbill.engine.featuretask.phase.planning

import skillbill.application.testHarnessClock
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWrite
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWriteResult
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunRecords
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.runner.NoopWorkflowSnapshotValidator
import skillbill.engine.featuretask.testing.featureTaskRuntimeWireArtifactValidatorForTest
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.PlanBundleFeasibilityFailureCode
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityIdentity
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunch
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchKind
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchOutcome
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityMode
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilitySettledLaunch
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilitySubtaskIdentity
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePlanBundleFeasibilityPayloadKeys as Keys

class PlanBundleFeasibilityDurableRecordsTest {
  @Test
  fun `repeated initialization retains the first durable identity`() {
    val home = Files.createTempDirectory("feasibility-records")
    try {
      val database = phaseRunDatabase(home, testHarnessClock)
      val validator = featureTaskRuntimeWireArtifactValidatorForTest()
      val phaseRecords =
        featureTaskRuntimePhaseRecorder(
          database,
          NoopWorkflowSnapshotValidator,
          validator,
          validator,
          testHarnessClock,
          NoopRuntimeDiagnostics,
        )
      phaseRecords.openTestWorkflow(WORKFLOW_ID, "session")
      phaseRecords.recordRequiredPhaseStart(
        FeatureTaskRuntimePhaseStateRequest(WORKFLOW_ID, "plan", "running", 1, "codex", false),
      )
      val firstRecorder =
        FeatureTaskRuntimeDecomposeTerminalRecorder(
          database,
          testHarnessClock,
          PlanBundleFeasibilityStateCodec(validator),
          NoopRuntimeDiagnostics,
        )
      val secondRecorder =
        FeatureTaskRuntimeDecomposeTerminalRecorder(
          database,
          testHarnessClock,
          PlanBundleFeasibilityStateCodec(validator),
          NoopRuntimeDiagnostics,
        )
      val initial = initialState("a")
      val changedCapture = initial.copy(identity = initial.identity.copy(parentSha256 = "b".repeat(64)))

      assertIs<PlanBundleFeasibilityStateWriteResult.Applied>(
        firstRecorder.writePlanBundleFeasibility(initialization(initial)),
      )
      assertIs<PlanBundleFeasibilityStateWriteResult.Existing>(
        secondRecorder.writePlanBundleFeasibility(initialization(changedCapture)),
      )
      assertEquals(initial, secondRecorder.loadPlanBundleFeasibility(WORKFLOW_ID))

      assertReplayAndStaleWrite(firstRecorder, secondRecorder, initial)
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  @Test
  fun `initialization cannot fabricate settled evidence in either recorder`() {
    val home = Files.createTempDirectory("feasibility-initialization")
    try {
      val database = phaseRunDatabase(home, testHarnessClock)
      val validator = featureTaskRuntimeWireArtifactValidatorForTest()
      val phases =
        featureTaskRuntimePhaseRecorder(
          database,
          NoopWorkflowSnapshotValidator,
          validator,
          validator,
          testHarnessClock,
          NoopRuntimeDiagnostics,
        )
      phases.openTestWorkflow(WORKFLOW_ID, "session")
      val durable =
        FeatureTaskRuntimeDecomposeTerminalRecorder(
          database,
          testHarnessClock,
          PlanBundleFeasibilityStateCodec(validator),
          NoopRuntimeDiagnostics,
        )
      val memory = InMemoryPhaseRunRecords(testHarnessClock, null)
      val initial = initialState("a")
      val recordedLaunch = launch("invented-transition", initial)
      val fabricated =
        initial.copy(
          settledLaunches =
            listOf(
              PlanBundleFeasibilitySettledLaunch(
                recordedLaunch.transitionId, recordedLaunch.phaseId, recordedLaunch.attempt, recordedLaunch.kind,
                recordedLaunch.baselineIdentity, null, null, PlanBundleFeasibilityLaunchOutcome.COMPLETED, "invented",
              ),
            ),
        )
      assertIs<PlanBundleFeasibilityStateWriteResult.Refused>(
        durable.writePlanBundleFeasibility(initialization(fabricated)),
      )
      assertIs<PlanBundleFeasibilityStateWriteResult.Refused>(
        memory.writePlanBundleFeasibility(initialization(fabricated)),
      )
      assertNull(durable.loadPlanBundleFeasibility(WORKFLOW_ID))
      assertNull(memory.loadPlanBundleFeasibility(WORKFLOW_ID))
      assertIs<PlanBundleFeasibilityStateWriteResult.Applied>(
        durable.writePlanBundleFeasibility(initialization(initial)),
      )
      assertIs<PlanBundleFeasibilityStateWriteResult.Applied>(
        memory.writePlanBundleFeasibility(initialization(initial)),
      )
      val missingTransition = initialization(initial.copy(revision = 1)).copy(expectedRevision = 0)
      assertIs<PlanBundleFeasibilityStateWriteResult.Refused>(memory.writePlanBundleFeasibility(missingTransition))
      assertIs<PlanBundleFeasibilityStateWriteResult.Refused>(durable.writePlanBundleFeasibility(missingTransition))
      assertEquals(initial, memory.loadPlanBundleFeasibility(WORKFLOW_ID))
      assertEquals(initial, durable.loadPlanBundleFeasibility(WORKFLOW_ID))
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  @Test
  fun `malformed codec input retains feasibility failure ownership`() {
    val codec = PlanBundleFeasibilityStateCodec(featureTaskRuntimeWireArtifactValidatorForTest())
    val validWire = codec.encode(initialState("a")).toMap()
    val invalidPayloads =
      listOf(
        null,
        validWire + (1 to "non-string object key"),
        validWire + (Keys.REVISION to "zero"),
      )
    invalidPayloads.forEach { payload ->
      val failure = assertFailsWith<SkillBillRuntimeException> { codec.decode(payload, "corrupt-artifact") }
      assertEquals(PlanBundleFeasibilityFailureCode.INVALID_STATE, failure.code)
    }
    assertEquals(initialState("a"), codec.decode(validWire, "retained-artifact"))
  }

  private fun assertReplayAndStaleWrite(
    firstRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
    secondRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
    initial: FeatureTaskRuntimePlanBundleFeasibilityState,
  ) {
    val firstLaunch = launch("transition-1", initial)
    val launched = initial.copy(revision = 1, reviewRoundsStarted = 1, activeLaunch = firstLaunch)
    assertIs<PlanBundleFeasibilityStateWriteResult.Applied>(
      firstRecorder.writePlanBundleFeasibility(write(0, "transition-1", launched)),
    )
    val settlement =
      PlanBundleFeasibilitySettledLaunch(
        firstLaunch.transitionId,
        firstLaunch.phaseId,
        firstLaunch.attempt,
        firstLaunch.kind,
        firstLaunch.baselineIdentity,
        firstLaunch.repairDirectory,
        firstLaunch.intendedAfterIdentity,
        PlanBundleFeasibilityLaunchOutcome.COMPLETED,
        "original review result",
      )
    val settled =
      launched.copy(
        revision = 2,
        activeLaunch = null,
        settledLaunches = listOf(settlement),
        lastReviewValue = settlement.value,
      )
    assertIs<PlanBundleFeasibilityStateWriteResult.Applied>(
      firstRecorder.writePlanBundleFeasibility(write(1, "transition-1", settled)),
    )
    val secondLaunch = launch("transition-2", settled)
    val latest =
      settled.copy(
        revision = 3,
        reviewRoundsStarted = 2,
        activeLaunch = secondLaunch,
      )
    assertIs<PlanBundleFeasibilityStateWriteResult.Applied>(
      firstRecorder.writePlanBundleFeasibility(write(2, "transition-2", latest)),
    )
    val replay =
      assertIs<PlanBundleFeasibilityStateWriteResult.Replayed>(
        secondRecorder.writePlanBundleFeasibility(write(0, "transition-1", launched)),
      )
    assertEquals("completed", replay.outcome)
    assertEquals(latest, replay.state)
    val refused =
      assertIs<PlanBundleFeasibilityStateWriteResult.Refused>(
        secondRecorder.writePlanBundleFeasibility(write(2, "stale-transition", latest.copy(revision = 4))),
      )
    assertEquals(3, refused.observedRevision)
    assertEquals(latest, secondRecorder.loadPlanBundleFeasibility(WORKFLOW_ID))
  }

  private fun initialization(state: FeatureTaskRuntimePlanBundleFeasibilityState) =
    PlanBundleFeasibilityStateWrite(WORKFLOW_ID, "plan", 1, null, null, state)

  private fun write(
    revision: Int,
    transitionId: String,
    state: FeatureTaskRuntimePlanBundleFeasibilityState,
  ) = PlanBundleFeasibilityStateWrite(WORKFLOW_ID, "plan", 1, revision, transitionId, state)

  private fun launch(
    transitionId: String,
    state: FeatureTaskRuntimePlanBundleFeasibilityState,
  ) = PlanBundleFeasibilityLaunch(
    transitionId,
    "plan",
    1,
    PlanBundleFeasibilityLaunchKind.REVIEW,
    state.identity,
    null,
    null,
  )

  private fun initialState(parentHashSeed: String) =
    FeatureTaskRuntimePlanBundleFeasibilityState(
      revision = 0,
      mode = PlanBundleFeasibilityMode.REVIEW,
      identity =
        PlanBundleFeasibilityIdentity(
          parentSha256 = parentHashSeed.repeat(64),
          manifestSha256 = "c".repeat(64),
          immutableManifestSha256 = "d".repeat(64),
          subtasks =
            listOf(
              PlanBundleFeasibilitySubtaskIdentity(1, "spec.md", "e".repeat(64), listOf("1.1"), emptyList()),
            ),
        ),
      acceptedLines = emptyList(),
      conflicts = emptyList(),
      changedSubtaskIds = emptyList(),
      requiredRefs = listOf("1.1"),
      carriedRefs = emptyList(),
      reviewRoundsStarted = 0,
      repairLaunchesStarted = 0,
      activeLaunch = null,
      settledLaunches = emptyList(),
      lastReviewValue = null,
      approval = null,
      legacyOrigin = false,
    )

  private companion object {
    const val WORKFLOW_ID = "wf-plan-feasibility"
  }
}
