package skillbill.engine.featuretask.phaserun

import org.junit.jupiter.api.io.TempDir
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.qa.execution.testQaExecutionLifecycle
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecorder
import skillbill.engine.featuretask.runner.kotlinPackWithValidationGate
import skillbill.engine.featuretask.slot.testPhaseStrategies
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.scaffold.model.QaCapabilityDeclaration
import skillbill.scaffold.model.QaTargetKind
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class GoalQaHistoryAttemptTest {
  @TempDir lateinit var home: Path

  @Test
  fun `parent QA restart records acknowledged attempts instead of the admission snapshot`() {
    val clock = Clock.fixed(Instant.parse("2026-11-08T09:00:00Z"), ZoneOffset.UTC)
    val database = phaseRunDatabase(home, clock)
    val outcomes = FeatureTaskQaOutcomeRecorder(database, clock)
    var latest = GoalQualityPhaseProgress()
    var invocation = 0
    val attempts = mutableListOf<Int>()
    val git =
      RecordingWorkflowGitOperations().apply {
        headCommitShaValue = "1".repeat(40)
        repositoryFingerprintValue = "2".repeat(64)
        trackedPathsValue = listOf("src/Foo.kt")
        runtimePhaseHeadCommitSequence += List(20) { "1".repeat(40) }
      }
    val pack =
      kotlinPackWithValidationGate().copy(
        qaCapability = QaCapabilityDeclaration(setOf(QaTargetKind("android")), "qa/android-qa.md"),
      )
    val strategies =
      testPhaseStrategies(
        launcher =
          GoalRunnerSubtaskLauncher {
            val occurrence = assertNotNull(outcomes.find("parent")?.details?.occurrences?.last())
            val acknowledged = assertNotNull(latest.phaseRecords["qa"]).attemptCount
            assertEquals(acknowledged, occurrence.phaseAttempt)
            assertEquals("qa-parent-${attempts.size + 1}", occurrence.workspaceInvocationId)
            attempts += acknowledged
            throw CancellationException("restart parent QA")
          },
        gitOperations = git,
        qaExecution =
          testQaExecutionLifecycle(
            git,
            capabilities = QaCapabilityResolver(git) { listOf(pack) },
            identifiers = IdentifierGeneratorPort { "parent-${++invocation}" },
          ),
        qaOutcomes = outcomes,
      )
    val entry = phaseRunEntry(strategies, git, database, clock)

    repeat(2) {
      val request =
        PhaseRunRequest(
          definitionId = SkeletonDefinition.GOAL_QA.id,
          repoRoot = home,
          invokedAgentId = "claude",
          qaSelection = FeatureTaskRuntimeQaSelection.AUTO,
          goalQualityPhaseProgress = latest,
          persistGoalQualityPhaseProgress = { progress, _ ->
            latest = progress
            true
          },
          latestGoalQualityPhaseProgress = { latest },
        )
      assertFailsWith<CancellationException> { entry.runForGoal(request, "parent", "feat/existing-runtime-branch") }
    }

    assertEquals(listOf(1, 2), attempts)
    assertEquals("recorded", outcomes.find("parent")?.details?.historyAvailability)
  }
}
