package skillbill.engine.featuretask.phaserun

import skillbill.engine.COMMITTED_HEAD_SHA
import skillbill.engine.RuntimeHarnessConfig
import skillbill.engine.RuntimeRecordingLauncher
import skillbill.engine.committedRepoBranchSetup
import skillbill.engine.facts
import skillbill.engine.phaseIdFromPrompt
import skillbill.engine.telemetryRunnerHarness
import skillbill.engine.validJsonOutput
import skillbill.error.featuretask.PhaseSpecRequiredError
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PhaseImplementRunTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-phase-implement-repo")
  private val home: Path = Files.createTempDirectory("skillbill-phase-implement-home")
  private val clock: Clock = Clock.systemUTC()
  private val database = phaseRunDatabase(home, clock)
  private val branchSetup = committedRepoBranchSetup()
  private val launcher =
    RuntimeRecordingLauncher { request ->
      val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
      if (phaseId == IMPLEMENT) {
        Files.createDirectories(repoRoot.resolve(EDITED_FILE).parent)
        Files.writeString(repoRoot.resolve(EDITED_FILE), EDITED_CONTENT)
      }
      facts(validJsonOutput(phaseId))
    }

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
  }

  @Test
  fun `implement over a governed spec leaves the agent's edit uncommitted and writes no workflow state`() {
    val spec = repoRoot.resolve(".feature-specs/$ISSUE_KEY-phase-implement/spec.md")
    Files.createDirectories(spec.parent)
    Files.writeString(
      spec,
      "# $ISSUE_KEY - phase implement\n\n## Acceptance Criteria\n\n1. The feature is implemented.\n",
    )

    val result = entry().run(implementRequest(ISSUE_KEY))

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertEquals(listOf(IMPLEMENT, SIMPLIFY), result.completedStepIds)
    assertEquals(EDITED_CONTENT, Files.readString(repoRoot.resolve(EDITED_FILE)))
    branchSetup.gitOperations.assertNoCommitOrCheckpointRef(COMMITTED_HEAD_SHA)
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `implement whose intake names no governed spec fails before launching an agent`() {
    assertFailsWith<PhaseSpecRequiredError> { entry().run(implementRequest("SKILL-902")) }

    assertEquals(emptyList(), launcher.requests)
    database.assertNoDurableWorkflowState()
  }

  private fun implementRequest(intake: String): PhaseRunRequest =
    PhaseRunRequest(
      definitionId = SkeletonDefinition.IMPLEMENT.id,
      repoRoot = repoRoot,
      invokedAgentId = "claude",
      intake = intake,
    )

  private fun entry(): PhaseRunEntry {
    val runner =
      telemetryRunnerHarness(
        runtimeConfig = RuntimeHarnessConfig(branchSetup = branchSetup, repoRoot = repoRoot, launcher = launcher),
        databaseFactory = { database },
      ).runner
    return phaseRunEntry(runner, database, clock)
  }

  private companion object {
    const val ISSUE_KEY = "SKILL-901"
    const val IMPLEMENT = "implement"
    const val SIMPLIFY = "simplify"
    const val EDITED_FILE = "src/Feature.kt"
    const val EDITED_CONTENT = "class Feature\n"
  }
}
