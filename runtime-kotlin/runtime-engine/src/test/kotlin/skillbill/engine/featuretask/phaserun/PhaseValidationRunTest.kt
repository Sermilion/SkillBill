package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.committedRepoBranchSetup
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.kotlinPackWithValidationGate
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.engine.featuretask.validation.repoLocalConfig
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.scaffold.model.PlatformManifest
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class PhaseValidationRunTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-phase-validation-repo")
  private val home: Path = Files.createTempDirectory("skillbill-phase-validation-home")
  private val clock: Clock = Clock.systemUTC()
  private val database = phaseRunDatabase(home, clock)
  private val branchSetup =
    committedRepoBranchSetup().also {
      it.gitOperations.repositoryFingerprintValue = "validation-checkpoint"
      it.gitOperations.trackedPathsValue = listOf("src/Foo.kt")
    }
  private val headBefore = branchSetup.gitOperations.headCommitShaValue

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
  }

  @Test
  fun `standalone validation discovers checks without installed packs`() {
    val gateRequests = mutableListOf<ValidationGateRunRequest>()
    val launcher = launcher { validJsonOutput(PHASE_VALIDATE) }
    val result =
      assertIs<PhaseRunResult.Completed>(
        entry(launcher, gateRequests, emptyList(), manifests = emptyList()).run(validationRequest()),
      )

    assertEquals(listOf(PHASE_VALIDATE), result.completedStepIds)
    val prompt = requireNotNull(launcher.requests.single().skillRunRequest.promptOverride)
    assertContains(prompt, "Discover the validation checks required by this project")
    assertContains(prompt, "Compilation alone is insufficient")
    assertContains(prompt, "print a warning")
    assertContains(prompt, "then settle completed and allow the workflow to advance")
    assertEquals(emptyList(), gateRequests)
    database.assertNoDurableWorkflowState()
    branchSetup.gitOperations.assertNoCommitOrCheckpointRef(headBefore)
  }

  @Test
  fun `no discovered checks completes validation and retains the warning`() {
    val requests = mutableListOf<ValidationGateRunRequest>()
    val warning =
      "Warning: No applicable validation commands were found after inspecting repository instructions, " +
        "build files, scripts, and CI."
    val launcher = launcher { warning }

    val result =
      assertIs<PhaseRunResult.Completed>(
        entry(launcher, requests, emptyList(), manifests = emptyList()).run(validationRequest()),
      )

    assertContains(requireNotNull(result.value), warning)
    assertEquals(listOf(PHASE_VALIDATE), result.completedStepIds)
    assertEquals(emptyList(), requests)
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `repeated validation discovers checks afresh even with declared pack commands`() {
    val gateRequests = mutableListOf<ValidationGateRunRequest>()
    val launcher = launcher { validJsonOutput(PHASE_VALIDATE) }
    val entry = entry(launcher, gateRequests, emptyList())

    val first = assertIs<PhaseRunResult.Completed>(entry.run(validationRequest()))
    val second = assertIs<PhaseRunResult.Completed>(entry.run(validationRequest()))

    assertNotEquals(first.invocationId, second.invocationId)
    assertEquals(2, launcher.requests.size)
    assertEquals(emptyList(), gateRequests)
    database.assertNoDurableWorkflowState()
    branchSetup.gitOperations.assertNoCommitOrCheckpointRef(headBefore)
  }

  private fun validationPack(): PlatformManifest =
    kotlinPackWithValidationGate().let { pack ->
      pack.copy(
        validationGate =
          requireNotNull(pack.validationGate).copy(
            buildCommand = listOf("./gradlew", "build-discovery"),
            cacheBypassingBuildCommand = listOf("./gradlew", "build-verification"),
            collectAllFullGateCommand = listOf("./gradlew", "validation-discovery"),
            cacheBypassingCollectAllFullGateCommand = listOf("./gradlew", "validation-verification"),
          ),
      )
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

  private fun entry(
    launcher: RuntimeRecordingLauncher,
    gateRequests: MutableList<ValidationGateRunRequest>,
    results: List<ValidationGateRunResult>,
    manifests: List<PlatformManifest> = listOf(validationPack()),
    runLoopEntry: FeatureTaskRuntimeRunLoopEntry = TestFeatureTaskRuntimeRunLoopEntry(),
  ): PhaseRunEntry {
    var resultIndex = 0
    val config =
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        branchSetup =
          branchSetup.also {
            it.gitOperations.ownedPathsResult =
              WorkflowGitNameListResult.Listed(listOf("src/Foo.kt"))
          },
        repoRoot = repoRoot,
        launcher = launcher,
        validationGatePlatformManifests = manifests,
        gateRepoLocalConfig = repoLocalConfig("./tools/gradlew"),
        validationGateRunner =
          object : ValidationGateRunner {
            override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
              gateRequests += request
              return results.getOrNull(resultIndex++)?.copy(command = request.argv.joinToString(" "))
                ?: ValidationGateRunResult(
                  exitCode = 0,
                  durationMs = 1,
                  outcome = ValidationGateRunOutcome.PASSED,
                  cacheMode = request.cacheMode,
                  executedWorkUnits = 1,
                  executedCheckIdentities = emptyList(),
                  findings = emptyList(),
                  command = request.argv.joinToString(" "),
                )
            }
          },
      )
    val harness =
      telemetryRunnerHarness(
        runtimeConfig = config,
        databaseFactory = { database },
      )
    return phaseRunEntry(
      harness.strategies,
      config.harnessGitOperations,
      database,
      clock,
      harness.runLoopEntry.delegateTo(runLoopEntry),
    )
  }
}
