package skillbill.engine.goalrunner.qa

import org.junit.jupiter.api.io.TempDir
import skillbill.application.testProjectStore
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.phaserun.PhaseRunEntryFixture
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.phaserun.phaseRunEntry
import skillbill.engine.featuretask.phaserun.plans
import skillbill.engine.featuretask.phaserun.testStandalonePhaseRegistration
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecorder
import skillbill.engine.featuretask.runner.BranchSetupTestConfig
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.defaultPhaseOutput
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.kotlinPackWithBuildGate
import skillbill.engine.featuretask.runner.phaseIdFromPrompt
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.validation.kotlinPackWithoutGate
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.TestGoalQualityOutcomeStore
import skillbill.engine.goalrunner.execution.core.testActivityStampWriter
import skillbill.engine.goalrunner.history.goalHistoryFingerprint
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.model.GoalSubtaskQaSummary
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.quality.GoalQualityAdmissionRequest
import skillbill.engine.goalrunner.persist.quality.GoalQualityValidationSourceProof
import skillbill.goalrunner.model.history.GoalFinalRepairContext
import skillbill.goalrunner.model.history.GoalFinalRepairOutput
import skillbill.goalrunner.model.history.GoalHistoryCompletion
import skillbill.goalrunner.model.history.GoalHistoryWriterInput
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.goalrunner.model.quality.GoalQualityPreQaBuildState
import skillbill.goalrunner.model.quality.GoalQualityValidationDisposition
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskQaFailureKind
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.QaCapabilityDeclaration
import skillbill.scaffold.model.QaTargetKind
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeProviderLimitSignal
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultGoalRunnerQaGateTest {
  @TempDir lateinit var home: Path

  private val clock = Clock.systemUTC()

  @Test
  fun `accepted history writer contents keep validation proof current at admission`() {
    var admittedProof: GoalQualityValidationSourceProof? = null
    val outcomes =
      object : QaGateOutcomeStore() {
        override fun admitGoalQualityExecution(
          parentWorkflowId: String,
          admission: GoalQualityAdmissionRequest,
        ): GoalQualityExecutionRecord {
          admittedProof = admission.validationSourceProof
          return super.admitGoalQualityExecution(parentWorkflowId, admission)
        }
      }
    val git = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = TREE_IDENTITY }
    val gate = gate(git, outcomes)
    val state = state()
    val first = gate.run(state, request())
    val accepted = assertNotNull(outcomes.goalQualityExecution(state.parentWorkflowId)?.execution)
    val beforeWriter = assertNotNull(accepted.finalValidationContentFingerprint)
    val afterWriter = "3".repeat(64)
    val writerInput =
      GoalHistoryWriterInput(
        state.parentWorkflowId,
        outcomes.goalHistoryWriterEntries(
          state.parentWorkflowId,
          state.manifest.subtasks.map { it.id to it.workflowId },
        ),
        assertNotNull(outcomes.goalFinalRepairContext(state.parentWorkflowId)),
      )
    assertTrue(
      outcomes.saveGoalHistoryCompletion(
        state.parentWorkflowId,
        accepted.invocationId,
        GoalHistoryCompletion(goalHistoryFingerprint(writerInput, beforeWriter), beforeWriter, afterWriter, "written"),
      ),
    )
    git.repositoryFingerprintValue = afterWriter

    assertEquals(first, gate.run(state, request()))
    assertTrue(assertNotNull(admittedProof).sourceIsCurrent)
    assertEquals(accepted, outcomes.goalQualityExecution(state.parentWorkflowId)?.execution)
  }

  @Test
  fun `resuming settled quality keeps admitted inputs across launch changes`() {
    val admissions = mutableListOf<GoalQualityExecutionInputs>()
    val outcomes =
      object : QaGateOutcomeStore() {
        override fun admitGoalQualityExecution(
          parentWorkflowId: String,
          admission: GoalQualityAdmissionRequest,
        ): GoalQualityExecutionRecord {
          admissions += admission.governingInputs
          return super.admitGoalQualityExecution(parentWorkflowId, admission)
        }
      }
    val git = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = TREE_IDENTITY }
    val gate = gate(git, outcomes)
    val original = request().copy(intake = "SKILL-431")

    val first = gate.run(state(), original)
    val accepted = assertNotNull(outcomes.goalQualityExecution("goal-parent"))
    val sameAgent = gate.run(state(), original.copy(intake = "parent-spec.md"))
    val resumed = gate.run(state(), original.copy(invokedAgentId = "codex"))

    assertEquals(listOf(admissions.first(), admissions.first(), admissions.first()), admissions)
    assertEquals(first, sameAgent)
    assertEquals(first, resumed)
    assertEquals(accepted, outcomes.goalQualityExecution("goal-parent"))
  }

  @Test
  fun `all children with QA plans and recorded outcomes skip goal QA`() {
    val git = RecordingWorkflowGitOperations()
    val outcomes =
      QaGateOutcomeStore(childPlan = true, childOutcome = GoalSubtaskQaSummary.Recorded(qaOutcome("child")))
    val gate = gate(git, outcomes)

    val result = gate.run(state(subtaskWorkflowId = "child"), request())

    assertEquals(GoalQaGateResult.Skipped(GoalQaSkipReason.SUBTASKS_ALREADY_RAN_QA), result)
    assertEquals(emptyList(), outcomes.goalFinalRepairContext("goal-parent")?.outputs)
  }

  @Test
  fun `a passed parent outcome on the current tree skips goal QA`() {
    val git = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = TREE_IDENTITY }
    val outcomes = QaGateOutcomeStore(parentOutcome = qaOutcome("goal-parent"))
    val gate = gate(git, outcomes)

    val result = gate.run(state(), request())

    assertEquals(GoalQaGateResult.Skipped(GoalQaSkipReason.ALREADY_SETTLED_ON_TREE), result)
  }

  @Test
  fun `disabled goal QA retains initial build proof and completes full final validation`() {
    val git = RecordingWorkflowGitOperations()
    val outcomes = QaGateOutcomeStore()
    val pack =
      kotlinPackWithBuildGate().copy(
        qaCapability = QaCapabilityDeclaration(setOf(QaTargetKind("android")), "qa/android-qa.md"),
      )

    val result =
      gate(git, outcomes, listOf(pack)).run(
        state(),
        request().copy(qaSelection = FeatureTaskRuntimeQaSelection.OFF),
      )

    assertEquals(GoalQaGateResult.Skipped(GoalQaSkipReason.DISABLED_POLICY), result)
    val execution = assertNotNull(outcomes.goalQualityExecution("goal-parent")?.execution)
    assertEquals(GoalQualityPreQaBuildState.PASSED, execution.preQaBuild)
    assertNotNull(execution.preQaBuildContentFingerprint)
    assertEquals(GoalQualityValidationDisposition.COMPLETED, execution.validation)
    assertNotNull(execution.finalValidationContentFingerprint)
    assertNull(phaseRunDatabase(home, clock).read { it.featureTaskQaOutcomes.find("goal-parent:test-quality") })
  }

  @Test
  fun `a pack without QA omits the goal QA run and completes final validation`() {
    val git = RecordingWorkflowGitOperations().apply { trackedPathsValue = listOf("runtime-kotlin/src/Application.kt") }
    val outcomes = QaGateOutcomeStore()
    val result =
      gate(git, outcomes, listOf(kotlinPackWithoutGate())).run(state(), request())

    val warning = assertIs<GoalQaGateResult.Warned>(result)
    assertEquals("Required build gate declaration is absent from dominant pack 'kotlin'.", warning.reason)
    assertNull(phaseRunDatabase(home, clock).read { it.featureTaskQaOutcomes.find("goal-parent:test-quality") })
    val execution = assertNotNull(outcomes.goalQualityExecution("goal-parent")?.execution)
    assertEquals(GoalQualityPreQaBuildState.WARNED, execution.preQaBuild)
    assertEquals(GoalQualityExecutionStatus.SETTLED_WARNED, execution.status)
    assertEquals(GoalQualityValidationDisposition.COMPLETED, execution.validation)
    assertNotNull(execution.finalValidationContentFingerprint)
  }

  @Test
  fun `a runtime defect inside the gate is contained, recorded on the parent row and returned as a warning`() {
    val outcomes = QaGateOutcomeStore(parentReadFailure = IllegalStateException("boom"))

    val diagnostics = RecordingDiagnostics()

    val result =
      gate(RecordingWorkflowGitOperations(), outcomes, diagnostics = diagnostics).run(state(), request())

    val reason = "QA could not complete because of a runtime defect: IllegalStateException: boom"
    val warning = diagnostics.warnings.first()
    assertEquals("goal_qa_gate: $reason", warning.first)
    assertEquals("boom", warning.second?.message)
    assertEquals(
      listOf("goal_qa_gate: $reason", "goal_qa_gate: could not recover the QA outcome"),
      diagnostics.warnings.map { it.first },
    )
    assertEquals(GoalQaGateResult.Warned(reason), result)
    val recorded =
      assertNotNull(phaseRunDatabase(home, clock).read { it.featureTaskQaOutcomes.find("goal-parent:test-quality") })
    assertEquals(StandaloneQaOutcome.INCOMPLETE, recorded.outcome)
    assertEquals(reason, recorded.reason)
    val execution = assertNotNull(outcomes.goalQualityExecution("goal-parent")?.execution)
    assertEquals(GoalQualityValidationDisposition.COMPLETED, execution.validation)
    assertNotNull(execution.finalValidationContentFingerprint)
    assertEquals(FeatureTaskQaFailureKind.DEFECT, recorded.failureKind)
  }

  @Test
  fun `an unreadable parent spec is recorded as an environment failure on the parent row`() {
    val message = "QA did not run: could not read parent spec acceptance criteria: no criteria"
    val rejected = FeatureTaskRuntimeRunInvariantsRead.Rejected("no criteria")
    val git = RecordingWorkflowGitOperations().apply { trackedPathsValue = listOf("src/HomeScreen.kt") }
    val qaPack =
      kotlinPackWithBuildGate().copy(
        qaCapability = QaCapabilityDeclaration(setOf(QaTargetKind("android")), "qa/android-qa.md"),
      )

    val result = gate(git, QaGateOutcomeStore(), listOf(qaPack), invariants = rejected).run(state(), request())

    assertEquals(GoalQaGateResult.Warned(message), result)
    val recorded =
      assertNotNull(phaseRunDatabase(home, clock).read { it.featureTaskQaOutcomes.find("goal-parent:test-quality") })
    assertEquals(StandaloneQaOutcome.INCOMPLETE, recorded.outcome)
    assertEquals(FeatureTaskQaFailureKind.ENVIRONMENT, recorded.failureKind)
    assertEquals(message, recorded.reason)
  }

  @Test
  fun `provider limit pause maps blocked QA and build phases without qa_fix build warning`() {
    val pauseReason =
      FeatureTaskRuntimeProviderLimitPause.reason(
        phaseLabel = "Feature-task-runtime phase 'qa'",
        agentId = "claude",
        resumePhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA,
        signal = FeatureTaskRuntimeProviderLimitSignal(evidence = "usage limit exceeded", resetHint = null),
      )
    val git =
      RecordingWorkflowGitOperations().apply {
        repositoryFingerprintValue = TREE_IDENTITY
        trackedPathsValue = listOf("src/HomeScreen.kt")
      }
    val outcomes =
      object : QaGateOutcomeStore() {
        override fun goalQualityPhaseProgress(
          parentWorkflowId: String,
          invocationId: String,
          definitionId: String,
          acceptedPlan: String,
        ) = if (definitionId == SkeletonDefinition.GOAL_QA.id) {
          GoalQualityPhaseProgress(
            terminalBlockedStep = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA,
            terminalBlockedReason = pauseReason,
          )
        } else {
          super.goalQualityPhaseProgress(parentWorkflowId, invocationId, definitionId, acceptedPlan)
        }
      }
    val qaPack =
      kotlinPackWithBuildGate().copy(
        qaCapability = QaCapabilityDeclaration(setOf(QaTargetKind("android")), "qa/android-qa.md"),
      )
    val invariants =
      FeatureTaskRuntimeRunInvariantsRead.Read(
        FeatureTaskRuntimeRunInvariants(
          specReference = "SKILL-431",
          acceptanceCriteria = listOf("The home screen shows the signed-in user name."),
          mandatesAndOverrides = emptyList(),
        ),
      )

    val result = gate(git, outcomes, listOf(qaPack), invariants = invariants).run(state(), request())

    assertEquals(
      GoalQaGateResult.Paused(pauseReason, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA),
      result,
    )
    assertEquals(
      GoalQualityExecutionStatus.QA_PENDING,
      outcomes.goalQualityExecution("goal-parent")?.execution?.status,
    )
    assertNull(outcomes.goalFinalRepairContext("goal-parent")?.warning)
    assertEquals(emptyList(), outcomes.goalFinalRepairContext("goal-parent")?.outputs.orEmpty())
    val blockedAtBuild =
      PhaseRunResult.Blocked(
        "quality-inv",
        emptyList(),
        null,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
        pauseReason,
      )
    assertEquals(
      GoalQaGateResult.Paused(pauseReason, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX),
      goalQaProviderLimitPause(blockedAtBuild),
    )
    assertNull(buildAfterQaFixWarning(blockedAtBuild))
    val ordinaryBlock =
      PhaseRunResult.Blocked(
        "quality-inv",
        emptyList(),
        null,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
        "compile failed after qa_fix",
      )
    assertNull(goalQaProviderLimitPause(ordinaryBlock))
    assertTrue(buildAfterQaFixWarning(ordinaryBlock)?.contains("qa_fix left the app without a passing build") == true)
  }

  @Test
  fun `a cancellation inside the gate is rethrown instead of contained`() {
    val outcomes = QaGateOutcomeStore(parentReadFailure = CancellationException("stop"))

    assertFailsWith<CancellationException> {
      gate(RecordingWorkflowGitOperations(), outcomes).run(state(), request())
    }
  }

  private fun gate(
    git: RecordingWorkflowGitOperations,
    outcomes: GoalRunnerWorkflowOutcomeStore,
    manifests: List<PlatformManifest> = emptyList(),
    diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
    invariants: FeatureTaskRuntimeRunInvariantsRead? = null,
  ): DefaultGoalRunnerQaGate {
    git.currentBranchValue = "feat/SKILL-431-goal"
    git.repositoryFingerprintValue = git.repositoryFingerprintValue ?: TREE_IDENTITY
    if (git.trackedPathsValue.isEmpty()) git.trackedPathsValue = listOf("src/HomeScreen.kt")
    val database = phaseRunDatabase(home, clock)
    val packs = manifests.ifEmpty { listOf(kotlinPackWithBuildGate()) }
    val launcher =
      RuntimeRecordingLauncher { launch ->
        val phaseId = phaseIdFromPrompt(requireNotNull(launch.skillRunRequest.promptOverride))
        assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE, phaseId)
        facts(defaultPhaseOutput(launch))
      }
    val config =
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        repoRoot = home,
        launcher = launcher,
        gitOperationsOverride = git,
        validationGatePlatformManifests = packs,
        diagnostics = diagnostics,
      )
    val harness = telemetryRunnerHarness(runtimeConfig = config, databaseFactory = { database })
    val phaseEntry =
      phaseRunEntry(
        harness.strategies,
        git,
        database,
        clock,
        harness.runLoopEntry.delegateTo(TestFeatureTaskRuntimeRunLoopEntry(gitOperations = git)),
      )
    val phasePlans =
      PhaseRunEntryFixture(harness.strategies, git, database, clock, qaManifests = packs).plans()
    val requests = GoalQualityPhaseRequests(outcomes, git, clock, testActivityStampWriter())
    return DefaultGoalRunnerQaGate(
      phaseEntry,
      phasePlans,
      outcomes,
      git,
      GoalQualityPreparation(
        diagnostics,
        phasePlans,
        requests,
        outcomes,
        git,
        object : FeatureTaskRuntimeRunInvariantsSource {
          override fun read(specPath: Path) =
            invariants ?: FeatureTaskRuntimeRunInvariantsRead.Read(
              FeatureTaskRuntimeRunInvariants(
                specReference = "SKILL-431",
                acceptanceCriteria = listOf("The goal completes its requested quality checks."),
                mandatesAndOverrides = emptyList(),
              ),
            )
        },
        testProjectStore,
        testStandalonePhaseRegistration(clock, diagnostics),
      ),
      requests,
      GoalQualityBuildBoundary(phaseEntry, phasePlans, outcomes, git, requests, diagnostics),
      FeatureTaskQaOutcomeRecorder(database, clock),
      QaCapabilityResolver(git) { manifests },
      diagnostics,
    )
  }

  private fun state(subtaskWorkflowId: String? = null): GoalRunnerManifestState =
    GoalRunnerManifestState(
      parentWorkflowId = "goal-parent",
      dbPath = home.resolve("metrics.db").toString(),
      manifest =
        DecompositionManifest(
          issueKey = "SKILL-431",
          featureName = "goal-level-qa",
          parentSpecPath = "parent-spec.md",
          baseBranch = "main",
          featureBranch = "feat/SKILL-431-goal",
          currentSubtaskIntent = CurrentSubtaskIntent(1, "complete"),
          subtasks =
            listOf(
              DecompositionSubtask(
                id = 1,
                name = "Implementation",
                specPath = "subtask.md",
                workflowId = subtaskWorkflowId,
              ),
            ),
        ),
    )

  private fun request() =
    GoalRunnerRunRequest(
      issueKey = "SKILL-431",
      repoRoot = home,
      invokedAgentId = "claude",
    )

  private fun qaOutcome(workflowId: String) =
    FeatureTaskQaOutcome(workflowId, StandaloneQaOutcome.PASSED, null, 0, TREE_IDENTITY, "2026-10-19T00:00Z")

  private class RecordingDiagnostics : RuntimeDiagnostics {
    val warnings = mutableListOf<Pair<String, Throwable?>>()

    override fun info(message: String) = Unit

    override fun warning(
      message: String,
      error: Throwable?,
    ) {
      warnings += message to error
    }

    override fun error(
      message: String,
      error: Throwable?,
    ) = Unit
  }

  private open class QaGateOutcomeStore(
    private val childPlan: Boolean = false,
    private val childOutcome: GoalSubtaskQaSummary = GoalSubtaskQaSummary.NotRecorded,
    private val parentOutcome: FeatureTaskQaOutcome? = null,
    private val parentReadFailure: Exception? = null,
  ) : TestGoalQualityOutcomeStore() {
    private var repairContext: GoalFinalRepairContext? = null

    override fun recordGoalFinalRepairOutput(
      parentWorkflowId: String,
      expectedInvocationId: String,
      output: GoalFinalRepairOutput,
    ): Boolean {
      val context = repairContext ?: GoalFinalRepairContext(emptyList())
      repairContext = context.copy(outputs = context.outputs + output)
      return true
    }

    override fun recordGoalFinalRepairWarning(
      parentWorkflowId: String,
      expectedInvocationId: String,
      warning: String?,
    ): Boolean {
      val context = repairContext ?: GoalFinalRepairContext(emptyList())
      repairContext = context.copy(warning = warning)
      return true
    }

    override fun goalFinalRepairContext(parentWorkflowId: String): GoalFinalRepairContext? = repairContext

    override fun hasQaComposedPlan(workflowId: String): Boolean = childPlan

    override fun recordedQaOutcome(workflowId: String): GoalSubtaskQaSummary = childOutcome

    override fun recordedGoalQaOutcome(parentWorkflowId: String): FeatureTaskQaOutcome? {
      parentReadFailure?.let { throw it }
      return parentOutcome
    }
  }

  private companion object {
    val TREE_IDENTITY = "2".repeat(64)
  }
}
