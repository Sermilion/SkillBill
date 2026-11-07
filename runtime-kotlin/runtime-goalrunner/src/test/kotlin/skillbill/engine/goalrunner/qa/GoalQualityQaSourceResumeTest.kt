package skillbill.engine.goalrunner.qa

import org.junit.jupiter.api.io.TempDir
import skillbill.contracts.JsonCodec
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunRecords
import skillbill.engine.featuretask.phaserun.PhaseRunEntryFixture
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.goalQualityRecordsForTest
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.phaserun.plans
import skillbill.engine.featuretask.phaserun.qualityProgressForTest
import skillbill.engine.featuretask.slot.testPhaseStrategies
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.execution.core.testActivityStampWriter
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerOutcomeStore
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.quality.governingInputFingerprint
import skillbill.engine.goalrunner.persist.quality.toPersistenceWire
import skillbill.engine.goalrunner.persist.taskRuntimeWorkflowRecord
import skillbill.error.core.SkillBillRuntimeException
import skillbill.goalrunner.model.quality.GoalQualityAcceptedOutput
import skillbill.goalrunner.model.quality.GoalQualityAcceptedSourceObservation
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionAdmission
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionPlans
import skillbill.goalrunner.model.quality.GoalQualityExecutionProgress
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.goalrunner.model.quality.GoalQualityPreQaBuildState
import skillbill.goalrunner.model.quality.GoalQualityQaSourceWitness
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.goalrunner.model.quality.acceptedQaSourceWitness
import skillbill.goalrunner.model.quality.hasAcceptedQaSource
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRepairOutput
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalQualityQaSourceResumeTest {
  @TempDir lateinit var home: Path
  private val now = Instant.parse("2026-11-07T00:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)
  private val parent = "goal-parent"
  private val invocation = "phr-quality-invocation"
  private val namespace = "$parent:$invocation"
  private val nestedKey = "$namespace:goal-qa:build:1:build"
  private val pause = FeatureTaskRuntimeProviderLimitPause.MARKER
  private val git = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "qa-source-1" }

  @Test
  fun `provider paused nested QA repair retains its next accepted repair and budget across durable reload`() {
    val database = phaseRunDatabase(home, clock)
    val quality = initialQuality()
    val row =
      taskRuntimeWorkflowRecord(parent).copy(
        artifactsJson =
          JsonCodec.mapToJsonString(
            mapOf(DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.entry(quality.toPersistenceWire())),
          ),
      )
    database.transaction { it.workflowStates.saveFeatureTaskWorkflow(row, FeatureTaskWorkflowMode.RUNTIME) }
    val store = testWorkflowGoalRunnerOutcomeStore(database)
    val execution = assertNotNull(store.goalQualityExecution(parent)?.execution)
    val phase =
      GoalQualityPhaseRequests(store, git, clock, testActivityStampWriter()).create(
        GoalQualityPhaseInput(SkeletonDefinition.GOAL_QA, assertNotNull(execution.governingInputs), execution),
        state(),
        GoalRunnerRunRequest("SKILL-514", home, "codex"),
      ) { _, _, _, _ -> }
    val records = goalQualityRecordsForTest(phase, clock, namespace)
    records.recordRequiredPhaseStart(
      FeatureTaskRuntimePhaseStateRequest(
        namespace,
        "build",
        "running",
        1,
        resolvedAgentId = "codex",
        finished = false,
      ),
    )
    val resumed =
      assertNotNull(
        records.qualityProgressForTest().buildGateProgress,
      ).copy(terminalOutcome = null, terminalReason = null)
    records.persistBuildGateProgress(nestedKey, resumed)
    val proof = assertNotNull(store.goalQualityExecution(parent)?.execution?.acceptedQaSourceWitness("qa-source-1"))

    val pending =
      resumed.copy(
        pendingExternalOperation = "build:repair:build:2",
        pendingExternalCheckpoint = "qa-source-1",
      )
    records.persistBuildGateProgress(nestedKey, pending)
    val strategies = testPhaseStrategies(GoalRunnerSubtaskLauncher { error("unexpected dispatch") }, git)
    val resolver = PhaseRunEntryFixture(strategies, git, database, clock).plans()
    val qaPlan =
      resolver.validateGoalQualityExecutionPlan(
        resolver.resolveGoalQualityExecutionPlan(PhaseRunRequest(SkeletonDefinition.GOAL_QA.id, home, "codex"), parent),
        SkeletonDefinition.GOAL_QA.id,
        "codex",
      )
    val unresolved = assertNotNull(store.goalQualityExecution(parent)?.execution?.progress).qa
    assertFailsWith<SkillBillRuntimeException> { resolver.validateRetainedRunningPhases(unresolved, qaPlan, namespace) }
    val verified = completeRepair(records, pending)

    val reopened = testWorkflowGoalRunnerOutcomeStore(phaseRunDatabase(home, clock))
    val retained = assertNotNull(reopened.goalQualityExecution(parent)?.execution)
    val qa = assertNotNull(retained.progress).qa
    assertEquals(2, qa.buildGateProgress?.repairsUsed)
    assertEquals(
      listOf("first repair", "second repair"),
      qa.buildGateProgress?.acceptedRepairOutputs?.map { it.output },
    )
    assertTrue(retained.hasAcceptedQaSource("qa-source-1", proof))
    assertNotNull(retained.acceptedQaSourceWitness("qa-source-2"))
    assertNull(retained.acceptedQaSourceWitness("unrelated-source"))
    assertEquals(GoalQualityPreQaBuildState.PASSED, retained.preQaBuild)
    assertEquals("initial-source", retained.preQaBuildContentFingerprint)
    assertNull(retained.finalValidationContentFingerprint)

    assertLaterAttempt(reopened, retained, proof, verified)
  }

  private fun completeRepair(
    records: InMemoryPhaseRunRecords,
    pending: FeatureTaskRuntimeValidationGateProgress,
  ): FeatureTaskRuntimeValidationGateProgress {
    git.repositoryFingerprintValue = "qa-source-2"
    val repaired =
      pending.copy(
        repairsUsed = 2,
        acceptedRepairOutputs =
          pending.acceptedRepairOutputs +
            FeatureTaskRuntimeValidationGateRepairOutput(
              2,
              "second repair",
            ),
        pendingExternalOperation = "build:post_repair_verify:compile",
        pendingExternalCheckpoint = "qa-source-2",
      )
    records.persistBuildGateProgress(nestedKey, repaired)
    val verified =
      repaired.copy(
        gateRunCount = 2,
        gateRuns = repaired.gateRuns + gateRun("qa-source-2"),
        pendingExternalOperation = null,
        pendingExternalCheckpoint = null,
      )
    records.persistBuildGateProgress(nestedKey, verified)
    records.persistBuildGateProgress(nestedKey, verified.copy(terminalOutcome = "paused", terminalReason = pause))

    return verified
  }

  private fun assertLaterAttempt(
    reopened: GoalRunnerWorkflowOutcomeStore,
    retained: GoalQualityExecution,
    proof: GoalQualityQaSourceWitness,
    verified: FeatureTaskRuntimeValidationGateProgress,
  ) {
    val nextRequest =
      GoalQualityPhaseRequests(reopened, git, clock, testActivityStampWriter()).create(
        GoalQualityPhaseInput(SkeletonDefinition.GOAL_QA, assertNotNull(retained.governingInputs), retained),
        state(),
        GoalRunnerRunRequest("SKILL-514", home, "codex"),
      ) { _, _, _, _ -> }
    val nextRecords = goalQualityRecordsForTest(nextRequest, clock, namespace)
    nextRecords.persistBuildGateProgress(nestedKey, verified)
    nextRecords.recordCompletedPhase(
      FeatureTaskRuntimePhaseStateRequest(
        namespace,
        "build",
        "completed",
        1,
        resolvedAgentId = "codex",
        finished = true,
      ),
    )
    nextRecords.recordRequiredPhaseStart(
      FeatureTaskRuntimePhaseStateRequest(
        namespace,
        "build",
        "running",
        2,
        resolvedAgentId = "codex",
        finished = false,
      ),
    )
    nextRecords.persistBuildGateProgress(
      "$namespace:goal-qa:build:2:build",
      FeatureTaskRuntimeValidationGateProgress(0, emptyList()),
    )
    val advanced = assertNotNull(reopened.goalQualityExecution(parent)?.execution)
    assertTrue(advanced.hasAcceptedQaSource("qa-source-1", proof))
    assertNotNull(advanced.acceptedQaSourceWitness("qa-source-2"))
    val before = nextRecords.qualityProgressForTest()
    val tampered = before.copy(acceptedQaSourceWitnesses = emptyList(), revision = before.revision + 1)
    assertFalse(assertNotNull(nextRequest.persistGoalQualityPhaseProgress).invoke(tampered, null))
    assertEquals(advanced, reopened.goalQualityExecution(parent)?.execution)
    val rejected = nextRequest.copy(persistGoalQualityPhaseProgress = { _, _ -> false })
    val refusedRecords = goalQualityRecordsForTest(rejected.copy(goalQualityPhaseProgress = before), clock, namespace)
    assertFailsWith<SkillBillRuntimeException> { refusedRecords.persistBuildGateProgress("new-key", verified) }
    assertEquals(before, refusedRecords.qualityProgressForTest())
  }

  private fun state() =
    GoalRunnerManifestState(
      parent,
      home.resolve("metrics.db").toString(),
      DecompositionManifest(
        issueKey = "SKILL-514",
        featureName = "quality-resume",
        parentSpecPath = "spec.md",
        baseBranch = "main",
        featureBranch = "feat/SKILL-514",
        currentSubtaskIntent = CurrentSubtaskIntent(1, "complete"),
        subtasks = emptyList(),
      ),
    )

  private fun initialQuality(): GoalQualityExecutionRecord {
    val inputs =
      GoalQualityExecutionInputs(
        issueKey = "SKILL-514", intake = null, repoRoot = home.toString(), invokedAgentId = "codex",
        configuredAgentOverrideId = null, timeoutMillis = null, qaSelection = FeatureTaskRuntimeQaSelection.AUTO,
        parentSpecPath = "spec.md", acceptanceCriteria = emptyList(), acceptanceCriteriaReadFailure = null,
        branch = "feat/SKILL-514", modelDirectives = emptyMap(), modelMatrix = null, addons = emptyList(),
      )
    val admitted =
      GoalQualityExecutionRecord(readiness = GoalQualityReadiness.CLEAN_UNSTARTED).admit(
        GoalQualityExecutionAdmission(
          parent,
          invocation,
          "initial-source",
          "selection",
          governingInputFingerprint(inputs),
          inputs,
          GoalQualityExecutionPlans("build-plan", "qa-plan", "validation-plan"),
        ),
      )
    val start =
      FeatureTaskRuntimePhaseLedgerEntry(FeatureTaskRuntimePhaseLedgerAction.START, 1, now, "build", 1, "codex")
    return admitted.copy(
      execution =
        assertNotNull(admitted.execution).copy(
          progress = GoalQualityExecutionProgress(build = initialBuild(start), qa = pausedQa(start)),
          preQaBuild = GoalQualityPreQaBuildState.PASSED,
          preQaBuildContentFingerprint = "initial-source",
        ),
    )
  }

  private fun initialBuild(start: FeatureTaskRuntimePhaseLedgerEntry): GoalQualityPhaseProgress =
    GoalQualityPhaseProgress(
      phaseRecords =
        mapOf(
          "build" to
            FeatureTaskRuntimePhaseRecord(
              "build",
              WorkflowStepStatus.COMPLETED,
              1,
              now,
              resolvedAgentId = "codex",
              finishedAt = now,
            ),
        ),
      phaseLedger =
        listOf(
          start,
          start.copy(action = FeatureTaskRuntimePhaseLedgerAction.COMPLETE, sequenceNumber = 2),
        ),
      acceptedOutputs = listOf(GoalQualityAcceptedOutput("build", 1, "initial build passed")),
      acceptedSourceObservations = listOf(GoalQualityAcceptedSourceObservation("build", 1, "initial-source", 2, 3)),
      buildGateProgress =
        FeatureTaskRuntimeValidationGateProgress(
          gateRunCount = 1,
          gateRuns = listOf(gateRun("initial-source")),
        ),
      buildGateProgressKey = "$namespace:goal-build:build:1:build",
      terminalCompleted = true,
      revision = 3,
    )

  private fun pausedQa(start: FeatureTaskRuntimePhaseLedgerEntry): GoalQualityPhaseProgress =
    GoalQualityPhaseProgress(
      phaseRecords =
        mapOf(
          "build" to
            FeatureTaskRuntimePhaseRecord(
              "build",
              WorkflowStepStatus.PAUSED,
              1,
              now,
              resolvedAgentId = "codex",
              blockedReason = pause,
            ),
        ),
      phaseLedger =
        listOf(
          start,
          start.copy(action = FeatureTaskRuntimePhaseLedgerAction.PAUSED, sequenceNumber = 2, blockedReason = pause),
        ),
      buildGateProgress =
        FeatureTaskRuntimeValidationGateProgress(
          gateRunCount = 1,
          gateRuns = listOf(gateRun("qa-source-1")),
          repairsUsed = 1,
          acceptedRepairOutputs = listOf(FeatureTaskRuntimeValidationGateRepairOutput(1, "first repair")),
          terminalOutcome = "paused",
          terminalReason = pause,
        ),
      buildGateProgressKey = nestedKey,
      revision = 5,
    )

  private fun gateRun(checkpoint: String) =
    FeatureTaskRuntimeValidationGateRunRecord(
      durationMs = 0,
      outcome = "passed",
      cacheMode = "cache_eligible",
      executedWorkUnits = 1,
      command = "compile",
      exitCode = 0,
      repositoryCheckpoint = checkpoint,
    )
}
