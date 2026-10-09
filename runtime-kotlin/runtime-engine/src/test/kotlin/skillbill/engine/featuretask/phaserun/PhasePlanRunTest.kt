package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.committedRepoBranchSetup
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.error.featuretask.InMemorySkeletonDefinitionRequiredError
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
  fun `plan is a durable workflow, so the in-memory phase entry refuses it and launches nothing`() {
    val launcher = RuntimeRecordingLauncher { facts("") }

    val error =
      assertFailsWith<InMemorySkeletonDefinitionRequiredError> {
        entry(launcher).run(
          PhaseRunRequest(
            definitionId = SkeletonDefinition.PLAN.id,
            repoRoot = repoRoot,
            invokedAgentId = "claude",
            intake = "SKILL-900 split the runtime work into ordered subtasks",
          ),
        )
      }

    assertEquals(SkeletonDefinition.PLAN.id, error.definitionId)
    assertEquals(emptyList(), launcher.requests)
    database.assertNoDurableWorkflowState()
  }

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
}
