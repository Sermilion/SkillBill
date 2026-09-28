package skillbill.engine.featuretask.phaserun

import skillbill.application.realFeatureTaskRuntimePhaseOutputValidator
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.RuntimeHarnessConfig
import skillbill.engine.RuntimeRecordingLauncher
import skillbill.engine.committedRepoBranchSetup
import skillbill.engine.facts
import skillbill.engine.kotlinPackWithBuildGate
import skillbill.engine.phaseIdFromPrompt
import skillbill.engine.telemetryRunnerHarness
import skillbill.engine.validJsonOutput
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
  fun `standalone validation runs full agent validation without build dispatch or durable workflow state`() {
    val launcher = launcher { validJsonOutput(PHASE_VALIDATE) }

    val result = entry(launcher).run(validationRequest())

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertEquals(listOf(PHASE_VALIDATE), result.completedStepIds)
    assertEquals(listOf(PHASE_VALIDATE), prompts(launcher).map(::phaseIdFromPrompt))
    assertContains(prompts(launcher).single(), "Run the full project validation")
    assertContains(prompts(launcher).single(), "Compilation alone is insufficient")
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `standalone validation preserves remaining failures across progress and completes after repair`() {
    val remaining = "WidgetTest failed: expected 2 but got 3."
    val launcher =
      launcher { attempt ->
        if (attempt == 1) blockedOutput(remaining, "progress") else validJsonOutput(PHASE_VALIDATE)
      }

    val result = entry(launcher).run(validationRequest())

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertEquals(listOf(PHASE_VALIDATE), result.completedStepIds)
    assertEquals(2, launcher.requests.size)
    assertContains(prompts(launcher).last(), remaining)
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `standalone validation blocks when failures stop shrinking`() {
    val launcher = launcher { blockedOutput("WidgetTest still fails.", "no_progress") }

    val result = assertIs<PhaseRunResult.Blocked>(entry(launcher).run(validationRequest()))

    assertEquals(PHASE_VALIDATE, result.stepId)
    assertEquals(emptyList(), result.completedStepIds)
    assertEquals(1, launcher.requests.size)
    assertContains(result.reason, "leftover set did not shrink")
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `standalone validation rejects unparseable completion instead of accepting build success`() {
    val launcher = launcher { "finished" }

    val result = assertIs<PhaseRunResult.Blocked>(entry(launcher).run(validationRequest()))

    assertEquals(PHASE_VALIDATE, result.stepId)
    assertEquals(emptyList(), result.completedStepIds)
    assertEquals(2, launcher.requests.size)
    database.assertNoDurableWorkflowState()
  }

  private fun validationRequest(): PhaseRunRequest =
    PhaseRunRequest(
      definitionId = SkeletonDefinition.VALIDATION.id,
      repoRoot = repoRoot,
      invokedAgentId = "claude",
    )

  private fun launcher(output: (Int) -> String): RuntimeRecordingLauncher {
    var attempt = 0
    return RuntimeRecordingLauncher { facts(output(++attempt)) }
  }

  private fun prompts(launcher: RuntimeRecordingLauncher): List<String> =
    launcher.requests.map { requireNotNull(it.skillRunRequest.promptOverride) }

  private fun blockedOutput(
    remaining: String,
    verdict: String,
  ): String =
    """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"$PHASE_VALIDATE",""" +
      """"status":"blocked","failure_disposition":"needs_user_action","summary":"Project checks still fail.",""" +
      """"produced_outputs":{"value":"$remaining"},"verdict":"$verdict"}"""

  private fun entry(launcher: RuntimeRecordingLauncher): PhaseRunEntry {
    val runner =
      telemetryRunnerHarness(
        runtimeConfig =
          RuntimeHarnessConfig(
            branchSetup =
              committedRepoBranchSetup().also {
                it.gitOperations.ownedPathsResult =
                  WorkflowGitNameListResult.Failed(
                    "Standalone validation must not collect durable readiness evidence.",
                  )
              },
            repoRoot = repoRoot,
            launcher = launcher,
            validator = realFeatureTaskRuntimePhaseOutputValidator,
            validationGatePlatformManifests = listOf(kotlinPackWithBuildGate()),
            validationGateRunner =
              object : ValidationGateRunner {
                override fun run(request: ValidationGateRunRequest): ValidationGateRunResult =
                  error("Standalone validation must use the goal validation agent, not build argv: ${request.argv}")
              },
          ),
        databaseFactory = { database },
      ).runner
    return phaseRunEntry(runner, database, clock)
  }
}
