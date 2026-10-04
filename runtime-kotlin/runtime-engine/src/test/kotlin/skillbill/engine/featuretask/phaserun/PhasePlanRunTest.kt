package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.runner.PLAN_BUNDLE_PROSE
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.committedRepoBranchSetup
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.phaseIdFromPrompt
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.runner.writePlanBundle
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.infrastructure.contracts.workflow.decomposition.DecompositionManifestSchemaValidator
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PhasePlanRunTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-phase-plan-repo")
  private val home: Path = Files.createTempDirectory("skillbill-phase-plan-home")
  private val clock: Clock = Clock.systemUTC()
  private val database = phaseRunDatabase(home, clock)

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
  }

  @Test
  fun `a preplan that produces nothing blocks, launches no plan agent and writes no spec files or workflow state`() {
    val launcher = launcher { phaseId -> if (phaseId == PREPLAN) "" else PLAN_BUNDLE_PROSE }

    val result = entry(launcher).run(planRequest())

    assertIs<PhaseRunResult.Blocked>(result, result.toString())
    assertEquals(PREPLAN, result.stepId)
    assertEquals(listOf(PREPLAN), launchedPhaseIds(launcher))
    assertEquals(emptyList(), planBundleDirectories(), "a blocked preplan must write no spec files")
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `a plan session that authors a complete bundle completes with its paths`() {
    val launcher = launcher { phaseId -> if (phaseId == PLAN) authoredBundleOutput() else validJsonOutput(phaseId) }

    val result = entry(launcher).run(planRequest())

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    val bundle = requireNotNull(result.specBundle) { "a decomposed plan must carry its spec bundle" }
    assertTrue(bundle.parentSpecPath.startsWith("$FEATURE_SPECS/$ISSUE_KEY-"), bundle.parentSpecPath)
    assertEquals(2, bundle.subtaskSpecPaths.size)
    (listOf(bundle.parentSpecPath, bundle.decompositionManifestPath) + bundle.subtaskSpecPaths).forEach { path ->
      assertTrue(Files.isRegularFile(repoRoot.resolve(path)), "$path must be written")
    }
    DecompositionManifestSchemaValidator().validateYamlText(
      Files.readString(repoRoot.resolve(bundle.decompositionManifestPath)),
      bundle.decompositionManifestPath,
    )
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `a plan session that authors no bundle blocks at plan`() {
    val launcher = launcher { phaseId -> if (phaseId == PLAN) PLAN_BUNDLE_PROSE else validJsonOutput(phaseId) }

    val result = entry(launcher).run(planRequest())

    assertIs<PhaseRunResult.Blocked>(result, result.toString())
    assertEquals(PLAN, result.stepId)
    assertEquals(listOf(PREPLAN), result.completedStepIds, "a plan without a bundle must not complete")
    assertEquals(emptyList(), planBundleDirectories(), "a plan without a bundle must leave no spec files")
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `a plan session whose subtask has no acceptance list blocks with the readiness reason`() {
    val launcher =
      launcher { phaseId ->
        if (phaseId == PLAN) {
          val bundle = writePlanBundle(repoRoot, ISSUE_KEY)
          val subtask = repoRoot.resolve(bundle.subtaskSpecPaths.first())
          Files.writeString(subtask, Files.readString(subtask).substringBefore("## Acceptance Criteria"))
          PLAN_BUNDLE_PROSE
        } else {
          validJsonOutput(phaseId)
        }
      }

    val result = entry(launcher).run(planRequest())

    assertIs<PhaseRunResult.Blocked>(result, result.toString())
    assertEquals(PLAN, result.stepId)
    assertEquals(listOf(PREPLAN), result.completedStepIds)
    assertTrue(result.reason.contains("ready spec bundle"), result.reason)
    database.assertNoDurableWorkflowState()
  }

  private fun authoredBundleOutput(): String {
    writePlanBundle(repoRoot, ISSUE_KEY)
    return PLAN_BUNDLE_PROSE
  }

  private fun planRequest(): PhaseRunRequest =
    PhaseRunRequest(
      definitionId = SkeletonDefinition.PLAN.id,
      repoRoot = repoRoot,
      invokedAgentId = "claude",
      intake = "$ISSUE_KEY split the runtime work into ordered subtasks",
    )

  private fun planBundleDirectories(): List<String> =
    repoRoot.resolve(FEATURE_SPECS).toFile().list().orEmpty().filter { name -> name.startsWith("$ISSUE_KEY-") }

  private fun launcher(output: (String) -> String): RuntimeRecordingLauncher =
    RuntimeRecordingLauncher { request ->
      facts(output(phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))))
    }

  private fun launchedPhaseIds(launcher: RuntimeRecordingLauncher): List<String> =
    launcher.requests.map { request -> phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride)) }

  private fun entry(launcher: RuntimeRecordingLauncher): PhaseRunEntry {
    val config =
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        branchSetup = committedRepoBranchSetup(),
        repoRoot = repoRoot,
        launcher = launcher,
      )
    val harness =
      telemetryRunnerHarness(
        runtimeConfig = config,
        databaseFactory = { database },
      )
    return phaseRunEntry(harness.strategies, config.harnessGitOperations, database, clock, harness.runLoopEntry)
  }

  private companion object {
    const val ISSUE_KEY = "SKILL-900"
    const val PREPLAN = "preplan"
    const val PLAN = "plan"
    const val FEATURE_SPECS = ".feature-specs"
  }
}
