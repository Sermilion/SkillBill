package skillbill.engine.featuretask.phaserun

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.DECOMPOSE_PLAN_OUTPUT
import skillbill.engine.RuntimeHarnessConfig
import skillbill.engine.RuntimeRecordingLauncher
import skillbill.engine.committedRepoBranchSetup
import skillbill.engine.facts
import skillbill.engine.phaseIdFromPrompt
import skillbill.engine.telemetryRunnerHarness
import skillbill.engine.validJsonOutput
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
  fun `a blocked preplan launches no plan agent and writes no spec files or workflow state`() {
    val launcher = launcher { phaseId -> if (phaseId == PREPLAN) BLOCKED_PREPLAN_OUTPUT else DECOMPOSE_PLAN_OUTPUT }

    val result = entry(launcher).run(planRequest())

    assertIs<PhaseRunResult.Blocked>(result, result.toString())
    assertEquals(PREPLAN, result.stepId)
    assertEquals(listOf(PREPLAN), launchedPhaseIds(launcher))
    assertEquals(emptyList(), planBundleDirectories(), "a blocked preplan must write no spec files")
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `a decompose plan writes a governed spec bundle and completes with its paths`() {
    val launcher = launcher { phaseId -> if (phaseId == PLAN) DECOMPOSE_PLAN_OUTPUT else validJsonOutput(phaseId) }

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
  fun `a direct plan blocks at plan and writes no spec bundle`() {
    val launcher = launcher(::validJsonOutput)

    val result = entry(launcher).run(planRequest())

    assertIs<PhaseRunResult.Blocked>(result, result.toString())
    assertEquals(PLAN, result.stepId)
    assertEquals(emptyList(), planBundleDirectories(), "a direct plan must write no spec files")
    database.assertNoDurableWorkflowState()
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
    val runner =
      telemetryRunnerHarness(
        runtimeConfig =
          RuntimeHarnessConfig(branchSetup = committedRepoBranchSetup(), repoRoot = repoRoot, launcher = launcher),
        databaseFactory = { database },
      ).runner
    return phaseRunEntry(runner, database, clock)
  }

  private companion object {
    const val ISSUE_KEY = "SKILL-900"
    const val PREPLAN = "preplan"
    const val PLAN = "plan"
    const val FEATURE_SPECS = ".feature-specs"
    const val BLOCKED_PREPLAN_OUTPUT =
      """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"preplan","status":"blocked",""" +
        """"failure_disposition":"needs_user_action","summary":"The intake names no reachable scope.",""" +
        """"produced_outputs":{"value":"The intake names no reachable scope."}}"""
  }
}
