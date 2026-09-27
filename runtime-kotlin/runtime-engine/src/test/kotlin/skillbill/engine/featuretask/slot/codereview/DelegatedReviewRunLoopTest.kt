package skillbill.engine.featuretask.slot.codereview

import skillbill.application.realFeatureTaskRuntimePhaseOutputValidator
import skillbill.application.review.verification.ReviewClaimVerificationRunner
import skillbill.engine.BranchSetupTestConfig
import skillbill.engine.REVIEW_FIX_BLOCKER_FINDING_ID
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.RuntimeHarnessConfig
import skillbill.engine.RuntimeRecordingLauncher
import skillbill.engine.TelemetryRunnerHarness
import skillbill.engine.WORKFLOW_ID
import skillbill.engine.auditSatisfiedOutput
import skillbill.engine.committedRepoBranchSetup
import skillbill.engine.defaultPhaseOutput
import skillbill.engine.facts
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.validation.passed
import skillbill.engine.phaseIdFromPrompt
import skillbill.engine.telemetryRunnerHarness
import skillbill.engine.validJsonOutput
import skillbill.engine.verifyFindingsOutput
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.agentrun.model.READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class DelegatedReviewRunLoopTest {
  @Test
  fun `a delegated-bound run reviews through bounded lanes and carries verified findings to implement_fix`() {
    val git = committedRepoBranchSetup().gitOperations.also { it.repositoryFingerprintValue = "before-fix" }
    val lanes = LaneScript()
    val launcher = phaseLauncher(git, lanes)

    withDelegatedRun(git, lanes, launcher) { harness, report ->
      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
      val launchedPhases = launcher.requests.mapNotNull { it.skillRunRequest.promptOverride }.map(::phaseIdFromPrompt)
      assertFalse(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW in launchedPhases,
        "the delegated review must not open a file-editing agent session for the review step",
      )
      assertBoundedDelegatedLanes(lanes)
      assertTrue(promptFor(launcher, "verify_findings").contains(DELEGATED_FINDING_MESSAGE))
      assertEquals(1, launchedPhases.count { it == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX })
      assertTrue(promptFor(launcher, "implement_fix").contains(DELEGATED_FINDING_MESSAGE))
      val reviewFixEdges =
        harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty().filter {
          it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
            it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
        }
      assertEquals(1, reviewFixEdges.size)
    }
  }

  @Test
  fun `a delegated review whose lane edits the worktree blocks instead of committing the edit`() {
    val git = committedRepoBranchSetup().gitOperations.also { it.repositoryFingerprintValue = "before-fix" }
    val lanes = LaneScript(onSpecialistLaunch = { git.worktreeStatusValue = " M src/Foo.kt\n M $LANE_EDITED_PATH" })
    val launcher = phaseLauncher(git, lanes)

    withDelegatedRun(git, lanes, launcher) { _, report ->
      val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report, report.toString())
      assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW, blocked.lastIncompletePhase)
      assertTrue(blocked.blockedReason.contains("read-only"), blocked.blockedReason)
      assertTrue(blocked.blockedReason.contains(LANE_EDITED_PATH), blocked.blockedReason)
      val launchedPhases = launcher.requests.mapNotNull { it.skillRunRequest.promptOverride }.map(::phaseIdFromPrompt)
      assertFalse(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS in launchedPhases)
    }
  }

  private fun withDelegatedRun(
    git: RecordingWorkflowGitOperations,
    lanes: LaneScript,
    launcher: RuntimeRecordingLauncher,
    assertions: (TelemetryRunnerHarness, FeatureTaskRuntimeRunReport) -> Unit,
  ) {
    val repoRoot = Files.createTempDirectory("delegated-review-run")
    val home = Files.createTempDirectory("delegated-review-db")
    try {
      val database =
        sqliteSessionFactoryForTests(
          userHome = home,
          dbPathOverride = home.resolve("metrics.db").toString(),
          environment = emptyMap(),
        )
      val harness =
        telemetryRunnerHarness(
          launcher = launcher,
          validator = realFeatureTaskRuntimePhaseOutputValidator,
          runtimeConfig =
            RuntimeHarnessConfig(
              branchSetup = BranchSetupTestConfig(gitOperations = git),
              repoRoot = repoRoot,
              agentAssignment =
                FeatureTaskRuntimeAgentAssignment(
                  perPhaseAgentIds = mapOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to "claude"),
                ),
              validationGateRunner =
                object : ValidationGateRunner {
                  override fun run(request: ValidationGateRunRequest) = passed()
                },
              launcher = launcher,
              delegatedReviewRunner = scriptedDelegatedReviewRunner(database, home, lanes),
            ),
          databaseFactory = { database },
        )

      assertions(harness, harness.runner.run(harness.request))
    } finally {
      repoRoot.toFile().deleteRecursively()
      home.toFile().deleteRecursively()
    }
  }

  private fun assertBoundedDelegatedLanes(lanes: LaneScript) {
    val specialists = lanes.launches.filter { it.skillRunRequest.issueKey == DELEGATED_SPECIALIST_ISSUE_KEY }
    assertTrue(specialists.isNotEmpty())
    specialists.forEach { lane ->
      assertTrue(lane.skillRunRequest.reviewFanOut, "specialist lanes run in the delegated fan-out shape")
      assertNotNull(lane.skillRunRequest.reviewEvidenceEndpoint, "specialists read evidence through the broker")
    }
    assertTrue(lanes.launches.any { it.skillRunRequest.issueKey == ReviewClaimVerificationRunner.ISSUE_KEY })
    lanes.launches.map { it.skillRunRequest }.forEach { lane ->
      assertEquals(
        READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES.minutes,
        lane.progressIdleTimeout,
        "${lane.issueKey} lane must carry the progress bound",
      )
      assertFalse(lane.readOnlyPhase, "${lane.issueKey} lane must stop once silent past the bound")
    }
  }

  private fun phaseLauncher(
    git: RecordingWorkflowGitOperations,
    lanes: LaneScript,
  ): RuntimeRecordingLauncher {
    var verifyLaunches = 0
    return RuntimeRecordingLauncher { request ->
      when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
        "audit" -> facts(auditSatisfiedOutput())
        "verify_findings" -> {
          verifyLaunches += 1
          val verified = if (verifyLaunches == 1) listOf(REVIEW_FIX_BLOCKER_FINDING_ID) else emptyList()
          facts(verifyFindingsOutput(verified))
        }
        "implement_fix" -> {
          lanes.fixed = true
          git.repositoryFingerprintValue = "after-fix"
          git.goalReviewTrackedDelta = "delegated-fix\n"
          facts(validJsonOutput(phaseId))
        }
        else -> facts(defaultPhaseOutput(request))
      }
    }
  }

  private fun promptFor(
    launcher: RuntimeRecordingLauncher,
    phaseId: String,
  ): String =
    launcher.requests
      .mapNotNull { it.skillRunRequest.promptOverride }
      .first { phaseIdFromPrompt(it) == phaseId }

  private companion object {
    const val LANE_EDITED_PATH = "src/LaneEdit.kt"
  }
}
