package skillbill.engine.featuretask.validation

import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.engine.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.engine.featuretask.validation.model.ValidationGateCycleResult
import skillbill.engine.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.engine.featuretask.validation.model.ValidationGateProgressStore
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.process.ShutdownHookPort
import skillbill.ports.process.ShutdownHookRegistration
import skillbill.ports.validation.ValidationGateRunner
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeBuildGateShutdownTest {
  private val declaration =
    validationGateTestDeclaration.copy(
      buildCommand = listOf("./gradlew", "compileKotlin"),
      cacheBypassingBuildCommand = listOf("./gradlew", "compileKotlin", "--rerun-tasks"),
    )
  private val discovery = "build:initial_discovery:./gradlew compileKotlin"

  @Test
  fun `a command that settles before shutdown keeps its recorded result`() {
    val hooks = CapturingShutdownHooks()
    val recorded = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val result =
      coordinator(ScriptedGateRunner(listOf(passed())), hooks)
        .execute(cycle(RecordingProgressStore(recorded, null)))

    assertIs<ValidationGateCycleTerminalOutcome.Completed>(assertIs<ValidationGateCycleResult.Terminal>(result).outcome)
    hooks.fire()
    assertFalse(recorded.last().pendingExternalInterrupted)
    assertEquals(1, recorded.last().gateRunCount)
    assertTrue(hooks.unregistered)
  }

  @Test
  fun `an interrupted discovery runs again on the next cycle`() {
    val interrupted =
      FeatureTaskRuntimeValidationGateProgress(
        gateRunCount = 0,
        gateRuns = emptyList(),
        pendingExternalOperation = discovery,
        pendingExternalCheckpoint = "checkpoint",
        pendingExternalInterrupted = true,
      )
    val recorded = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val runner = ScriptedGateRunner(listOf(passed()))
    val result =
      coordinator(
        runner,
        CapturingShutdownHooks(),
      ).execute(cycle(RecordingProgressStore(recorded, interrupted)))

    assertIs<ValidationGateCycleTerminalOutcome.Completed>(assertIs<ValidationGateCycleResult.Terminal>(result).outcome)
    assertEquals(1, runner.calls)
    assertNull(recorded.last().pendingExternalOperation)
    assertEquals(1, recorded.last().gateRunCount)
  }

  @Test
  fun `a pending discovery without the interrupted flag keeps the unknown outcome block`() {
    val pending =
      FeatureTaskRuntimeValidationGateProgress(
        gateRunCount = 0,
        gateRuns = emptyList(),
        pendingExternalOperation = discovery,
        pendingExternalCheckpoint = "checkpoint",
      )
    val runner = ScriptedGateRunner(emptyList())
    val result =
      coordinator(runner, CapturingShutdownHooks()).execute(cycle(RecordingProgressStore(mutableListOf(), pending)))

    val blocked =
      assertIs<ValidationGateCycleTerminalOutcome.Blocked>(assertIs<ValidationGateCycleResult.Terminal>(result).outcome)
    assertContains(blocked.reason, "is unknown after interruption")
    assertEquals(0, runner.calls)
  }

  private fun coordinator(
    runner: ValidationGateRunner,
    hooks: ShutdownHookPort,
  ) = FeatureTaskRuntimeBuildGateCoordinator(
    declaredResolver(declaration),
    runner,
    repoLocalConfig(),
    NoopRuntimeDiagnostics,
    hooks,
  )

  private fun cycle(store: ValidationGateProgressStore) =
    ValidationGateCycleRequest(
      phaseId = "build",
      repoRoot = validationGateTestRepoRoot,
      request = minimalRequest(),
      validationDepth = ValidationDepth.DEFAULT,
      changedPaths = listOf("runtime-kotlin/foo.kt"),
      repositoryCheckpoint = "checkpoint",
      progressStore = store,
      agentRepairLauncher =
        ValidationGateAgentRepairLauncher { _, _, _ -> error("repair must not launch") },
    )

  private class CapturingShutdownHooks : ShutdownHookPort {
    private val actions = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var unregistered = false

    override fun register(action: () -> Unit): ShutdownHookRegistration {
      actions += action
      return ShutdownHookRegistration {
        unregistered = true
        actions.remove(action)
      }
    }

    fun fire() {
      actions.toList().forEach { it() }
    }
  }
}
