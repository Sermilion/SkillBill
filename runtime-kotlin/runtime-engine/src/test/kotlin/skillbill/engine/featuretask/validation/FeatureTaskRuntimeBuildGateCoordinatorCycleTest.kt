package skillbill.engine.featuretask.validation

import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.engine.featuretask.validation.model.ValidationGateAgentTriageLauncher
import skillbill.engine.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.engine.featuretask.validation.model.ValidationGateCycleResult
import skillbill.engine.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.engine.featuretask.validation.model.ValidationGateTriageResult.Empty
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeBuildGateCoordinatorCycleTest {
  @Test
  fun `clean build gate settles after discovery and confirmation increments gate_run_count by two`() {
    val finding = ValidationGateFinding("m", "compile", "broken", "Foo.kt")
    val runner =
      ScriptedGateRunner(
        listOf(
          failedWith(finding),
          passed(forced = true),
        ),
      )
    val recorded = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val progress = RecordingProgressStore(recorded, null)
    var repairLaunches = 0
    val cycle =
      buildCoordinator(declaredResolver(declarationWithBuild()), runner).execute(
        ValidationGateCycleRequest(
          phaseId = "build",
          repoRoot = validationGateTestRepoRoot,
          request = minimalRequest(),
          validationDepth = ValidationDepth.DEFAULT,
          changedPaths = listOf("runtime-kotlin/foo.kt"),
          repositoryCheckpoint = "checkpoint",
          progressStore = progress,
          agentRepairLauncher =
            ValidationGateAgentRepairLauncher { _, _, _ ->
              repairLaunches++
              ValidationGateAgentRepairResult.Completed(
                FeatureTaskRuntimePhaseOutput("build", 1, "{}"),
              )
            },
        ),
      )
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    assertEquals(1, repairLaunches)
    assertEquals(2, runner.calls)
    assertEquals(2, recorded.last().gateRunCount)
    assertEquals(listOf("echo", "build"), runner.requests.first().argv)
    assertEquals(listOf("echo", "build-full"), runner.requests.last().argv)
  }

  @Test
  fun `build gate uses COLLECT_ALL parse mode so compiler diagnostics are captured`() {
    val runner = ScriptedGateRunner(listOf(passed()))
    val progress = RecordingProgressStore(mutableListOf(), null)
    buildCoordinator(declaredResolver(declarationWithBuild()), runner).execute(
      ValidationGateCycleRequest(
        phaseId = "build",
        repoRoot = validationGateTestRepoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.DEFAULT,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        progressStore = progress,
        agentRepairLauncher =
          ValidationGateAgentRepairLauncher { _, _, _ ->
            error("repair must not launch on a clean discovery run")
          },
      ),
    )
    assertEquals(ValidationGateFindingParseMode.COLLECT_ALL, runner.requests.single().findingParseMode)
  }

  @Test
  fun `absent validation gate skips build instead of blocking`() {
    val cycle =
      FeatureTaskRuntimeBuildGateCoordinator(
        ValidationGateResolver { emptyList() },
        ScriptedGateRunner(emptyList()),
        repoLocalConfig(),
        NoopRuntimeDiagnostics,
      ).execute(
        ValidationGateCycleRequest(
          phaseId = "build",
          repoRoot = validationGateTestRepoRoot,
          request = minimalRequest(),
          validationDepth = ValidationDepth.DEFAULT,
          changedPaths = listOf("skills/bill-feature/content.md"),
          repositoryCheckpoint = "checkpoint",
          progressStore = RecordingProgressStore(mutableListOf(), null),
          agentRepairLauncher =
            ValidationGateAgentRepairLauncher { _, _, _ ->
              error("repair must not launch when validation gate is absent")
            },
        ),
      )

    assertEquals(ValidationGateCycleResult.AbsentFallback, cycle)
  }

  @Test
  fun `build gate keeps repairing until the three-turn cap then records remaining findings`() {
    val finding = ValidationGateFinding("m", "compile", "broken", "Foo.kt")
    val maxTurns = FeatureTaskRuntimeBuildGateCoordinator.MAX_REPAIR_TURNS
    val runnerResults = mutableListOf(failedWith(finding))
    repeat(maxTurns) { runnerResults += failedWith(finding) }
    val runner = ScriptedGateRunner(runnerResults)
    val recorded = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val progress = RecordingProgressStore(recorded, null)
    var repairLaunches = 0
    val cycle =
      buildCoordinator(declaredResolver(declarationWithBuild()), runner).execute(
        ValidationGateCycleRequest(
          phaseId = "build",
          repoRoot = validationGateTestRepoRoot,
          request = minimalRequest(),
          validationDepth = ValidationDepth.DEFAULT,
          changedPaths = listOf("runtime-kotlin/foo.kt"),
          repositoryCheckpoint = "checkpoint",
          progressStore = progress,
          agentRepairLauncher =
            ValidationGateAgentRepairLauncher { _, _, _ ->
              repairLaunches++
              ValidationGateAgentRepairResult.Completed(
                FeatureTaskRuntimePhaseOutput("build", 1, "{}"),
              )
            },
        ),
      )
    val blocked =
      assertIs<ValidationGateCycleTerminalOutcome.Blocked>(
        assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
      )
    assertEquals(maxTurns, repairLaunches)
    assertEquals(1 + maxTurns, runner.calls)
    assertTrue(blocked.reason.contains("after $maxTurns repair"))
    assertTrue(blocked.reason.contains("recorded for the operator"))
    assertEquals("compile", blocked.remainingFindings?.findings?.single()?.ruleOrTestId)
    assertEquals(maxTurns, recorded.last().repairsUsed)
  }

  @Test
  fun `build gate schedules triage for sole unparseable_gate_failure`() {
    val triageLaunches = AtomicInteger(0)
    val repairLaunches = AtomicInteger(0)
    val runner =
      ScriptedGateRunner(
        listOf(failedEmptyFindings("unparseable blob"), passed(forced = true)),
      )
    val recorded = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val progress = RecordingProgressStore(recorded, null)
    buildCoordinator(declaredResolver(declarationWithBuild()), runner).execute(
      ValidationGateCycleRequest(
        phaseId = "build",
        repoRoot = validationGateTestRepoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.DEFAULT,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        progressStore = progress,
        agentTriageLauncher =
          ValidationGateAgentTriageLauncher {
            triageLaunches.incrementAndGet()
            Empty
          },
        agentRepairLauncher =
          ValidationGateAgentRepairLauncher { _, _, _ ->
            repairLaunches.incrementAndGet()
            ValidationGateAgentRepairResult.Completed(
              FeatureTaskRuntimePhaseOutput("build", 1, "{}"),
            )
          },
      ),
    )
    assertEquals(1, triageLaunches.get())
    assertEquals(1, repairLaunches.get())
    assertEquals(2, runner.calls)
  }

  private fun declarationWithBuild() =
    validationGateTestDeclaration.copy(
      buildCommand = listOf("echo", "build"),
      cacheBypassingBuildCommand = listOf("echo", "build-full"),
    )

  private fun buildCoordinator(
    resolver: ValidationGateResolver,
    runner: ScriptedGateRunner,
  ): FeatureTaskRuntimeBuildGateCoordinator =
    FeatureTaskRuntimeBuildGateCoordinator(
      resolver,
      runner,
      repoLocalConfig(),
      NoopRuntimeDiagnostics,
    )
}
