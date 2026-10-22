package skillbill.engine.featuretask.phaserun

import skillbill.application.review.model.ParallelCodeReviewPlanned
import skillbill.application.review.model.ParallelCodeReviewPlanningFailure
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
import skillbill.engine.featuretask.slot.codereview.CodeQualityLaneSection
import skillbill.engine.featuretask.slot.codereview.DELEGATED_REVIEWED_PATH
import skillbill.engine.featuretask.slot.codereview.DELEGATED_SPECIALIST_ISSUE_KEY
import skillbill.engine.featuretask.slot.codereview.InlineCodeQualityRubricResolver
import skillbill.engine.featuretask.slot.codereview.LaneScript
import skillbill.engine.featuretask.slot.codereview.scriptedDelegatedReviewRunner
import skillbill.engine.featuretask.slot.reviewStepOutput
import skillbill.engine.featuretask.slot.scriptedReviewPhaseRunner
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.engine.featuretask.slot.verifyFindingsOutput
import skillbill.error.featuretask.UnknownPhaseReviewTargetError
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.model.ParallelReviewSeverity
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
import kotlin.test.assertFalse
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
  fun `standalone inline review adds the code-quality lane without altering the rest of the prompt`() {
    val rubric = "Prefer cohesive helpers.\nIgnore: security defects belong to the security specialist."
    val baseline = standaloneInlineDirective(InlineCodeQualityRubricResolver { ParallelCodeReviewPlanned.Ready(null) })
    val laned = standaloneInlineDirective(InlineCodeQualityRubricResolver { ParallelCodeReviewPlanned.Ready(rubric) })

    val section = CodeQualityLaneSection.render(rubric)
    assertEquals(baseline, laned.replace("\n$section", ""))
    assertTrue(rubric in laned)
    assertTrue("adds a code-quality lane" in section && "responsible for every review area" in section)
    assertTrue("apply only to delegated review" in section)
  }

  @Test
  fun `a failed rubric resolution does not block standalone inline review and records a warning`() {
    val diagnostics = RecordingDiagnostics()
    val directive =
      standaloneInlineDirective(
        InlineCodeQualityRubricResolver {
          ParallelCodeReviewPlanned.Failed(ParallelCodeReviewPlanningFailure.DiffUnresolved(RUBRIC_FAILURE))
        },
        diagnostics,
      )

    assertFalse(CodeQualityLaneSection.HEADING in directive)
    assertTrue(diagnostics.warnings.single().contains(RUBRIC_FAILURE), diagnostics.warnings.toString())
  }

  @Test
  fun `a failed rubric resolution does not block the full-run inline review and records a warning`() {
    val diagnostics = RecordingDiagnostics()
    val directives = mutableListOf<String>()
    val durable =
      telemetryRunnerHarness(
        runtimeConfig =
          RuntimeHarnessConfig(
            repoRoot = repoRoot,
            branchSetup = BranchSetupTestConfig(gitOperations = git),
            launcher = fixLauncher(),
            reviewRunner = directiveRecordingReviewRunner(directives),
            diagnostics = diagnostics,
            inlineRubricResolver =
              InlineCodeQualityRubricResolver {
                ParallelCodeReviewPlanned.Failed(ParallelCodeReviewPlanningFailure.DiffUnresolved(RUBRIC_FAILURE))
              },
          ),
        databaseFactory = { database },
      )

    durable.withRunLoopEntry(RecordingRunLoopEntry()).run(durable.request)

    val reviews = directives.filter { "Fix every Blocker and Major finding" in it }
    assertTrue(reviews.isNotEmpty(), "the full-run review must still launch")
    assertTrue(reviews.none { CodeQualityLaneSection.HEADING in it })
    assertTrue(diagnostics.warnings.any { RUBRIC_FAILURE in it }, diagnostics.warnings.toString())
  }

  @Test
  fun `a commit target that does not resolve is an unknown-target error and launches no agent`() {
    git.onResolveCommit = { revision ->
      WorkflowGitOperationResult.Failed(error = "unknown revision").takeIf { revision == MISSING_BRANCH }
    }
    val launcher = fixLauncher()
    val directives = mutableListOf<String>()
    val entry = inlineEntryOver(launcher, directiveRecordingReviewRunner(directives))

    assertFailsWith<UnknownPhaseReviewTargetError> {
      entry.run(reviewRequest(mode = null, target = ReviewTarget.Commit(MISSING_BRANCH)))
    }
    assertTrue(directives.isEmpty(), "no review agent may launch")
    assertTrue(launcher.requests.isEmpty(), "no step agent may launch")
  }

  @Test
  fun `phase runs and durable runs drive the same run loop entry`() {
    val loopEntry = RecordingRunLoopEntry()
    val phaseEntry = inlineEntry(fixLauncher(), runLoopEntry = loopEntry) { APPROVED_REVIEW }
    val durable =
      telemetryRunnerHarness(
        runtimeConfig = RuntimeHarnessConfig(launcher = satisfiedAuditLauncher()),
        databaseFactory = { database },
      )

    phaseEntry.run(reviewRequest(mode = null))
    val durableReport = durable.withRunLoopEntry(loopEntry).run(durable.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(durableReport, durableReport.toString())
    assertEquals(2, loopEntry.runStates.size, loopEntry.runStates.toString())
    assertIs<InMemoryPhaseRunState>(loopEntry.runStates[0])
    val durableState = assertIs<FeatureTaskRuntimeRunLoopDurableState>(loopEntry.runStates[1])
    val durableReviewRows = SlotBaselineSqlite.rows(database.resolveDbPath(), "review_runs")
    assertTrue(durableReviewRows.size >= 2, "The durable completed review must persist its own review_runs parent.")
    assertEquals(0, database.transaction { it.reviews.reviewStats(null).stats.totalFindings })
    val nonEmptyResult =
      inlineEntry(fixLauncher()) { BLOCKER_REVIEW }
        .run(reviewRequest(CodeReviewExecutionMode.INLINE))
        .reviewResult
    val findingsBeforeDurablePersist = database.transaction { it.reviews.reviewStats(null).stats.totalFindings }
    val review = requireNotNull(nonEmptyResult)
    val nit = review.mergeResult.findings.single().copy(fNumber = "F-002", severity = ParallelReviewSeverity.NIT)
    val reviewWithNit = review.copy(mergeResult = review.mergeResult.copy(findings = review.mergeResult.findings + nit))
    durableState.recordReviewRun("rvw-durable-findings", reviewWithNit, laneTelemetryRecorded = true)

    assertEquals(
      1,
      SlotBaselineSqlite.rows(database.resolveDbPath(), "review_runs")
        .count { it["review_run_id"] == "rvw-durable-findings" },
    )
    assertEquals(
      2,
      SlotBaselineSqlite.rows(database.resolveDbPath(), "findings")
        .count { it["review_run_id"] == "rvw-durable-findings" },
    )
    assertEquals(
      findingsBeforeDurablePersist + 2,
      database.transaction { it.reviews.reviewStats(null).stats.totalFindings },
      "Stats without a run id must include durable review findings.",
    )
    assertEquals(
      1,
      database.transaction {
        it.reviews.reviewStats(
          "rvw-durable-findings",
        ).stats.unresolvedSeverityCounts[ParallelReviewSeverity.NIT.displayName]
      },
    )
    assertDurableReviewLedgerLink()
  }

  private fun assertDurableReviewLedgerLink() {
    val goalFinding =
      UnaddressedFinding(
        issueKey = "SKILL-433",
        subtaskId = 1,
        workflowId = "workflow-433",
        reviewPassNumber = 1,
        findingOrdinal = 1,
        severity = "blocker",
        issueCategory = "other",
        location = DELEGATED_REVIEWED_PATH,
        summary = REVIEW_BLOCKER_MESSAGE,
        reviewRunId = "rvw-durable-findings",
        findingId = "F-001",
      )
    database.transaction {
      it.unaddressedFindings.replaceLedgerForPass("workflow-433", 1, listOf(goalFinding))
    }
    assertEquals(
      listOf(goalFinding),
      database.transaction { it.unaddressedFindings.fetchLedger("SKILL-433") },
    )
  }

  @Test
  fun `durable goal review persists its parent before linking ledger findings`() {
    val loopEntry = RecordingRunLoopEntry()
    val durable =
      telemetryRunnerHarness(
        runtimeConfig =
          RuntimeHarnessConfig(
            repoRoot = repoRoot,
            branchSetup = BranchSetupTestConfig(gitOperations = git),
            launcher = fixLauncher(),
            reviewRunner = scriptedReviewPhaseRunner { BLOCKER_REVIEW },
          ),
        databaseFactory = { database },
      )

    durable.withRunLoopEntry(loopEntry).run(durable.request)

    val reviewRunIds =
      SlotBaselineSqlite.rows(database.resolveDbPath(), "review_runs").mapNotNull {
        it["review_run_id"] as? String
      }.toSet()
    val linkedLedgerRows =
      SlotBaselineSqlite.rows(database.resolveDbPath(), "unaddressed_findings")
        .filter { it["review_run_id"] != null }
    val reviewSettlements =
      SlotBaselineSqlite.rows(database.resolveDbPath(), "feature_task_phase_settlements")
        .filter { it["phase_id"] == PHASE_REVIEW }
    assertTrue(linkedLedgerRows.isNotEmpty(), "The durable goal review must store its findings in the ledger.")
    assertTrue(
      linkedLedgerRows.all { it["review_run_id"] in reviewRunIds },
      "Every ledger review_run_id must have a persisted review_runs parent.",
    )
    assertTrue(reviewSettlements.isNotEmpty(), "A completed durable review must write its phase settlement.")
    assertTrue(reviewSettlements.all { it["kind"] == "complete" })
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

  private fun standaloneInlineDirective(
    resolver: InlineCodeQualityRubricResolver,
    diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
  ): String {
    val directives = mutableListOf<String>()
    val harness =
      telemetryRunnerHarness(
        runtimeConfig =
          RuntimeHarnessConfig(
            branchSetup = BranchSetupTestConfig(gitOperations = git),
            repoRoot = repoRoot,
            launcher = fixLauncher(),
            reviewRunner = directiveRecordingReviewRunner(directives),
            inlineRubricResolver = resolver,
            seedDurableWorkflow = false,
          ),
        databaseFactory = { database },
      )
    val result =
      PhaseRunEntryFixture(
        harness.strategies,
        git,
        database,
        clock,
        harness.runLoopEntry.delegateTo(TestFeatureTaskRuntimeRunLoopEntry()),
        diagnostics = diagnostics,
      )
        .entry()
        .run(reviewRequest(CodeReviewExecutionMode.INLINE))

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    return directives.single()
  }

  private class RecordingDiagnostics : RuntimeDiagnostics {
    val warnings = mutableListOf<String>()

    override fun warning(
      message: String,
      error: Throwable?,
    ) {
      warnings += message
    }

    override fun error(
      message: String,
      error: Throwable?,
    ) = Unit
  }

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
    const val RUBRIC_FAILURE = "rubric catalog is unreadable"
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
