package skillbill.engine.featuretask.slot.standalonereview

import skillbill.config.model.PhaseModelDirective
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.model.review.ReviewInvocation
import skillbill.engine.featuretask.phaserun.PhaseRunEntryFixture
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.phaserun.entry
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.runner.BranchSetupTestConfig
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.committedRepoBranchSetup
import skillbill.engine.featuretask.runner.defaultPhaseOutput
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.slot.codereview.DELEGATED_SPECIALIST_ISSUE_KEY
import skillbill.engine.featuretask.slot.codereview.DELEGATED_REVIEWED_PATH
import skillbill.engine.featuretask.slot.codereview.LaneScript
import skillbill.engine.featuretask.slot.codereview.scriptedDelegatedReviewRunner
import skillbill.engine.featuretask.slot.scriptedReviewPhaseRunner
import skillbill.install.model.SupportedAgent
import skillbill.model.EnvironmentContext
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PRESENT_FINDINGS
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StandaloneReviewEffortPropagationTest {
  private val repoRoot = Files.createTempDirectory("standalone-review-effort-repo")
  private val home = Files.createTempDirectory("standalone-review-effort-home")
  private val clock = Clock.systemUTC()
  private val database = phaseRunDatabase(home, clock)
  private val git = committedRepoBranchSetup().gitOperations
  private val lanes = LaneScript().also { it.fixed = true }

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
  }

  @Test
  fun `explicit OpenCode effort reaches the delegated standalone review`() {
    val source = repoRoot.resolve(DELEGATED_REVIEWED_PATH)
    Files.createDirectories(source.parent)
    Files.writeString(source, "val connection = open()\n")
    val runtime =
      telemetryRunnerHarness(
        runtimeConfig =
          RuntimeHarnessConfig(
            seedDurableWorkflow = false,
            branchSetup = BranchSetupTestConfig(gitOperations = git),
            repoRoot = repoRoot,
            launcher = RuntimeRecordingLauncher { request -> facts(defaultPhaseOutput(request)) },
            agentAssignment = FeatureTaskRuntimeAgentAssignment(
              perPhaseAgentIds = mapOf(PHASE_REVIEW to SupportedAgent.OPENCODE.id),
            ),
            reviewRunner = scriptedReviewPhaseRunner { error("delegated review must not use the inline runner") },
            delegatedReviewRunner =
              scriptedDelegatedReviewRunner(
                database,
                home,
                lanes,
                EnvironmentContext(environment = mapOf("HOME" to home.toString()), userHome = home),
              ),
          ),
        databaseFactory = { database },
      )
    val entry =
      PhaseRunEntryFixture(
        strategies = runtime.strategies,
        gitOperations = git,
        database = database,
        clock = clock,
        runLoopEntry = runtime.runLoopEntry,
      ).entry()

    val result =
      entry.run(
        PhaseRunRequest(
          definitionId = SkeletonDefinition.REVIEW.id,
          repoRoot = repoRoot,
          invokedAgentId = SupportedAgent.OPENCODE.id,
          codeReviewMode = CodeReviewExecutionMode.DELEGATED,
          reviewInvocation = ReviewInvocation(),
          modelAssignment =
            FeatureTaskRuntimeModelAssignment(
              perPhaseDirectives = mapOf(PHASE_PRESENT_FINDINGS to PhaseModelDirective("provider/new-model", "high")),
            ),
        ),
      )

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    val launches = lanes.launches.filter { it.skillRunRequest.issueKey == DELEGATED_SPECIALIST_ISSUE_KEY }
    assertTrue(launches.isNotEmpty(), "The delegated review must launch a specialist.")
    assertTrue(launches.all { it.skillRunRequest.effortOverride == "high" })
  }
}
