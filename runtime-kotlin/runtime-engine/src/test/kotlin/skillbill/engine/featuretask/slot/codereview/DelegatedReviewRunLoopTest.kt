package skillbill.engine.featuretask.slot.codereview

import skillbill.application.realFeatureTaskRuntimePhaseOutputValidator
import skillbill.application.review.governed.stubGovernedReviewEvidenceEndpointBinder
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunner
import skillbill.application.review.snapshot.diffForChanges
import skillbill.application.review.snapshot.parallelCodeReviewRunnerOf
import skillbill.application.review.snapshot.simulateGovernedEvidenceReads
import skillbill.application.review.snapshot.sparseReviewPack
import skillbill.application.review.spec.ReviewSpecAdjudicationRunner
import skillbill.application.review.spec.SpecIntentProjectionExtractor
import skillbill.application.review.spec.SpecIntentProjectionResolver
import skillbill.application.review.verification.ReviewClaimVerificationRunner
import skillbill.config.model.RepoLocalConfig
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
import skillbill.infrastructure.contracts.review.ReviewContextSchemaValidator
import skillbill.infrastructure.contracts.workflow.decomposition.DecompositionManifestSchemaValidator
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.infrastructure.workflow.decomposition.FileSystemDecompositionManifestFileStore
import skillbill.infrastructure.workflow.review.broker.FileSystemReviewEvidenceBroker
import skillbill.infrastructure.workflow.review.specialists.ClasspathReviewSpecialistContractProvider
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.evidence.ReviewEvidenceBrokerFactory
import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import skillbill.ports.review.model.ReviewOwnedFileEvidence
import skillbill.ports.review.preparation.ReviewRubricResolver
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.scaffold.model.PlatformManifest
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
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
      assertTrue(promptFor(launcher, "verify_findings").contains(FINDING_MESSAGE))
      assertEquals(1, launchedPhases.count { it == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX })
      assertTrue(promptFor(launcher, "implement_fix").contains(FINDING_MESSAGE))
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
              delegatedReviewRunner = delegatedReviewRunner(database, home, lanes),
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
    val specialists = lanes.launches.filter { it.skillRunRequest.issueKey == SPECIALIST_ISSUE_KEY }
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

  private fun delegatedReviewRunner(
    database: DatabaseSessionFactory,
    home: Path,
    lanes: LaneScript,
  ): ParallelCodeReviewRunner {
    val envelopeValidator = ReviewContextSchemaValidator()
    return parallelCodeReviewRunnerOf(
      diffResolver = WorktreeDiffResolver(diffForChanges(REVIEWED_PATH to "val connection = open()")),
      repoLocalConfig = DefaultRepoLocalConfig,
      reviewContextEnvelopeValidator = envelopeValidator,
      reviewRubricResolver = GovernedRubricResolver,
      reviewSpecialistContractProvider = ClasspathReviewSpecialistContractProvider(),
      database = database,
      installedPackCatalog =
        InstalledPlatformPackCatalogPort { listOf(sparseReviewPack("kotlin", "architecture", emptyMap())) },
      specIntentProjectionResolver =
        SpecIntentProjectionResolver(
          FileSystemDecompositionManifestFileStore(),
          DecompositionManifestSchemaValidator(),
          SpecIntentProjectionExtractor(envelopeValidator, FileSystemDecompositionManifestFileStore()),
        ),
      parentReviewLauncher = lanes,
      reviewEvidenceBrokerFactory = ReviewEvidenceBrokerFactory(::FileSystemReviewEvidenceBroker),
      governedEvidenceEndpointBinder = stubGovernedReviewEvidenceEndpointBinder(home.resolve("review-endpoint")),
    )
  }

  private class LaneScript(
    private val onSpecialistLaunch: () -> Unit = {},
  ) : GoalRunnerSubtaskLauncher {
    val launches: MutableList<GoalRunnerSubtaskLaunchRequest> = Collections.synchronizedList(mutableListOf())

    @Volatile var fixed: Boolean = false

    override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome {
      launches += request
      val lane = request.skillRunRequest
      simulateGovernedEvidenceReads(lane)
      if (lane.issueKey == SPECIALIST_ISSUE_KEY) onSpecialistLaunch()
      return agentRunLaunchFacts(
        agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
        stdout =
          when (lane.issueKey) {
            SPECIALIST_ISSUE_KEY -> if (fixed) "NO_FINDINGS" else FINDING_REGISTER
            ReviewClaimVerificationRunner.ISSUE_KEY -> """{"claim_verdict":"confirmed"}"""
            ReviewSpecAdjudicationRunner.ISSUE_KEY -> """{"scope_disposition":"in_scope"}"""
            else -> "NO_FINDINGS"
          },
        stderr = "",
      )
    }
  }

  private class WorktreeDiffResolver(private val diff: String) : DiffResolverPort {
    override fun reviewWorktreeFileIdentities(
      root: Path,
      paths: List<String>,
    ): Map<String, ReviewCheckpointFileIdentity> = emptyMap()

    override fun readDiff(
      path: Path,
      maxBytes: Long,
    ): String? = null

    override fun runProcess(
      args: List<String>,
      workDir: Path,
    ): String =
      when (args.getOrNull(1)) {
        "rev-parse" -> args.last().removeSuffix("^{commit}")
        "rev-list", "ls-files" -> ""
        else -> diff
      }
  }

  private object DefaultRepoLocalConfig : RepoLocalConfigPort {
    override fun readRepoLocalConfig(request: ReadRepoLocalConfigRequest) =
      ReadRepoLocalConfigResult(RepoLocalConfig.defaults())
  }

  private object GovernedRubricResolver : ReviewRubricResolver {
    override fun resolve(manifest: PlatformManifest?): ResolvedReviewRubric =
      ResolvedReviewRubric("parallel-code-review", "governed rubric body for parallel-code-review")

    override fun resolve(
      manifest: PlatformManifest?,
      evidence: List<ReviewOwnedFileEvidence>,
      specialistSkillName: String,
    ): ResolvedReviewRubric =
      ResolvedReviewRubric(
        rubricId = specialistSkillName,
        body = "governed rubric body for $specialistSkillName",
        area = specialistSkillName.substringAfter("-code-review-", "generic"),
      )
  }

  private companion object {
    const val SPECIALIST_ISSUE_KEY = "code-review"
    const val REVIEWED_PATH = "src/Foo.kt"
    const val LANE_EDITED_PATH = "src/LaneEdit.kt"
    const val FINDING_MESSAGE = "the delegated lane saw the connection leak on the error path"
    const val FINDING_REGISTER =
      "- [F-001] Blocker | High | specialist=bill-kotlin-code-review-architecture | " +
        "path=\"$REVIEWED_PATH\" | line=1 | $FINDING_MESSAGE"
  }
}
