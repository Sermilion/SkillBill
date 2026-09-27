package skillbill.engine.featuretask.phaserun

import skillbill.contracts.JsonCodec
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.engine.RuntimeHarnessConfig
import skillbill.engine.committedRepoBranchSetup
import skillbill.engine.failThenPassValidationGateRunner
import skillbill.engine.featuretask.validation.passed
import skillbill.engine.kotlinPackWithBuildGate
import skillbill.engine.telemetryRunnerHarness
import skillbill.error.shellcontent.MissingValidationGateError
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.scaffold.model.PlatformManifest
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
import skillbill.workflow.taskruntime.phase.task.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PhaseValidationRunTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-phase-validation-repo")
  private val home: Path = Files.createTempDirectory("skillbill-phase-validation-home")
  private val clock: Clock = Clock.systemUTC()
  private val database = phaseRunDatabase(home, clock)

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
  }

  @Test
  fun `phase validation runs the pack gate, reports the quality check, and writes no workflow state`() {
    val gateRuns = mutableListOf<ValidationGateRunRequest>()
    val entry = entryFor(kotlinPackWithBuildGate(), gate { request -> passed().also { gateRuns += request } })

    val result = entry.run(validationRequest())

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertEquals(listOf(PHASE_BUILD), result.completedStepIds)
    assertTrue(gateRuns.isNotEmpty(), "the dominant pack gate must run")
    val started = outboxPayload(TelemetryOutboxEvent.QUALITY_CHECK_STARTED)
    val finished = outboxPayload(TelemetryOutboxEvent.QUALITY_CHECK_FINISHED)
    assertEquals(fixture(QUALITY_CHECK_STARTED_FIXTURE).keys, started.keys)
    assertEquals(fixture(QUALITY_CHECK_FINISHED_FIXTURE).keys, finished.keys)
    assertEquals(QUALITY_CHECK_SKILL, started["routed_skill"])
    assertEquals(QUALITY_CHECK_SKILL, finished["routed_skill"])
    assertEquals(0, (started["initial_failure_count"] as Number).toInt())
    assertEquals("pass", finished["result"])
    assertEquals(started["session_id"], finished["session_id"])
    database.assertOnlyOutboxEvents(QUALITY_CHECK_EVENTS)
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `phase validation that fails, repairs, and passes reports the fixture's quality check payloads`() {
    val gateCalls = AtomicInteger()
    val entry = entryFor(kotlinPackWithBuildGate(), failThenPassValidationGateRunner(gateCalls))

    val result = entry.run(validationRequest())

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertEquals(2, gateCalls.get(), "the gate runs once to discover the failure and once to verify the repair")
    val started = outboxPayload(TelemetryOutboxEvent.QUALITY_CHECK_STARTED)
    val finished = outboxPayload(TelemetryOutboxEvent.QUALITY_CHECK_FINISHED)
    assertMatchesFixture(fixture(QUALITY_CHECK_STARTED_FIXTURE), started)
    assertMatchesFixture(fixture(QUALITY_CHECK_FINISHED_FIXTURE), finished)
    assertEquals(started["session_id"], finished["session_id"])
    database.assertOnlyOutboxEvents(QUALITY_CHECK_EVENTS)
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `phase validation whose gate still fails after the repair cap reports the last run's failures`() {
    val entry = entryFor(kotlinPackWithBuildGate(), failingGate())

    val result = entry.run(validationRequest())

    assertIs<PhaseRunResult.Blocked>(result, result.toString())
    val finished = outboxPayload(TelemetryOutboxEvent.QUALITY_CHECK_FINISHED)
    assertEquals("fail", finished["result"])
    assertEquals(1, (finished["final_failure_count"] as Number).toInt())
    assertEquals(listOf(FAILING_CHECK), finished["failing_check_names"])
  }

  @Test
  fun `phase validation on a dominant pack with no validation gate fails with the missing-gate error`() {
    val entry = entryFor(kotlinPackWithBuildGate().copy(validationGate = null), gate { error("no gate is declared") })

    assertFailsWith<MissingValidationGateError> { entry.run(validationRequest()) }
    database.assertNoDurableWorkflowState()
  }

  private fun validationRequest(): PhaseRunRequest =
    PhaseRunRequest(
      definitionId = SkeletonDefinition.VALIDATION.id,
      repoRoot = repoRoot,
      invokedAgentId = "claude",
    )

  private fun failingGate(): ValidationGateRunner =
    gate { request ->
      ValidationGateRunResult(
        exitCode = 1,
        durationMs = 1,
        outcome = ValidationGateRunOutcome.FAILED,
        cacheMode = request.cacheMode,
        executedWorkUnits = 1,
        executedCheckIdentities = emptyList(),
        findings = listOf(ValidationGateFinding("app", FAILING_CHECK, "broken", "A.kt")),
      )
    }

  private fun gate(result: (ValidationGateRunRequest) -> ValidationGateRunResult): ValidationGateRunner =
    object : ValidationGateRunner {
      override fun run(request: ValidationGateRunRequest) = result(request)
    }

  private fun entryFor(
    pack: PlatformManifest,
    gate: ValidationGateRunner,
  ): PhaseRunEntry {
    val runner =
      telemetryRunnerHarness(
        runtimeConfig =
          RuntimeHarnessConfig(
            branchSetup = committedRepoBranchSetup(),
            repoRoot = repoRoot,
            validationGatePlatformManifests = listOf(pack),
            validationGateRunner = gate,
          ),
        databaseFactory = { database },
      ).runner
    return phaseRunEntry(runner, database, clock)
  }

  /** Every fixture value matches except the normalised session id and duration, and the phase's own scope. */
  private fun assertMatchesFixture(
    expected: Map<String, Any?>,
    actual: Map<String, Any?>,
  ) {
    assertEquals(expected.keys, actual.keys)
    expected.filterKeys { it !in RUN_SPECIFIC_KEYS }.forEach { (key, value) ->
      assertEquals(normalized(value), normalized(actual[key]), key)
    }
    assertEquals(QUALITY_CHECK_SCOPE_TYPE, actual["scope_type"])
    assertTrue((actual["session_id"] as String).isNotBlank())
  }

  private fun normalized(value: Any?): Any? = if (value is Number) value.toLong() else value

  private fun outboxPayload(event: TelemetryOutboxEvent): Map<String, Any?> =
    database.outboxPayloads(event.wireValue).last()

  private fun fixture(relativePath: String): Map<String, Any?> =
    requireNotNull(JsonCodec.anyToStringAnyMap(slotBaselineFixture(relativePath)))

  private companion object {
    const val QUALITY_CHECK_SKILL = "bill-code-check"
    const val QUALITY_CHECK_SCOPE_TYPE = "working_tree"
    const val QUALITY_CHECK_STARTED_FIXTURE = "featuretask/slotbaseline/mcp-lifecycle/quality-check-started.json"
    const val QUALITY_CHECK_FINISHED_FIXTURE = "featuretask/slotbaseline/mcp-lifecycle/quality-check-finished.json"
    const val FAILING_CHECK = "detekt:LongMethod"
    val RUN_SPECIFIC_KEYS = setOf("session_id", "duration_seconds", "scope_type")
    val QUALITY_CHECK_EVENTS =
      setOf(TelemetryOutboxEvent.QUALITY_CHECK_STARTED.wireValue, TelemetryOutboxEvent.QUALITY_CHECK_FINISHED.wireValue)
  }
}
