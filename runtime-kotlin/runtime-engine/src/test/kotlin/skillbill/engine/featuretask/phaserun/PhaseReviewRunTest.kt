package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.review.ReviewInvocation
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoop
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunLoopDurableState
import skillbill.engine.featuretask.runner.BranchSetupTestConfig
import skillbill.engine.featuretask.runner.REVIEW_BLOCKER_MESSAGE
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.SlotBaselineSqlite
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.committedRepoBranchSetup
import skillbill.engine.featuretask.runner.defaultPhaseOutput
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.phaseIdFromPrompt
import skillbill.engine.featuretask.runner.satisfiedAuditLauncher
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.REVIEW_FIX_BLOCKER_FINDING_ID
import skillbill.engine.featuretask.slot.codereview.DELEGATED_REVIEWED_PATH
import skillbill.engine.featuretask.slot.codereview.DELEGATED_SPECIALIST_ISSUE_KEY
import skillbill.engine.featuretask.slot.codereview.LaneScript
import skillbill.engine.featuretask.slot.codereview.scriptedDelegatedReviewRunner
import skillbill.engine.featuretask.slot.reviewStepOutput
import skillbill.engine.featuretask.slot.scriptedReviewPhaseRunner
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.engine.featuretask.slot.verifyFindingsOutput
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.PhaseSlotFailureCode
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PRESENT_FINDINGS
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PhaseReviewRunTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-phase-review-repo")
  private val home: Path = Files.createTempDirectory("skillbill-phase-review-home")
  private val clock: Clock = Clock.systemUTC()
  private val database = phaseRunDatabase(home, clock)
  private val git = committedRepoBranchSetup().gitOperations.also { it.repositoryFingerprintValue = "before-fix" }
  private val headBefore = git.headCommitShaValue
  private val source: Path =
    repoRoot.resolve(DELEGATED_REVIEWED_PATH).also { path ->
      Files.createDirectories(path.parent)
      Files.writeString(path, LEAKY_SOURCE)
    }

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
  }

  @Test
  fun `inline phase review reports defects and preserves the reviewed file without launching repair`() {
    var reviews = 0
    val launcher = fixLauncher()
    val result =
      inlineEntry(launcher) {
        reviews += 1
        BLOCKER_REVIEW
      }
        .run(reviewRequest(CodeReviewExecutionMode.INLINE, reviewRunId = REVIEW_RUN_ID))

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertEquals(listOf(PHASE_PRESENT_FINDINGS), result.completedStepIds)
    assertEquals(LEAKY_SOURCE, Files.readString(source))
    assertEquals(1, reviews)
    assertTrue(launcher.requests.isEmpty(), "report-only review must never launch verification or repair")
    assertTrue(requireNotNull(result.reviewResult).output.contains("verdict: changes_requested"))
    git.assertNoCommitOrCheckpointRef(headBefore)
    database.assertNoDurableWorkflowState()
    assertReportRecorded()
  }

  @Test
  fun `delegated phase review reports defects through bounded lanes and preserves the reviewed file`() {
    val lanes = LaneScript()
    val launcher = fixLauncher()
    val result =
      delegatedEntry(launcher, lanes)
        .run(reviewRequest(CodeReviewExecutionMode.DELEGATED, reviewRunId = REVIEW_RUN_ID))

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertEquals(listOf(PHASE_PRESENT_FINDINGS), result.completedStepIds)
    assertTrue(lanes.launches.any { it.skillRunRequest.issueKey == DELEGATED_SPECIALIST_ISSUE_KEY })
    assertEquals(LEAKY_SOURCE, Files.readString(source))
    assertTrue(launcher.requests.isEmpty(), "report-only review must never launch verification or repair")
    assertTrue(requireNotNull(result.reviewResult).output.contains("verdict: changes_requested"))
    git.assertNoCommitOrCheckpointRef(headBefore)
    database.assertNoDurableWorkflowState()
    assertReportRecorded()
  }

  @Test
  fun `an incomplete report blocks with retained findings and never launches repair`() {
    val launcher = fixLauncher()
    val result =
      inlineEntry(launcher) { BLOCKER_REVIEW.substringBefore("\nverdict:") }
        .run(reviewRequest(CodeReviewExecutionMode.INLINE))

    assertIs<PhaseRunResult.Blocked>(result, result.toString())
    assertTrue(result.reason.contains("verdict"))
    assertTrue(requireNotNull(result.reviewResult).output.contains("F-001"))
    assertEquals(LEAKY_SOURCE, Files.readString(source))
    assertTrue(launcher.requests.isEmpty())
    git.assertNoCommitOrCheckpointRef(headBefore)
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `an omitted, inline, or auto mode runs the inline review strategy even with the delegated runner bound`() {
    listOf(null, CodeReviewExecutionMode.INLINE, CodeReviewExecutionMode.AUTO).forEach { mode ->
      var reviews = 0
      val lanes = LaneScript()
      val entry =
        delegatedEntry(fixLauncher(), lanes) {
          reviews += 1
          APPROVED_REVIEW
        }

      val result = entry.run(reviewRequest(mode))

      assertIs<PhaseRunResult.Completed>(result, "$mode: $result")
      assertEquals(1, reviews, "$mode must review through the inline agent session")
      assertTrue(lanes.launches.isEmpty(), "$mode must not fan out to delegated lanes")
    }
  }

  @Test
  fun `an omitted target reviews uncommitted changes when the worktree is dirty and HEAD when it is clean`() {
    listOf(DIRTY_STATUS to UNCOMMITTED_OPENING_LINE, "" to HEAD_OPENING_LINE).forEach { (status, openingLine) ->
      git.worktreeStatusValue = status
      val directives = mutableListOf<String>()

      inlineEntryOver(fixLauncher(), directiveRecordingReviewRunner(directives)).run(reviewRequest(mode = null))

      assertEquals(openingLine, directives.single().lineSequence().first(), "worktree status '$status'")
    }
  }

  @Test
  fun `a commit target that does not resolve is an unknown-target error and launches no agent`() {
    git.onResolveCommit = { revision ->
      WorkflowGitOperationResult.Failed(error = "unknown revision").takeIf { revision == MISSING_BRANCH }
    }
    val launcher = fixLauncher()
    val directives = mutableListOf<String>()
    val entry = inlineEntryOver(launcher, directiveRecordingReviewRunner(directives))

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        entry.run(reviewRequest(mode = null, target = ReviewTarget.Commit(MISSING_BRANCH)))
      }
    assertEquals(PhaseSlotFailureCode.UNKNOWN_PHASE_REVIEW_TARGET, error.code)
    assertTrue(directives.isEmpty(), "no review agent may launch")
    assertTrue(launcher.requests.isEmpty(), "no step agent may launch")
  }

  @Test
  fun `phase runs and durable runs drive the same run loop entry`() {
    val loopEntry = RecordingRunLoopEntry()
    val phaseEntry = inlineEntry(fixLauncher(), runLoopEntry = loopEntry) { APPROVED_REVIEW }
    val durable = telemetryRunnerHarness(RuntimeHarnessConfig(launcher = satisfiedAuditLauncher()))

    phaseEntry.run(reviewRequest(mode = null))
    val durableReport = durable.withRunLoopEntry(loopEntry).run(durable.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(durableReport, durableReport.toString())
    assertEquals(2, loopEntry.runStates.size, loopEntry.runStates.toString())
    assertIs<InMemoryPhaseRunState>(loopEntry.runStates[0])
    assertIs<FeatureTaskRuntimeRunLoopDurableState>(loopEntry.runStates[1])
  }

  private fun reviewRequest(
    mode: CodeReviewExecutionMode?,
    target: ReviewTarget? = null,
    reviewRunId: String? = null,
  ): PhaseRunRequest =
    PhaseRunRequest(
      definitionId = SkeletonDefinition.REVIEW.id,
      repoRoot = repoRoot,
      invokedAgentId = REVIEW_AGENT,
      codeReviewMode = mode,
      reviewInvocation = ReviewInvocation(target = target, reviewRunId = reviewRunId),
    )

  private fun directiveRecordingReviewRunner(directives: MutableList<String>): PhaseRunner =
    object : PhaseRunner {
      override fun run(
        input: PhaseStepInput,
        state: PhaseLaunchState,
      ): PhaseStepOutput {
        directives += input.directive
        return reviewStepOutput(APPROVED_REVIEW)
      }
    }

  private fun fixLauncher(
    verifyEveryPass: Boolean = false,
    onFix: () -> Unit = {},
  ): RuntimeRecordingLauncher {
    var verifies = 0
    var fixes = 0
    return RuntimeRecordingLauncher { request ->
      when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
        "verify_findings" -> {
          verifies += 1
          val verified = if (verifyEveryPass || verifies == 1) listOf(REVIEW_FIX_BLOCKER_FINDING_ID) else emptyList()
          facts(verifyFindingsOutput(verified))
        }
        PHASE_IMPLEMENT_FIX -> {
          fixes += 1
          Files.writeString(source, FIXED_SOURCE)
          git.repositoryFingerprintValue = "after-fix-$fixes"
          git.goalReviewTrackedDelta = "phase-review-fix-$fixes\n"
          onFix()
          facts(validJsonOutput(phaseId))
        }
        else -> facts(defaultPhaseOutput(request))
      }
    }
  }

  private fun inlineEntry(
    launcher: RuntimeRecordingLauncher,
    runLoopEntry: FeatureTaskRuntimeRunLoopEntry = TestFeatureTaskRuntimeRunLoopEntry(),
    review: () -> String,
  ): PhaseRunEntry = inlineEntryOver(launcher, scriptedReviewPhaseRunner(review), runLoopEntry)

  private fun inlineEntryOver(
    launcher: RuntimeRecordingLauncher,
    reviewRunner: PhaseRunner,
    runLoopEntry: FeatureTaskRuntimeRunLoopEntry = TestFeatureTaskRuntimeRunLoopEntry(),
  ): PhaseRunEntry =
    entryFor(
      RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        repoRoot = repoRoot,
        launcher = launcher,
        reviewRunner = reviewRunner,
      ),
      runLoopEntry,
    )

  private fun delegatedEntry(
    launcher: RuntimeRecordingLauncher,
    lanes: LaneScript,
    inlineReview: () -> String = { error("the delegated review must not open an inline review session") },
  ): PhaseRunEntry =
    entryFor(
      RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        repoRoot = repoRoot,
        launcher = launcher,
        agentAssignment = FeatureTaskRuntimeAgentAssignment(perPhaseAgentIds = mapOf(PHASE_REVIEW to REVIEW_AGENT)),
        reviewRunner = scriptedReviewPhaseRunner(inlineReview),
        delegatedReviewRunner = scriptedDelegatedReviewRunner(database, home, lanes),
      ),
    )

  private fun entryFor(
    config: RuntimeHarnessConfig,
    runLoopEntry: FeatureTaskRuntimeRunLoopEntry = TestFeatureTaskRuntimeRunLoopEntry(),
  ): PhaseRunEntry {
    val harness =
      telemetryRunnerHarness(runtimeConfig = config.copy(seedDurableWorkflow = false), databaseFactory = {
        database
      })
    return phaseRunEntry(
      harness.strategies,
      config.harnessGitOperations,
      database,
      clock,
      harness.runLoopEntry.delegateTo(runLoopEntry),
    )
  }

  private fun assertReportRecorded() {
    val rows = SlotBaselineSqlite.rows(database.resolveDbPath(), "review_runs")
    assertEquals(1, rows.size)
    assertEquals(REVIEW_RUN_ID, rows.single()["review_run_id"])
    assertTrue(SlotBaselineSqlite.rows(database.resolveDbPath(), "review_run_pass_claims").isNotEmpty())
  }

  private class RecordingRunLoopEntry : TestFeatureTaskRuntimeRunLoopEntry() {
    val runStates = mutableListOf<PhaseRunState>()

    override fun run(
      context: FeatureTaskRuntimeRunLoopContext,
      beforeDrive: (FeatureTaskRuntimeRunLoop) -> Unit,
    ): FeatureTaskRuntimeRunReport {
      runStates += context.runState
      return super.run(context, beforeDrive)
    }
  }

  private companion object {
    const val REVIEW_AGENT = "claude"
    const val LEAKY_SOURCE = "val connection = open()\n"
    const val FIXED_SOURCE = "open().use { connection -> connection }\n"
    const val BLOCKER_REVIEW =
      "- [F-001] Blocker | High | $DELEGATED_REVIEWED_PATH:1 | $REVIEW_BLOCKER_MESSAGE\nverdict: changes_requested"
    const val APPROVED_REVIEW = "NO_FINDINGS\nverdict: approved"
    const val REVIEW_RUN_ID = "rvw-20260927-120000-phrv"
    const val DIRTY_STATUS = " M src/Foo.kt"
    const val MISSING_BRANCH = "no-such-branch"
    const val UNCOMMITTED_OPENING_LINE =
      "Review the uncommitted changes in this repository workspace against `HEAD`, including untracked files."
    const val HEAD_OPENING_LINE = "Review commit `HEAD` against its first parent `HEAD^`."
  }
}
