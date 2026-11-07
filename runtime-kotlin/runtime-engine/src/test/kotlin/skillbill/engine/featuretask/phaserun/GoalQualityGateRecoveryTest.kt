package skillbill.engine.featuretask.phaserun

import org.junit.jupiter.api.io.TempDir
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.slot.testPhaseStrategies
import skillbill.error.core.SkillBillRuntimeException
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class GoalQualityGateRecoveryTest {
  @TempDir lateinit var home: Path

  private val started = Instant.parse("2026-11-01T15:51:58.488Z")
  private val record =
    FeatureTaskRuntimePhaseRecord("build", WorkflowStepStatus.RUNNING, 1, started, resolvedAgentId = "codex")
  private val first =
    FeatureTaskRuntimePhaseLedgerEntry(
      FeatureTaskRuntimePhaseLedgerAction.START,
      9,
      started,
      "build",
      1,
      "codex",
    )
  private val second = first.copy(sequenceNumber = 10, timestamp = started.plusMillis(507))
  private val gate =
    FeatureTaskRuntimeValidationGateProgress(
      gateRunCount = 1,
      gateRuns =
        listOf(
          FeatureTaskRuntimeValidationGateRunRecord(
            1,
            ValidationGateRunOutcome.PASSED,
            ValidationGateCacheMode.CACHE_ELIGIBLE,
            1,
            command = "./gradlew compileKotlin",
            exitCode = 0,
            repositoryCheckpoint = "checkpoint",
          ),
        ),
    )

  @Test
  fun `repeated matching starts retain a recoverable gate`() {
    validate(progress(listOf(first, second)))
  }

  @Test
  fun `conflicting missing and terminal start histories refuse recovery`() {
    val terminal = first.copy(action = FeatureTaskRuntimePhaseLedgerAction.COMPLETE, sequenceNumber = 10)
    listOf(
      emptyList(),
      listOf(first, second.copy(resolvedAgentId = "other")),
      listOf(first, second.copy(loopId = "other")),
      listOf(first, terminal, second.copy(sequenceNumber = 11)),
    ).forEach { ledger ->
      assertFailsWith<SkillBillRuntimeException> { validate(progress(ledger)) }
    }
  }

  @Test
  fun `repeated starts do not make an interrupted external build safe to replay`() {
    val pending =
      gate.copy(
        pendingExternalOperation = "build:initial_discovery:./gradlew compileKotlin",
        pendingExternalCheckpoint = "checkpoint",
      )
    val failure = assertFailsWith<SkillBillRuntimeException> { validate(progress(listOf(first, second), pending)) }
    assertContains(failure.message.orEmpty(), "outcome of goal-quality step 'build#1' is unknown")
  }

  @Test
  fun `initial discovery cancelled by a graceful runner shutdown may run again before any gate run`() {
    val interrupted =
      FeatureTaskRuntimeValidationGateProgress(
        gateRunCount = 0,
        gateRuns = emptyList(),
        pendingExternalOperation = "build:initial_discovery:./gradlew compileKotlin",
        pendingExternalCheckpoint = "checkpoint",
        pendingExternalInterrupted = true,
      )
    validate(progress(listOf(first, second), interrupted))
  }

  @Test
  fun `interrupted repair and verification commands keep the unknown outcome block`() {
    listOf(
      "build:repair:build:1",
      "build:post_repair_verify:./gradlew compileKotlin",
      "validation:initial_discovery:./gradlew check",
    ).forEach { operation ->
      val interrupted =
        gate.copy(
          pendingExternalOperation = operation,
          pendingExternalCheckpoint = "checkpoint",
          pendingExternalInterrupted = true,
        )
      val failure =
        assertFailsWith<SkillBillRuntimeException>(operation) { validate(progress(listOf(first, second), interrupted)) }
      assertContains(failure.message.orEmpty(), "outcome of goal-quality step 'build#1' is unknown")
    }
  }

  @Test
  fun `an interrupted read-only QA attempt with a consistent start may run again`() {
    val qa = record.copy(phaseId = "qa")
    validateRetained(GoalQualityPhaseProgress(phaseRecords = mapOf("qa" to qa), phaseLedger = qaStarts("qa")))
  }

  @Test
  fun `an interrupted QA fix attempt keeps the unknown outcome block`() {
    val fix = record.copy(phaseId = "qa_fix")
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        validateRetained(
          GoalQualityPhaseProgress(phaseRecords = mapOf("qa_fix" to fix), phaseLedger = qaStarts("qa_fix")),
        )
      }
    assertContains(failure.message.orEmpty(), "outcome of goal-quality step 'qa_fix#1' is unknown")
  }

  @Test
  fun `a running QA attempt with a terminal ledger action keeps the unknown outcome block`() {
    val qa = record.copy(phaseId = "qa")
    val terminal =
      first.copy(phaseId = "qa", action = FeatureTaskRuntimePhaseLedgerAction.COMPLETE, sequenceNumber = 11)
    val ledger = qaStarts("qa") + terminal
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        validateRetained(GoalQualityPhaseProgress(phaseRecords = mapOf("qa" to qa), phaseLedger = ledger))
      }
    assertContains(failure.message.orEmpty(), "outcome of goal-quality step 'qa#1' is unknown")
  }

  private fun qaStarts(phaseId: String) = listOf(first.copy(phaseId = phaseId), second.copy(phaseId = phaseId))

  private fun validateRetained(progress: GoalQualityPhaseProgress) {
    val resolver = resolver()
    val plan =
      resolver.resolveExecutionPlan(
        PhaseRunRequest(definitionId = SkeletonDefinition.GOAL_QA.id, repoRoot = home, invokedAgentId = "codex"),
        "goal-parent",
      )
    resolver.validateRetainedRunningPhases(progress, plan, "goal-parent:quality-invocation")
  }

  private fun resolver(): PhaseRunPlanResolver {
    val clock = Clock.systemUTC()
    val git = RecordingWorkflowGitOperations()
    val strategies = testPhaseStrategies(GoalRunnerSubtaskLauncher { error("Unexpected launch") }, git)
    return PhaseRunEntryFixture(strategies, git, phaseRunDatabase(home, clock), clock).plans()
  }

  private fun progress(
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    nested: FeatureTaskRuntimeValidationGateProgress = gate,
  ) = GoalQualityPhaseProgress(phaseLedger = ledger, buildGateProgress = nested, buildGateProgressKey = "key")

  private fun validate(progress: GoalQualityPhaseProgress) {
    resolver().validateRunningGate(progress, record, ValidationGateCommandFamily.BUILD, "key")
  }
}
