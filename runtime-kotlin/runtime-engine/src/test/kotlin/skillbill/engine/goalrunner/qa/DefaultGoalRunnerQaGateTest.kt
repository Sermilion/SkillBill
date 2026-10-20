package skillbill.engine.goalrunner.qa

import org.junit.jupiter.api.io.TempDir
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.phaserun.phaseRunEntry
import skillbill.engine.featuretask.slot.testPhaseStrategies
import skillbill.engine.goalrunner.execution.core.testActivityStampWriter
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.model.GoalSubtaskQaSummary
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.work.EmptyOutcomeStore
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultGoalRunnerQaGateTest {
  @TempDir lateinit var home: Path

  @Test
  fun `all children with QA plans and recorded outcomes skip goal QA`() {
    val clock = Clock.systemUTC()
    val git = RecordingWorkflowGitOperations()
    val outcomes =
      QaGateOutcomeStore(childPlan = true, childOutcome = GoalSubtaskQaSummary.Recorded(qaOutcome("child")))
    val gate = gate(git, outcomes, clock)

    val result = gate.run(state(subtaskWorkflowId = "child"), request())

    assertEquals(GoalQaGateResult.Skipped(GoalQaSkipReason.SUBTASKS_ALREADY_RAN_QA), result)
  }

  @Test
  fun `a passed parent outcome on the current tree skips goal QA`() {
    val clock = Clock.systemUTC()
    val git = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = TREE_IDENTITY }
    val outcomes = QaGateOutcomeStore(parentOutcome = qaOutcome("goal-parent"))
    val gate = gate(git, outcomes, clock)

    val result = gate.run(state(), request())

    assertEquals(GoalQaGateResult.Skipped(GoalQaSkipReason.ALREADY_SETTLED_ON_TREE), result)
  }

  private fun gate(
    git: RecordingWorkflowGitOperations,
    outcomes: GoalRunnerWorkflowOutcomeStore,
    clock: Clock,
  ): DefaultGoalRunnerQaGate {
    val database = phaseRunDatabase(home, clock)
    val phaseEntry =
      phaseRunEntry(
        testPhaseStrategies(
          launcher = GoalRunnerSubtaskLauncher { error("The QA skip rules must not launch an agent.") },
          gitOperations = git,
        ),
        git,
        database,
        clock,
      )
    return DefaultGoalRunnerQaGate(
      phaseEntry,
      outcomes,
      git,
      object : FeatureTaskRuntimeRunInvariantsSource {
        override fun read(specPath: Path) = error("Skipped goal QA must not read the parent spec.")
      },
      clock,
      testActivityStampWriter(),
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

  private class QaGateOutcomeStore(
    private val childPlan: Boolean = false,
    private val childOutcome: GoalSubtaskQaSummary = GoalSubtaskQaSummary.NotRecorded,
    private val parentOutcome: FeatureTaskQaOutcome? = null,
  ) : GoalRunnerWorkflowOutcomeStore by EmptyOutcomeStore {
    override fun hasQaComposedPlan(workflowId: String): Boolean = childPlan

    override fun recordedQaOutcome(workflowId: String): GoalSubtaskQaSummary = childOutcome

    override fun recordedGoalQaOutcome(parentWorkflowId: String): FeatureTaskQaOutcome? = parentOutcome
  }

  private companion object {
    val TREE_IDENTITY = "2".repeat(64)
  }
}
