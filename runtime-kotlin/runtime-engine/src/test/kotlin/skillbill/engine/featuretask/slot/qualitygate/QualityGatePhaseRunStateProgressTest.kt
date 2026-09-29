package skillbill.engine.featuretask.slot.qualitygate

import skillbill.engine.featuretask.runloop.qualitygate.buildGateProgressStore
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.engine.featuretask.validation.ScriptedGateRunner
import skillbill.engine.featuretask.validation.blockedRepair
import skillbill.engine.featuretask.validation.completedRepair
import skillbill.engine.featuretask.validation.declaredResolver
import skillbill.engine.featuretask.validation.failedWith
import skillbill.engine.featuretask.validation.minimalRequest
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.engine.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.engine.featuretask.validation.model.ValidationGateCycleResult
import skillbill.engine.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.engine.featuretask.validation.passed
import skillbill.engine.featuretask.validation.repoLocalConfig
import skillbill.engine.featuretask.validation.validationGateTestDeclaration
import skillbill.engine.featuretask.validation.validationGateTestRepoRoot
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRepairWindowPhase
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

class QualityGatePhaseRunStateProgressTest {
  @Test
  fun `a resumed build cycle reads the open findings a failed cycle wrote only through the run state`() {
    val state = GateOnlyRunRecords()
    val finding = ValidationGateFinding("m", "compile", "broken", "Foo.kt")

    val first =
      coordinator(ScriptedGateRunner(listOf(failedWith(finding)))).execute(
        cycle(state.proxy, ValidationGateAgentRepairLauncher { _, _, _ -> blockedRepair("operator stopped") }),
      )

    assertIs<ValidationGateCycleTerminalOutcome.Blocked>(
      assertIs<ValidationGateCycleResult.Terminal>(first).outcome,
    )
    assertEquals(
      FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN,
      state.progress?.repairWindowPhase,
    )

    val resumedRunner = ScriptedGateRunner(listOf(passed(forced = true)))
    val repairedRules = mutableListOf<String>()
    val second =
      coordinator(resumedRunner).execute(
        cycle(
          state.proxy,
          ValidationGateAgentRepairLauncher { findings, _, _ ->
            repairedRules += findings.findings.map { it.ruleOrTestId }
            completedRepair()
          },
        ),
      )

    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(second).outcome,
    )
    assertEquals(listOf("compile"), repairedRules)
    assertEquals(1, resumedRunner.calls)
    assertEquals(setOf("loadBuildGateProgress", "persistBuildGateProgress"), state.calls.toSet())
  }

  private fun coordinator(runner: ScriptedGateRunner): FeatureTaskRuntimeBuildGateCoordinator =
    FeatureTaskRuntimeBuildGateCoordinator(
      declaredResolver(
        validationGateTestDeclaration.copy(
          buildCommand = listOf("echo", "build"),
          cacheBypassingBuildCommand = listOf("echo", "build-full"),
        ),
      ),
      runner,
      repoLocalConfig(),
      NoopRuntimeDiagnostics,
    )

  private fun cycle(
    records: PhaseRunRecords,
    repair: ValidationGateAgentRepairLauncher,
  ): ValidationGateCycleRequest =
    ValidationGateCycleRequest(
      phaseId = "build",
      repoRoot = validationGateTestRepoRoot,
      request = minimalRequest(),
      validationDepth = ValidationDepth.DEFAULT,
      changedPaths = listOf("runtime-kotlin/foo.kt"),
      repositoryCheckpoint = "checkpoint",
      agentRepairLauncher = repair,
      progressStore = records.buildGateProgressStore(),
    )
}

private class GateOnlyRunRecords {
  var progress: FeatureTaskRuntimeValidationGateProgress? = null
  val calls = mutableListOf<String>()

  val proxy: PhaseRunRecords =
    Proxy.newProxyInstance(
      PhaseRunRecords::class.java.classLoader,
      arrayOf(PhaseRunRecords::class.java),
    ) { _, method, args ->
      if (method.declaringClass != Any::class.java) calls += method.name
      when (method.name) {
        "loadBuildGateProgress" -> progress
        "persistBuildGateProgress" -> {
          progress = args?.last() as FeatureTaskRuntimeValidationGateProgress
          Unit
        }
        "toString" -> "GateOnlyRunRecords"
        "hashCode" -> System.identityHashCode(this)
        "equals" -> false
        else -> fail("gate code reached PhaseRunRecords.${method.name}")
      }
    } as PhaseRunRecords
}
