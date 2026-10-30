package skillbill.engine.goalrunner.qa

import org.junit.jupiter.api.io.TempDir
import skillbill.application.testProjectStore
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.phaserun.PhaseRunEntryFixture
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.phaserun.phaseRunEntry
import skillbill.engine.featuretask.phaserun.plans
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecorder
import skillbill.engine.featuretask.runner.kotlinPackWithValidationGate
import skillbill.engine.featuretask.slot.testPhaseStrategies
import skillbill.engine.featuretask.validation.kotlinPackWithoutGate
import skillbill.engine.goalrunner.TestGoalQualityOutcomeStore
import skillbill.engine.goalrunner.execution.core.testActivityStampWriter
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.model.GoalSubtaskQaSummary
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.goalrunner.model.history.GoalFinalRepairContext
import skillbill.goalrunner.model.history.GoalFinalRepairOutput
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionPlans
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskQaFailureKind
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.QaCapabilityDeclaration
import skillbill.scaffold.model.QaTargetKind
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DefaultGoalRunnerQaGateTest {
  @TempDir lateinit var home: Path

  private val clock = Clock.systemUTC()

  @Test
  fun `resuming settled quality keeps admitted inputs across launch changes`() {
    val admissions = mutableListOf<GoalQualityExecutionInputs>()
    val outcomes =
      object : QaGateOutcomeStore() {
        override fun admitGoalQualityExecution(
          parentWorkflowId: String,
          baselineContentFingerprint: String,
          governingInputs: GoalQualityExecutionInputs,
          executionPlans: GoalQualityExecutionPlans,
        ): GoalQualityExecutionRecord {
          admissions += governingInputs
          return super.admitGoalQualityExecution(
            parentWorkflowId,
            baselineContentFingerprint,
            governingInputs,
            executionPlans,
          )
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
  fun `a pack without QA omits the goal QA run before reading acceptance criteria`() {
    val git = RecordingWorkflowGitOperations().apply { trackedPathsValue = listOf("runtime-kotlin/src/Application.kt") }
    val result =
      gate(git, QaGateOutcomeStore(), listOf(kotlinPackWithoutGate())).run(state(), request())

    assertEquals(GoalQaGateResult.Skipped(GoalQaSkipReason.UNSUPPORTED_TARGET), result)
    assertNull(phaseRunDatabase(home, clock).read { it.featureTaskQaOutcomes.find("goal-parent:test-quality") })
  }

  @Test
  fun `a runtime defect inside the gate is contained, recorded on the parent row and returned as a warning`() {
    val outcomes = QaGateOutcomeStore(parentReadFailure = IllegalStateException("boom"))

    val diagnostics = RecordingDiagnostics()

    val result =
      gate(RecordingWorkflowGitOperations(), outcomes, diagnostics = diagnostics).run(state(), request())

    val reason = "QA could not complete because of a runtime defect: IllegalStateException: boom"
    val warning = diagnostics.warnings.single()
    assertEquals("goal_qa_gate: $reason", warning.first)
    assertEquals("boom", warning.second?.message)
    assertEquals(GoalQaGateResult.Warned(reason), result)
    val recorded =
      assertNotNull(phaseRunDatabase(home, clock).read { it.featureTaskQaOutcomes.find("goal-parent") })
    assertEquals(StandaloneQaOutcome.INCOMPLETE, recorded.outcome)
    assertEquals(reason, recorded.reason)
    assertEquals(FeatureTaskQaFailureKind.DEFECT, recorded.failureKind)
  }

  @Test
  fun `an unreadable parent spec is recorded as an environment failure on the parent row`() {
    val message = "QA did not run: could not read parent spec acceptance criteria: no criteria"
    val rejected = FeatureTaskRuntimeRunInvariantsRead.Rejected("no criteria")
    val git = RecordingWorkflowGitOperations().apply { trackedPathsValue = listOf("src/HomeScreen.kt") }
    val qaPack =
      kotlinPackWithValidationGate().copy(
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
    val database = phaseRunDatabase(home, clock)
    val strategies =
      testPhaseStrategies(
        launcher = GoalRunnerSubtaskLauncher { error("The QA skip rules must not launch an agent.") },
        gitOperations = git,
      )
    val phaseEntry =
      phaseRunEntry(
        strategies,
        git,
        database,
        clock,
      )
    return DefaultGoalRunnerQaGate(
      phaseEntry,
      PhaseRunEntryFixture(strategies, git, database, clock, qaManifests = manifests).plans(),
      outcomes,
      git,
      object : FeatureTaskRuntimeRunInvariantsSource {
        override fun read(specPath: Path) = invariants ?: error("Skipped goal QA must not read the parent spec.")
      },
      clock,
      testActivityStampWriter(),
      FeatureTaskQaOutcomeRecorder(database, clock),
      QaCapabilityResolver(git) { manifests },
      testProjectStore,
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
