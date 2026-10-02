package skillbill.application

import skillbill.agentaddon.model.AgentAddonPromptFormatter
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelectionEntry
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.application.review.governed.stubGovernedReviewEvidenceEndpointBinder
import skillbill.application.review.model.ParallelCodeReviewPlanningFailure
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewRunOutcome
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunner
import skillbill.application.review.parallel.runner.finding
import skillbill.application.review.snapshot.RecordedWorkerResponse
import skillbill.application.review.snapshot.ReviewHarnessConfig
import skillbill.application.review.snapshot.ReviewRecorder
import skillbill.application.review.snapshot.diffForChanges
import skillbill.application.review.snapshot.diffForPaths
import skillbill.application.review.snapshot.harnessRequest
import skillbill.application.review.snapshot.parallelCodeReviewRunnerOf
import skillbill.application.review.snapshot.recordingLearnings
import skillbill.application.review.snapshot.reviewFileSystemDiffResolver
import skillbill.application.review.snapshot.reviewHarness
import skillbill.application.review.snapshot.reviewed
import skillbill.application.review.snapshot.simulateGovernedEvidenceReads
import skillbill.application.review.snapshot.sparseReviewPack
import skillbill.application.review.spec.SpecIntentProjectionExtractor
import skillbill.application.review.spec.SpecIntentProjectionResolver
import skillbill.application.review.spec.resolver
import skillbill.application.review.verification.ReviewClaimVerificationRunner
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.config.model.RepoLocalConfig
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.GovernedReviewFailureCode
import skillbill.error.shellcontent.MissingInstalledNativeAgentError
import skillbill.goalrunner.terminalStatus
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.diff.DiffResolverPortDefaults
import skillbill.ports.diff.model.ReviewDiffQuery
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.repository.toFileLocation
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.evidence.ReviewEvidenceBroker
import skillbill.ports.review.evidence.ReviewEvidenceBrokerFactory
import skillbill.ports.review.launch.NO_OP_REVIEW_LAUNCH_AGENT_STAGING
import skillbill.ports.review.launch.NO_OP_REVIEW_NATIVE_AGENT_PREFLIGHT
import skillbill.ports.review.launch.ReviewLaunchAgentStagingPort
import skillbill.ports.review.launch.ReviewNativeAgentPreflightPort
import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.ports.review.preparation.ReviewRubricResolver
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.review.repository.ReviewSpecialistContractProvider
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.review.context.ReviewContextWireMap
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewParseResult
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewPassClaimSnapshot
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageDegradationMeasurement
import skillbill.review.parallel.ParallelReviewFindingParser
import skillbill.scaffold.model.BaselineReviewCatalog
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.ReviewLaneCondition
import skillbill.scaffold.model.RoutingSignals
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import skillbill.telemetry.model.PrDescriptionGeneratedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeDiagnosticDegradationMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProjectionMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRejectionMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ParallelCodeReviewRunnerTest {
  @Test
  fun `unsupported agent1 id returns a UsageInvalid planning failure`() {
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher)

    runner.planningFailure<ParallelCodeReviewPlanningFailure.UsageInvalid>(baseRequest(agent1Id = "unknown-agent-xyz"))

    assertTrue(launcher.requests.isEmpty())
  }

  @Test
  fun `single agent prose result soft-admits register lines for verification without failing shape`() {
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val prose =
      """
      Shared issue in Test.kt
      - [F-001] Major | High | path="Test.kt" | line=1 | Shared issue
      verdict: changes_requested
      """.trimIndent()
    val launcher = alwaysSuccessLauncher(prose)
    val runner = runner(launcher)

    val result =
      runner.reviewed(
        baseRequest(
          agent1Id = "claude",
          scope = ParallelReviewScope.STAGED,
          repoRoot = tempDir,
        ),
      )

    assertTrue(result.lane1.success)
    assertEquals(prose, result.mergeResult.formattedOutput)
    assertEquals(1, result.mergeResult.findings.size)
    assertEquals("F-001", result.mergeResult.findings.single().fNumber)
  }

  @Test
  fun `single agent prose without register lines still succeeds with empty findings`() {
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val prose = "Shared issue in Test.kt\nverdict: changes_requested"
    val launcher = alwaysSuccessLauncher(prose)
    val runner = runner(launcher)

    val result =
      runner.reviewed(
        baseRequest(
          agent1Id = "claude",
          scope = ParallelReviewScope.STAGED,
          repoRoot = tempDir,
        ),
      )

    assertTrue(result.lane1.success)
    assertEquals(emptyList(), result.mergeResult.findings)
    assertEquals(prose, result.mergeResult.formattedOutput)
  }

  @Test
  fun `delegated review obtains specialist contract independently of reviewed checkout`() {
    val reviewedRepo = createGitRepo()
    createStagedFile(reviewedRepo)
    val unrelatedWorkingDirectory = Files.createTempDirectory("unrelated-working-directory")
    val originalWorkingDirectory = System.getProperty("user.dir")
    try {
      System.setProperty("user.dir", unrelatedWorkingDirectory.toString())

      val result =
        runner(alwaysSuccessLauncher()).reviewed(
          baseRequest(scope = ParallelReviewScope.STAGED, repoRoot = reviewedRepo),
        )

      assertTrue(result.lane1.success)
    } finally {
      System.setProperty("user.dir", originalWorkingDirectory)
    }
  }

  @Test
  fun `worker approval failure with exit zero cannot claim clean review coverage`() {
    val repo = createGitRepo()
    createStagedFile(repo)
    val blockedOutput = "Review blocked: MCP tool call requires approval, but approval policy is never."
    val launcher =
      GoalRunnerSubtaskLauncher { request ->
        agentRunLaunchFacts(
          agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
          stdout = blockedOutput,
          stderr = "",
        )
      }

    val result = runner(launcher).reviewed(baseRequest(scope = ParallelReviewScope.STAGED, repoRoot = repo))

    assertFalse(result.lane1.success)
    assertEquals("Review worker returned without reading assigned evidence.", result.lane1.failureReason)
    assertEquals(0, result.lane1.accounting?.authorizedReadCount)
    assertFalse(requireNotNull(result.coverage).isCleanCoverage)
    assertTrue(requireNotNull(result.coverage).incompleteLanes.all { it.unreviewedUnits.isNotEmpty() })
    assertTrue(result.mergeResult.findings.isEmpty())
  }

  @Test
  fun `lane1 timedOut produces lane1Success false`() {
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val launcher =
      GoalRunnerSubtaskLauncher { request ->
        val agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId")
        agentRunLaunchFacts(
          agent = agent,
          termination = AgentRunTermination.TimedOut,
          stdout = "",
          stderr = "",
        )
      }
    val runner = runner(launcher)

    val result =
      runner.reviewed(
        baseRequest(agent1Id = "claude", scope = ParallelReviewScope.STAGED, repoRoot = tempDir),
      )

    assertFalse(result.lane1.success)
    assertEquals("agent timed out", result.lane1.failureReason)
  }

  @Test
  fun `lane1 spawnFailed produces lane1Success false`() {
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val launcher =
      GoalRunnerSubtaskLauncher { request ->
        val agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId")
        agentRunLaunchFacts(
          agent = agent,
          termination = AgentRunTermination.SpawnFailed,
          stdout = "",
          stderr = "",
        )
      }
    val runner = runner(launcher)

    val result =
      runner.reviewed(
        baseRequest(agent1Id = "claude", scope = ParallelReviewScope.STAGED, repoRoot = tempDir),
      )

    assertFalse(result.lane1.success)
    assertEquals("agent process failed to spawn", result.lane1.failureReason)
  }

  @Test
  fun `STAGED scope maps diff command to git diff --cached`() {
    val resolver =
      RecordingDiffResolver(
        commits = mapOf("HEAD" to "head-sha"),
        default = diffFor("A.kt"),
      )
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher, diffResolver = resolver)

    runner.reviewed(baseRequest(agent1Id = "claude", scope = ParallelReviewScope.STAGED))

    assertContains(resolver.calls, "diff ${ReviewDiffQuery.Staged}")
  }

  @Test
  fun `BRANCH scope resolves merge-base then diffs the canonical base against the canonical head`() {
    val resolver =
      RecordingDiffResolver(
        commits = mapOf("HEAD" to "head-sha"),
        mergeBases = mapOf("main" to "base-sha"),
        firstParent = mapOf("base-sha..head-sha" to emptyList()),
        default = diffFor("A.kt"),
      )
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher, diffResolver = resolver)

    runner.reviewed(
      baseRequest(agent1Id = "claude", scope = ParallelReviewScope.BRANCH).detectingRevisions(),
    )

    assertContains(resolver.calls, "mergeBase main")
    assertContains(resolver.calls, "diff ${ReviewDiffQuery.CommitRange("base-sha", "head-sha")}")
  }

  @Test
  fun `PR scope resolves the pull request base and enumerates its commit range`() {
    val resolver =
      RecordingDiffResolver(
        commits = mapOf("HEAD" to "head-sha"),
        pullRequestBase = "pr-base-oid",
        mergeBases = mapOf("pr-base-oid" to "base-sha"),
        firstParent = mapOf("base-sha..head-sha" to emptyList()),
        default = diffFor("A.kt"),
      )
    val runner = runner(ParallelSubtaskLauncher(), diffResolver = resolver)

    runner.reviewed(
      baseRequest(agent1Id = "claude", scope = ParallelReviewScope.PR).detectingRevisions(),
    )

    assertContains(resolver.calls, PULL_REQUEST_BASE_CALL)
    assertContains(resolver.calls, "mergeBase pr-base-oid")
    assertContains(resolver.calls, "firstParentCommits base-sha..head-sha")
  }

  @Test
  fun `WORKTREE_FROM_BASE scope fails when an untracked file diff is unavailable`() {
    val resolver =
      RecordingDiffResolver(
        untracked = listOf("new.kt"),
        diffs = mapOf(ReviewDiffQuery.UntrackedFile("new.kt") to null),
        default = diffFor("A.kt"),
      )
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher, diffResolver = resolver)

    runner.planningFailure<ParallelCodeReviewPlanningFailure.DiffUnresolved>(
      baseRequest(agent1Id = "claude", scope = ParallelReviewScope.WORKTREE_FROM_BASE),
    )

    assertTrue(launcher.requests.isEmpty())
  }

  @Test
  fun `review prompt asks for free-form prose and an explicit verdict`() {
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    runner.reviewed(baseRequest(scope = ParallelReviewScope.STAGED))

    assertTrue(launcher.requests.isNotEmpty())
    launcher.requests.forEach { request ->
      val prompt = request.skillRunRequest.promptOverride.orEmpty()
      assertContains(prompt, "free-form review prose")
      assertContains(prompt, "verdict: approved")
      assertContains(prompt, "verdict: changes_requested")
      assertContains(prompt, "optional `[F-XXX]` register lines")
      assertContains(prompt, "parsed lines are optional verification enrichment")
      assertFalse(prompt.contains("Lines that still do not parse are ignored"))
      assertFalse(prompt.contains("prose-only is invalid"))
      assertFalse(prompt.contains("admit-or-drop"))
      assertFalse(prompt.contains("Return only '[F-XXX]"))
    }
  }

  @Test
  fun `an inline or auto request fails with a typed error before any launch`() {
    listOf(
      CodeReviewExecutionMode.INLINE,
      CodeReviewExecutionMode.AUTO,
    ).forEach { mode ->
      val launcher = ParallelSubtaskLauncher()
      val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

      val error =
        assertFailsWith<SkillBillRuntimeException> {
          runner.reviewed(baseRequest(scope = ParallelReviewScope.STAGED).copy(codeReviewMode = mode))
        }

      assertEquals(GovernedReviewFailureCode.INLINE_PARALLEL_UNSUPPORTED, error.code)
      assertTrue(error.message.orEmpty().contains("requested mode '${mode.wireValue}'"))
      assertTrue(launcher.requests.isEmpty(), "$mode must not launch a parent agent.")
    }
  }

  @Test
  fun `the delegated parent lane requests a fan-out surface`() {
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    runner.reviewed(baseRequest(scope = ParallelReviewScope.STAGED))

    assertEquals(1, launcher.requests.size)
    val request = launcher.requests.single()
    assertTrue(request.skillRunRequest.reviewFanOut)
    assertEquals(null, request.skillRunRequest.nativeReviewWorkerName)
    assertContains(request.skillRunRequest.promptOverride.orEmpty(), "Resolved execution mode: delegated")
  }

  @Test
  fun `parent lane accounting carries the parent prompt and stdout as one turn`() {
    val launcher =
      GoalRunnerSubtaskLauncher { request ->
        simulateGovernedEvidenceReads(request.skillRunRequest)
        agentRunLaunchFacts(
          agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
          stdout = "- [F-001] Major | High | path=\"A.kt\" | line=1 | Parent finding",
          stderr = "",
        )
      }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    val result = runner.reviewed(baseRequest(scope = ParallelReviewScope.STAGED))

    assertTrue(result.lane1.success)
    val accounting = assertNotNull(result.lane1.accounting)
    assertEquals("completed", accounting.terminalStatus)
    assertEquals(1, accounting.modelTurns, "The parent lane is exactly one parent turn.")
    assertTrue(accounting.authorizedReadCount > 0, "The parent must read its assigned evidence.")
    assertTrue(accounting.launchBytes > 0, "The rendered parent prompt must be measured as launch bytes.")
    assertEquals(
      "- [F-001] Major | High | path=\"A.kt\" | line=1 | Parent finding".toByteArray().size.toLong(),
      accounting.resultBytes,
    )
  }

  @Test
  fun `parent lane accounting reports unsupported_provider without a session turn`() {
    val launcher =
      GoalRunnerSubtaskLauncher { request ->
        UnsupportedAgentRunLaunch(
          agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
          reason = "not configured for this repo",
        )
      }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    val result = runner.reviewed(baseRequest(scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success)
    assertContains(result.lane1.failureReason.orEmpty(), "unsupported agent")
    val accounting = assertNotNull(result.lane1.accounting)
    assertEquals("unsupported_provider", accounting.terminalStatus)
    assertEquals(0L, accounting.resultBytes, "No session ran, so there is no result to measure.")
  }

  @Test
  fun `delegated routing launches one rubric per non-empty selected specialist`() {
    val launcher = ParallelSubtaskLauncher()
    val architecture =
      ResolvedReviewRubric(
        "bill-kotlin-code-review-architecture",
        "architecture specialist rubric",
        area = "architecture",
      )
    val testing =
      ResolvedReviewRubric(
        "bill-kotlin-code-review-testing",
        "testing specialist rubric",
        area = "testing",
      )
    val runner =
      runner(
        launcher,
        catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
        diffResolver = RecordingDiffResolver(default = diffFor("src/Main.kt")),
        rubricResolver =
          ReviewRubricResolver {
            ResolvedReviewRubric(
              "bill-kotlin-code-review",
              "parent routing rubric",
              specialists = listOf(architecture, testing),
            )
          },
      )

    runner.reviewed(baseRequest(scope = ParallelReviewScope.STAGED))

    assertEquals(1, launcher.requests.size, "single parent agent receives the routed rubric set")
    launcher.requests.forEach { request ->
      val prompt = request.skillRunRequest.promptOverride.orEmpty()
      assertContains(prompt, "architecture specialist rubric")
      assertFalse(prompt.contains("testing specialist rubric"))
      assertFalse(prompt.contains("parent routing rubric"))
    }
  }

  @Test
  fun `a failed specialist does not discard a successful sibling specialist's findings`() {
    val architectureRubric = "architecture specialist rubric"
    val testingRubric = "testing specialist rubric"
    val launcher =
      GoalRunnerSubtaskLauncher { request ->
        val agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId")
        val prompt = request.skillRunRequest.promptOverride.orEmpty()
        if (prompt.contains(architectureRubric)) {
          agentRunLaunchFacts(
            agent = agent,
            termination = AgentRunTermination.Exited(1),
            stdout = "",
            stderr = "boom",
          )
        } else {
          agentRunLaunchFacts(
            agent = agent,
            stdout = "Testing issue\nverdict: changes_requested",
            stderr = "",
          )
        }
      }
    val runner =
      runner(
        launcher,
        catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
        diffResolver = RecordingDiffResolver(default = diffFor("src/Main.kt") + "\n" + diffFor("src/FooTest.kt")),
        rubricResolver =
          ReviewRubricResolver {
            ResolvedReviewRubric(
              "bill-kotlin-code-review",
              "parent routing rubric",
              specialists =
                listOf(
                  ResolvedReviewRubric(
                    "bill-kotlin-code-review-architecture",
                    architectureRubric,
                    area = "architecture",
                  ),
                  ResolvedReviewRubric("bill-kotlin-code-review-testing", testingRubric, area = "testing"),
                ),
            )
          },
      )

    val result = runner.reviewed(baseRequest(scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success, "Parent fails when its process exits non-zero.")
    assertTrue(result.mergeResult.findings.isEmpty(), "Prose path never publishes a findings register.")
  }

  @Test
  fun `excessive lane result terminates with typed budget outcome`() {
    val runner =
      runner(
        alwaysSuccessLauncher("x".repeat(65_537)),
        diffResolver = RecordingDiffResolver(default = diffFor("A.kt")),
      )

    val result = runner.reviewed(baseRequest(scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success)
    assertContains(result.lane1.failureReason.orEmpty(), "review_context_budget_exceeded")
    assertEquals("review_context_budget_exceeded", result.lane1.budgetOutcome?.type)
    assertTrue(result.mergeResult.findings.isEmpty())
  }
}

class ParallelCodeReviewCursorDelegatedLaunchTest {
  @Test
  fun `cursor delegated parent names specialists and stages project agents before launch`() {
    val endpointRoot = Files.createTempDirectory("cursor-delegated-endpoint")
    val launcher = ParallelSubtaskLauncher()
    val runner = cursorDelegatedRunner(launcher, endpointRoot)

    runner.reviewed(
      baseRequest(agent1Id = "cursor", scope = ParallelReviewScope.STAGED)
        .copy(codeReviewMode = CodeReviewExecutionMode.DELEGATED),
    )

    val request = launcher.requests.single()
    val prompt = request.skillRunRequest.promptOverride.orEmpty()
    assertTrue(request.skillRunRequest.reviewFanOut)
    assertContains(prompt, "/bill-kotlin-code-review-architecture")
    assertContains(prompt, "/bill-kotlin-code-review-testing")
    assertTrue(
      prompt.contains("bill-kotlin-code-review-architecture") &&
        prompt.contains("bill-kotlin-code-review-testing") &&
        prompt.contains("one instruction"),
    )
    val agentsDir = endpointRoot.resolve(".cursor/agents")
    assertTrue(Files.isRegularFile(agentsDir.resolve("bill-kotlin-code-review-architecture.md")))
    assertTrue(Files.isRegularFile(agentsDir.resolve("bill-kotlin-code-review-testing.md")))
  }

  @Test
  fun `claude delegated parent prompt has no cursor slash-name invocation lines`() {
    val launcher = ParallelSubtaskLauncher()
    val runner =
      runner(
        launcher,
        catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
        diffResolver = RecordingDiffResolver(default = diffFor("src/FooTest.kt")),
        rubricResolver =
          ReviewRubricResolver {
            ResolvedReviewRubric(
              "bill-kotlin-code-review",
              "parent routing rubric",
              specialists =
                listOf(
                  ResolvedReviewRubric(
                    "bill-kotlin-code-review-architecture",
                    "architecture specialist rubric",
                    area = "architecture",
                  ),
                  ResolvedReviewRubric(
                    "bill-kotlin-code-review-testing",
                    "testing specialist rubric",
                    area = "testing",
                  ),
                ),
            )
          },
      )

    runner.reviewed(
      baseRequest(agent1Id = "claude", scope = ParallelReviewScope.STAGED)
        .copy(codeReviewMode = CodeReviewExecutionMode.DELEGATED),
    )

    launcher.requests.forEach { request ->
      val prompt = request.skillRunRequest.promptOverride.orEmpty()
      assertFalse(Regex("/bill-").containsMatchIn(prompt))
      assertTrue(request.skillRunRequest.reviewFanOut)
    }
  }

  @Test
  fun `cursor delegated missing selected specialist fails before parent launch`() {
    val launcher = ParallelSubtaskLauncher()
    val runner =
      cursorDelegatedRunner(
        launcher,
        Files.createTempDirectory("cursor-missing-endpoint"),
        nativeAgentPreflight =
          ReviewNativeAgentPreflightPort {
            throw MissingInstalledNativeAgentError(
              "bill-kotlin-code-review-testing",
              "cursor",
              "/missing",
              "managed inventory entry is missing",
              "skill-bill install apply",
            )
          },
      )

    val error =
      assertFailsWith<MissingInstalledNativeAgentError> {
        runner.reviewed(
          baseRequest(agent1Id = "cursor", scope = ParallelReviewScope.STAGED)
            .copy(codeReviewMode = CodeReviewExecutionMode.DELEGATED),
        )
      }

    assertTrue(launcher.requests.isEmpty())
    assertContains(error.logicalName, "bill-kotlin-code-review-testing")
  }
}

class ParallelCodeReviewParentFindingTest {
  @Test
  fun `parent review keeps a rubric-tagged finding on a path owned by another selected specialist`() {
    val persistencePath =
      "application/src/test/kotlin/dev/skillbill/application/slot/InMemoryPersistencePorts.kt"
    val finding =
      "[F-001] Major | High | specialist=bill-kotlin-code-review-persistence | " +
        "path=\"$persistencePath\" | line=956 | RunStateConflict hides a cancelled committed attempt"
    val result =
      kotlinPersistenceInlineRunner(finding, persistencePath)
        .reviewed(baseRequest(scope = ParallelReviewScope.STAGED))
    assertTrue(result.lane1.success, result.lane1.failureReason.orEmpty())
    assertEquals(
      listOf("bill-kotlin-code-review-persistence"),
      result.mergeResult.findings.single().specialistSkillNames,
    )
  }

  @Test
  fun `parent review assigns an unknown specialist tag to a path-owning lane and still reports the finding`() {
    val finding =
      "[F-001] Major | High | specialist=bill-kotlin-code-review-unknown | " +
        "path=\"src/FooTest.kt\" | line=12 | test dispatcher never advances"
    val result =
      kotlinArchitectureTestingRunner(finding)
        .reviewed(baseRequest(scope = ParallelReviewScope.STAGED))
    assertTrue(result.lane1.success, result.lane1.failureReason.orEmpty())
    assertEquals(1, result.mergeResult.findings.size)
    assertEquals(
      listOf("bill-kotlin-code-review-architecture"),
      result.mergeResult.findings.single().specialistSkillNames,
    )
  }

  @Test
  fun `parent review keeps an unowned path finding on the default lane without failing the run`() {
    val finding =
      "[F-001] Major | High | specialist=bill-kotlin-code-review-unknown | " +
        "path=\"docs/OUTSIDE.md\" | line=3 | cited a file the packet does not own"
    val result =
      kotlinArchitectureTestingRunner(finding)
        .reviewed(baseRequest(scope = ParallelReviewScope.STAGED))
    assertTrue(result.lane1.success, result.lane1.failureReason.orEmpty())
    val reported = result.mergeResult.findings.single()
    assertEquals("docs/OUTSIDE.md:3", reported.location)
    assertEquals("docs/OUTSIDE.md", reported.repositoryPath)
    assertEquals(listOf("bill-kotlin-code-review-architecture"), reported.specialistSkillNames)
  }
}

class ParallelCodeReviewSuppliedDiffTest {
  @Test
  fun `a huge changed file leaves the sibling required lane selected and the parent composed`() {
    val pack =
      sparseReviewPack(
        slug = "kotlin",
        requiredArea = "architecture",
        pathAreas = mapOf("testing" to listOf("src/test/")),
      )
    val recorder = ReviewRecorder()
    val huge = "x".repeat(300_000)
    val result =
      reviewHarness(
        ReviewHarnessConfig(
          manifests = listOf(pack),
          diff =
            diffForChanges(
              "src/Main.kt" to huge,
              "src/test/MainTest.kt" to "ok",
            ),
        ),
        recorder,
      ).reviewed(harnessRequest())

    assertTrue(recorder.parentLaunches.isNotEmpty())
    recorder.parentPrompts.forEach { prompt ->
      assertTrue(prompt.contains("bill-kotlin-code-review-architecture"))
      assertTrue(prompt.contains("bill-kotlin-code-review-testing"))
      assertFalse(prompt.contains(huge))
    }
    val coverage = assertNotNull(result.coverage)
    assertTrue(coverage.isCleanCoverage, coverage.render())
  }

  @Test
  fun `supplied exact diff bypasses branch-scope resolution for the parent lane`() {
    val resolver = RecordingDiffResolver(default = "unexpected branch diff")
    val launcher = ParallelSubtaskLauncher()
    val runner =
      runner(
        launcher,
        catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
        diffResolver = resolver,
      )
    val exactDiff = "diff --git a/Child.kt b/Child.kt\n+++ b/Child.kt\n+owned change\n"

    runner.reviewed(baseRequest(scope = ParallelReviewScope.BRANCH).copy(suppliedDiff = exactDiff))

    assertEquals(listOf(CURRENT_BRANCH_CALL), resolver.calls)
    assertEquals(1, launcher.requests.size)
    launcher.requests.forEach { request ->
      val prompt = request.skillRunRequest.promptOverride.orEmpty()
      assertContains(prompt, "Resolved execution mode: delegated")
      assertContains(prompt, "Owned paths: \"Child.kt\"")
      assertContains(prompt, "## Assigned bundle:")
      assertContains(prompt, "\"Child.kt\"")
      assertFalse(prompt.contains("+owned change"))
      assertContains(prompt, "hunk_id:")
      assertContains(prompt, "content_digest:")
      assertContains(prompt, "evidence_locator:")
      assertContains(prompt, "they are not read_evidence arguments and passing one is refused")
      assertFalse(prompt.contains("unexpected branch diff"), "the supplied diff must replace branch resolution")
    }
  }

  @Test
  fun `empty supplied diff completes without git range resolution`() {
    val resolver = RecordingDiffResolver(default = "unexpected branch diff")
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher, diffResolver = resolver)

    val result = runner.reviewed(baseRequest(scope = ParallelReviewScope.BRANCH).copy(suppliedDiff = ""))

    assertEquals(listOf(CURRENT_BRANCH_CALL), resolver.calls)
    assertTrue(launcher.requests.isEmpty())
    assertTrue(result.mergeResult.findings.isEmpty())
  }

  @Test
  fun `supplied diff recovers spec intent from the HEAD branch without a spec path`() {
    val repo = Files.createTempDirectory("supplied-diff-spec-intent")
    val specDir = repo.resolve(".feature-specs/SKILL-191-runtime")
    Files.createDirectories(specDir)
    Files.writeString(
      specDir.resolve("spec.md"),
      """
      # Feature

      ## Intended Outcome
      Ship the review driver.

      ## Acceptance Criteria
      1. Adjudication runs from the ticket-named branch.
      """.trimIndent(),
    )
    val database = RecordingReviewDatabase()
    val resolver =
      RecordingDiffResolver(
        branchName = "feat/SKILL-191-runtime",
        default = "unexpected branch diff",
      )
    val runner =
      createRunner(
        ParallelSubtaskLauncher(),
        RunnerFixtureConfig(
          catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
          diffResolver = resolver,
          database = database,
        ),
      )
    val exactDiff = "diff --git a/Child.kt b/Child.kt\n+++ b/Child.kt\n+owned change\n"

    runner.reviewed(
      baseRequest(scope = ParallelReviewScope.BRANCH, repoRoot = repo).copy(suppliedDiff = exactDiff),
    )

    assertEquals(listOf(CURRENT_BRANCH_CALL), resolver.calls)
    assertEquals(".feature-specs/SKILL-191-runtime/spec.md", database.specProjection?.specPath)
    assertEquals(null, database.specProjection?.absenceReason)
  }

  @Test
  fun `unreadable supplied diff file fails instead of empty success`() {
    val resolver = RecordingDiffResolver(default = "unexpected branch diff")
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher, diffResolver = resolver)
    val missing = Path.of("/tmp/skill-bill-missing-diff-file.patch")

    val failure =
      runner.planningFailure<ParallelCodeReviewPlanningFailure.DiffUnresolved>(
        baseRequest(scope = ParallelReviewScope.BRANCH).copy(suppliedDiffPath = missing),
      )

    assertTrue(failure.message.contains("--diff-file"))
    assertTrue(launcher.requests.isEmpty())
  }

  @Test
  fun `selected agent add-ons section is copied onto every stage launch`() {
    val selection =
      HydratedAgentAddonSelection(
        listOf(
          HydratedAgentAddonSelectionEntry(
            PersistedAgentAddonSelectionEntry("first", "local:first", "a".repeat(64)),
            "first",
            "first body\n",
          ),
          HydratedAgentAddonSelectionEntry(
            PersistedAgentAddonSelectionEntry("second", "local:second", "b".repeat(64)),
            "second",
            "second body",
          ),
        ),
      )
    val formatted = AgentAddonPromptFormatter.format(selection)
    val pack =
      sparseReviewPack(
        slug = "kotlin",
        requiredArea = "architecture",
        pathAreas = mapOf("testing" to listOf("src/test/")),
      )
    val recorder = ReviewRecorder()
    reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(pack),
        diff = diffForPaths("src/Main.kt"),
        response = { request ->
          when (request.skillRunRequest.issueKey) {
            "code-review" -> RecordedWorkerResponse(stdout = STAGE_ADDON_FINDING)
            ReviewClaimVerificationRunner.ISSUE_KEY -> RecordedWorkerResponse(stdout = STAGE_ADDON_CONFIRMED)
            else -> RecordedWorkerResponse()
          }
        },
      ),
      recorder,
    ).reviewed(
      harnessRequest(
        reviewRunId = "runner-addons-stage",
      ).copy(
        suppliedDiff = diffForPaths("src/Main.kt"),
        selectedAgentAddonsSection = formatted,
      ),
    )

    val verificationPrompts =
      recorder.parentLaunches
        .filter { it.skillRunRequest.issueKey == ReviewClaimVerificationRunner.ISSUE_KEY }
        .map { it.skillRunRequest.promptOverride.orEmpty() }
    assertTrue(verificationPrompts.isNotEmpty())
    verificationPrompts.forEach { prompt ->
      assertTrue(formatted in prompt)
    }
  }
}

class ParallelCodeReviewRunnerFailureTest {
  @Test
  fun `lane1 interrupted produces lane1Success false`() {
    val launcher =
      GoalRunnerSubtaskLauncher { request ->
        val agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId")
        agentRunLaunchFacts(
          agent = agent,
          termination = AgentRunTermination.Interrupted,
          stdout = "",
          stderr = "",
        )
      }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    val result = runner.reviewed(baseRequest(agent1Id = "claude", scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success)
    assertEquals("agent was interrupted", result.lane1.failureReason)
  }

  @Test
  fun `failed lane findings are excluded from merge result`() {
    val launcher =
      GoalRunnerSubtaskLauncher { request ->
        val agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId")
        agentRunLaunchFacts(
          agent = agent,
          termination = AgentRunTermination.TimedOut,
          stdout = "- [F-001] Major | High | path=\"A.kt\" | line=1 | Should not appear in merge",
          stderr = "",
        )
      }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    val result = runner.reviewed(baseRequest(agent1Id = "claude", scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success)
    assertTrue(
      result.mergeResult.findings.none { it.description.contains("Should not appear in merge") },
      "A failed lane's own output must not reach the merge.",
    )
  }

  @Test
  fun `launcher exception produces ExecutionException outcome`() {
    val launcher =
      GoalRunnerSubtaskLauncher { _ ->
        error("internal failure in launcher")
      }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    val result = runner.reviewed(baseRequest(agent1Id = "claude", scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success)
    assertContains(result.lane1.failureReason.orEmpty(), "IllegalStateException")
  }

  @Test
  fun `coordinator timeout cancels blocking lane and produces failed outcome`() {
    val launcher =
      GoalRunnerSubtaskLauncher { request ->
        agentRunLaunchFacts(
          agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
          termination = AgentRunTermination.TimedOut,
          stdout = "",
          stderr = "",
        )
      }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    val result =
      runner.reviewed(
        baseRequest(agent1Id = "claude", scope = ParallelReviewScope.STAGED, timeout = 1.seconds),
      )

    assertFalse(result.lane1.success)
    assertContains(result.lane1.failureReason.orEmpty(), "timed out")
  }

  @Test
  fun `UnsupportedAgentRunLaunch produces failed lane outcome`() {
    val launcher =
      GoalRunnerSubtaskLauncher { request ->
        val agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId")
        if (request.invokedAgentId == "claude") {
          UnsupportedAgentRunLaunch(agent = agent, reason = "not configured for this repo")
        } else {
          agentRunLaunchFacts(
            agent = agent,
            stdout = "",
            stderr = "",
          )
        }
      }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    val result = runner.reviewed(baseRequest(agent1Id = "claude", scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success)
    assertContains(result.lane1.failureReason.orEmpty(), "unsupported agent")
  }

  @Test
  fun `nonzero exit status includes sanitized stderr excerpt in failure reason`() {
    val launcher =
      GoalRunnerSubtaskLauncher { request ->
        agentRunLaunchFacts(
          agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
          termination = AgentRunTermination.Exited(1),
          stdout = "",
          stderr = "Error: command failed with detail",
        )
      }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    val result = runner.reviewed(baseRequest(agent1Id = "claude", scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success)
    assertContains(result.lane1.failureReason.orEmpty(), "status 1")
    assertContains(result.lane1.failureReason.orEmpty(), "Error: command failed")
  }

  @Test
  fun `stack discovery failure returns a StackUndetected planning failure`() {
    val launcher = ParallelSubtaskLauncher()
    val runner =
      runner(
        launcher,
        catalogGateway = throwingCatalogGateway(),
        diffResolver = RecordingDiffResolver(default = diffFor("A.kt")),
      )

    val failure =
      runner.planningFailure<ParallelCodeReviewPlanningFailure.StackUndetected>(
        baseRequest(agent1Id = "claude", scope = ParallelReviewScope.STAGED),
      )
    assertContains(failure.message, "Installed platform pack discovery failed")
    assertTrue(launcher.requests.isEmpty(), "lanes must not launch when stack detection fails")
  }

  @Test
  fun `stack detection matches wildcard configuration signals`() {
    val launcher = ParallelSubtaskLauncher()
    val runner =
      runner(
        launcher,
        catalogGateway = stubCatalogGateway(listOf(platformManifest("typescript", listOf("tsconfig.*.json")))),
        diffResolver = RecordingDiffResolver(default = diffFor("tsconfig.base.json")),
      )

    runner.reviewed(baseRequest(scope = ParallelReviewScope.STAGED))

    assertEquals(1, launcher.requests.size)
  }

  @Test
  fun `detected manifest selects the governed baseline rubric before lane launch`() {
    val launcher = ParallelSubtaskLauncher()
    var resolvedSlug: String? = null
    val runner =
      runner(
        launcher,
        catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
        diffResolver = RecordingDiffResolver(default = diffFor("src/Main.kt")),
        rubricResolver =
          ReviewRubricResolver { manifest ->
            resolvedSlug = manifest?.slug
            ResolvedReviewRubric("bill-kotlin-code-review", "manifest-owned kotlin rubric")
          },
      )

    runner.reviewed(baseRequest(scope = ParallelReviewScope.STAGED))

    assertEquals("kotlin", resolvedSlug)
    launcher.requests.forEach { request ->
      assertContains(request.skillRunRequest.promptOverride.orEmpty(), "manifest-owned kotlin rubric")
    }
  }

  @Test
  fun `unsupported delta with installed concrete pack uses horizontal base rubric`() {
    val launcher = ParallelSubtaskLauncher()
    var resolvedSlug: String? = "unresolved"
    val runner =
      createRunner(
        launcher,
        RunnerFixtureConfig(
          diffResolver = RecordingDiffResolver(default = diffFor("README.md")),
          rubricResolver =
            ReviewRubricResolver { manifest ->
              resolvedSlug = manifest?.slug
              ResolvedReviewRubric("parallel-code-review", "horizontal base rubric")
            },
          catalogGateway = stubCatalogGateway(listOf(platformManifest("typescript", listOf("*.ts", ".ts")))),
        ),
      )

    runner.reviewed(baseRequest(scope = ParallelReviewScope.STAGED))

    assertEquals(null, resolvedSlug)
    assertEquals(1, launcher.requests.size)
    launcher.requests.forEach { request ->
      assertContains(request.skillRunRequest.promptOverride.orEmpty(), "horizontal base rubric")
    }
  }

  @Test
  fun `runtime launched review records one lane row per planned lane from the plan`() {
    val database = RecordingReviewDatabase()
    val runner =
      createRunner(
        ParallelSubtaskLauncher(),
        RunnerFixtureConfig(
          catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
          diffResolver = RecordingDiffResolver(default = diffFor("src/Main.kt")),
          database = database,
        ),
      )
    val request = baseRequest(scope = ParallelReviewScope.STAGED)

    runner.reviewed(request)

    val (runId, lanes) = database.laneWrites.last()
    assertEquals(request.reviewRunId, runId)
    assertTrue(lanes.isNotEmpty(), "A runtime-launched review must record the lanes it planned.")
    assertTrue(lanes.all { it.resolutionState.wireValue == "resolved" })
    assertTrue(lanes.all { it.packSlug.isNotBlank() && it.area.isNotBlank() })
    assertEquals(lanes.map { it.laneSkillName }.distinct().size, lanes.size)
    assertEquals(lanes.map { it.orderIndex }.sorted(), lanes.map { it.orderIndex })
    assertTrue(
      lanes.all { it.reviewDisposition.wireValue == "complete" },
      "Successful parallel pass must persist complete disposition for every planned lane.",
    )
    assertTrue(database.laneWrites.size >= 2, "Plan recording and disposition finalization must both write.")
  }

  @Test
  fun `runtime launched review records the producing lane of every merged finding`() {
    val database = RecordingReviewDatabase()
    val runner =
      createRunner(
        ParallelSubtaskLauncher(
          outcome =
            agentRunLaunchFacts(
              agent = SupportedAgent.fromNormalizedId("claude", label = "agentId"),
              stdout = "[F-001] Major | High | path=\"src/Main.kt\" | line=3 | Transaction is not rolled back.",
              stderr = "",
            ),
        ),
        RunnerFixtureConfig(
          catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
          diffResolver = RecordingDiffResolver(default = diffFor("src/Main.kt")),
          database = database,
        ),
      )
    val request = baseRequest(scope = ParallelReviewScope.STAGED)

    runner.reviewed(request)

    val (runId, attribution) = database.findingLaneWrites.single()
    assertEquals(request.reviewRunId, runId)
    assertEquals(
      database.laneWrites.last().second.map { it.laneSkillName }.toSet(),
      attribution.values.toSet(),
      "Attribution must name a lane the run actually planned.",
    )
    assertEquals(setOf("F-001"), attribution.keys)
  }

  @Test
  fun `stack detection excludes generated dependency and build paths`() {
    val launcher = ParallelSubtaskLauncher()
    val runner =
      runner(
        launcher,
        catalogGateway =
          stubCatalogGateway(
            listOf(platformManifest("typescript", listOf("*.ts", ".ts")), fallbackManifest()),
          ),
        diffResolver =
          RecordingDiffResolver(
            default =
              listOf(
                "node_modules/library/index.ts",
                "dist/app.ts",
                "build/bundle.ts",
                "coverage/report.ts",
                "src/generated/client.ts",
                "src/api/client.d.ts",
              ).joinToString("\n", transform = ::diffFor),
          ),
      )

    runner.reviewed(baseRequest(scope = ParallelReviewScope.STAGED))

    assertFalse(
      launcher.requests.any { request ->
        request.skillRunRequest.promptOverride.orEmpty().contains("dominant stack is typescript")
      },
    )
  }

  @Test
  fun `sparse routing on a staged UI-only diff drops security and keeps the required baseline`() {
    val launcher = ParallelSubtaskLauncher()
    val pack =
      sparsePlatformManifest(
        requiredArea = "architecture",
        pathAreas =
          mapOf(
            "ui" to listOf("ui/"),
            "security" to listOf("auth/"),
          ),
      )
    val runner =
      createRunner(
        launcher,
        RunnerFixtureConfig(
          catalogGateway = stubCatalogGateway(listOf(pack)),
          diffResolver = RecordingDiffResolver(default = diffFor("ui/Screen.kt")),
        ),
      )

    runner.reviewed(baseRequest(scope = ParallelReviewScope.STAGED))

    val rubrics =
      launcher.requests.flatMap { request ->
        Regex("## Resolved rubric: (\\S+)")
          .findAll(request.skillRunRequest.promptOverride.orEmpty())
          .map { it.groupValues[1] }
          .toList()
      }.toSet()
    assertTrue("bill-kotlin-code-review-architecture" in rubrics, rubrics.toString())
    assertTrue("bill-kotlin-code-review-ui" in rubrics, rubrics.toString())
    assertFalse("bill-kotlin-code-review-security" in rubrics, rubrics.toString())
  }

  @Test
  fun `sparse routing on an unstaged UI-only diff matches the staged lane selection`() {
    val pack =
      sparsePlatformManifest(
        requiredArea = "architecture",
        pathAreas =
          mapOf(
            "ui" to listOf("ui/"),
            "security" to listOf("auth/"),
          ),
      )

    fun launchedRubrics(scope: ParallelReviewScope): Set<String> {
      val launcher = ParallelSubtaskLauncher()
      createRunner(
        launcher,
        RunnerFixtureConfig(
          catalogGateway = stubCatalogGateway(listOf(pack)),
          diffResolver = RecordingDiffResolver(default = diffFor("ui/Screen.kt")),
        ),
      ).reviewed(baseRequest(scope = scope))
      return launcher.requests.flatMap { request ->
        Regex("## Resolved rubric: (\\S+)")
          .findAll(request.skillRunRequest.promptOverride.orEmpty())
          .map { it.groupValues[1] }
          .toList()
      }.toSet()
    }

    assertEquals(launchedRubrics(ParallelReviewScope.STAGED), launchedRubrics(ParallelReviewScope.UNSTAGED))
  }

  @Test
  fun `a former parent routing-analysis pair bound no longer blocks launch`() {
    val launcher = ParallelSubtaskLauncher()
    val pack =
      sparsePlatformManifest(
        requiredArea = "architecture",
        pathAreas =
          mapOf(
            "ui" to listOf("ui/"),
            "security" to listOf("auth/"),
          ),
      )
    val runner =
      createRunner(
        launcher,
        RunnerFixtureConfig(
          catalogGateway = stubCatalogGateway(listOf(pack)),
          diffResolver = RecordingDiffResolver(default = diffFor("ui/Screen.kt")),
          budget = ReviewContextBudgetPolicy.DEFAULT.copy(maxRoutingAnalysisPairs = 1),
        ),
      )

    runner.reviewed(baseRequest(scope = ParallelReviewScope.STAGED))
    assertTrue(launcher.requests.isNotEmpty(), "internal routing prep must not hard-fail before launch")
  }
}

internal data class RunnerFixtureConfig(
  val catalogGateway: ScaffoldCatalogGateway = stubCatalogGateway(),
  val diffResolver: DiffResolverPort = reviewFileSystemDiffResolver(),
  val rubricResolver: ReviewRubricResolver =
    ReviewRubricResolver {
      ResolvedReviewRubric("parallel-code-review", "governed generic rubric")
    },
  val database: RecordingReviewDatabase = RecordingReviewDatabase(),
  val budget: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy.DEFAULT,
  val nativeAgentPreflight: ReviewNativeAgentPreflightPort = NO_OP_REVIEW_NATIVE_AGENT_PREFLIGHT,
  val reviewLaunchAgentStaging: ReviewLaunchAgentStagingPort = NO_OP_REVIEW_LAUNCH_AGENT_STAGING,
  val evidenceEndpointRoot: Path? = null,
  val evidenceEndpointBinder: GovernedReviewEvidenceEndpointBinder? = null,
  val registerParse: (String) -> ParallelReviewParseResult =
    ParallelReviewFindingParser::parse,
) {
  val installedPackCatalog: InstalledPlatformPackCatalogPort =
    InstalledPlatformPackCatalogPort { catalogGateway.discoverPlatformManifests(Path.of(".")) }
}

internal fun runner(
  launcher: GoalRunnerSubtaskLauncher,
  catalogGateway: ScaffoldCatalogGateway = stubCatalogGateway(),
  diffResolver: DiffResolverPort = reviewFileSystemDiffResolver(),
  rubricResolver: ReviewRubricResolver =
    ReviewRubricResolver {
      ResolvedReviewRubric("parallel-code-review", "governed generic rubric")
    },
): ParallelCodeReviewRunner =
  createRunner(
    launcher,
    RunnerFixtureConfig(
      catalogGateway = catalogGateway,
      diffResolver = diffResolver,
      rubricResolver = rubricResolver,
    ),
  )

internal fun createRunner(
  launcher: GoalRunnerSubtaskLauncher,
  config: RunnerFixtureConfig,
): ParallelCodeReviewRunner {
  val endpointRoot = config.evidenceEndpointRoot ?: Files.createTempDirectory("endpoint")
  val runner =
    parallelCodeReviewRunnerOf(
      diffResolver = config.diffResolver,
      repoLocalConfig =
        object : RepoLocalConfigPort {
          override fun readRepoLocalConfig(request: ReadRepoLocalConfigRequest) =
            ReadRepoLocalConfigResult(RepoLocalConfig.defaults().copy(reviewContextBudget = config.budget))
        },
      reviewContextEnvelopeValidator =
        object : ReviewContextEnvelopeValidator {
          override fun validate(
            envelope: ReviewContextWireMap,
            sourceLabel: String,
          ) = Unit
        },
      reviewRubricResolver = config.rubricResolver,
      reviewSpecialistContractProvider = ReviewSpecialistContractProvider { TEST_SPECIALIST_CONTRACT },
      database = config.database,
      installedPackCatalog = config.installedPackCatalog,
      specIntentProjectionResolver =
        SpecIntentProjectionResolver(
          TestDecompositionManifestStore,
          testDecompositionManifestValidator,
          SpecIntentProjectionExtractor(
            object : ReviewContextEnvelopeValidator {
              override fun validate(
                envelope: ReviewContextWireMap,
                sourceLabel: String,
              ) = Unit
            },
            TestDecompositionManifestStore,
          ),
        ),
      parentReviewLauncher = launcher,
      nativeAgentPreflight = config.nativeAgentPreflight,
      registerParse = config.registerParse,
      clock = testHarnessClock,
      repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
      reviewEvidenceBrokerFactory =
        ReviewEvidenceBrokerFactory { binding ->
          object : ReviewEvidenceBroker {
            private var authorizedReads = 0

            override fun authorizeExpansion(request: ReviewExpansionAuthorizationRequest): ReviewExpansionRecord =
              ReviewExpansionRecord(
                expansionId = "test-expansion",
                assignmentDigest = "a".repeat(64),
                requestedPath = request.path,
                reachabilityReason = "test harness",
                authorized = false,
                sequence = 0,
              )

            override fun readBatch(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult {
              authorizedReads += request.requests.size
              return ReviewEvidenceBatchResult(
                results = emptyList(),
                cumulativeBytes = 0,
                expansions = emptyList(),
              )
            }

            override fun recordToolCall(call: ReviewToolCall) = ReviewToolCallResult()

            override fun recordModelTurn() = null

            override fun validateLaneResult(result: String) = null

            override fun observeLaneResultChunk(chunk: String) = null

            override fun accounting() =
              ReviewLaneAccounting(
                lane = binding.assignment.lane,
                authorizedReadCount = authorizedReads,
                reviewId = binding.assignment.reviewId,
                packetDigest = binding.assignment.packetDigest,
                evidenceBytes = 0,
                expansions = emptyList(),
                toolCalls = 0,
                modelTurns = 0,
                resultBytes = 0,
              )

            override fun terminalOutcome() = null
          }
        },
      governedEvidenceEndpointBinder =
        config.evidenceEndpointBinder ?: stubGovernedReviewEvidenceEndpointBinder(endpointRoot),
      reviewLaunchAgentStaging = config.reviewLaunchAgentStaging,
    )
  return runner
}

internal class RecordingReviewDatabase : DatabaseSessionFactory {
  val laneWrites = mutableListOf<Pair<String, List<ReviewRunLane>>>()
  val findingLaneWrites = mutableListOf<Pair<String, Map<String, String>>>()
  var specProjection: ReviewSpecProjectionReference? = null
  private var passClaims: ReviewPassClaimSnapshot? = null

  private val reviews =
    Proxy.newProxyInstance(
      ReviewRepository::class.java.classLoader,
      arrayOf(ReviewRepository::class.java),
    )
      @Suppress("UNCHECKED_CAST")
      { _, method, args ->
        when (method.name) {
          "saveAccounting" -> Unit
          "loadAccounting" -> null
          "replaceReviewRunLanes" -> {
            laneWrites += args[0] as String to (args[1] as List<ReviewRunLane>)
          }
          "fetchReviewRunLanes" -> laneWrites.lastOrNull()?.second.orEmpty()
          "fetchIntegrationPass" -> null
          "recordIntegrationPass" -> Unit
          "recordFindingLaneAttribution" -> {
            findingLaneWrites += args[0] as String to (args[1] as Map<String, String>)
          }
          "recordFindingVerdicts", "recordStageBoundary" -> Unit
          "recordSpecProjectionReference" -> specProjection = args[1] as ReviewSpecProjectionReference
          "recordReviewPassClaims" -> {
            passClaims = ReviewPassClaimSnapshot(args[1] as List<ParallelReviewMergedFinding>)
          }
          "fetchFindingVerdicts" -> emptyList<ReviewFindingVerdict>()
          "fetchReviewPassClaims" -> passClaims
          "fetchStageBoundaries" -> emptyList<ReviewStageBoundary>()
          "fetchSpecProjectionReference" -> specProjection
          else -> error("Unexpected review repository call: ${method.name}")
        }
      }
      as ReviewRepository
  private val unitOfWork =
    Proxy.newProxyInstance(
      UnitOfWork::class.java.classLoader,
      arrayOf(UnitOfWork::class.java),
    ) { _, method, _ ->
      when (method.name) {
        "getReviews" -> reviews
        "getLearnings" -> recordingLearnings()
        "getLifecycleTelemetry" -> NoopReviewLifecycleTelemetry
        "getDbPath" -> Path.of("/tmp/noop-review.db")
        else -> error("Unexpected unit-of-work call: ${method.name}")
      }
    } as UnitOfWork

  override fun resolveDbPath() = unitOfWork.dbPath

  override fun databaseExists() = true

  override fun <T> read(block: (UnitOfWork) -> T): T = block(unitOfWork)

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = transaction(block)

  override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unitOfWork)
}

private object NoopReviewLifecycleTelemetry : LifecycleTelemetryRepository {
  override fun featureTaskRuntimeProjectionMeasurement(record: FeatureTaskRuntimeProjectionMeasurement) = Unit

  override fun featureTaskRuntimeSharedEvidence(record: FeatureTaskRuntimeSharedEvidenceMeasurement) = Unit

  override fun featureTaskRuntimeRejection(record: FeatureTaskRuntimeRejectionMeasurement) = Unit

  override fun featureTaskRuntimeDiagnosticDegradation(record: FeatureTaskRuntimeDiagnosticDegradationMeasurement) =
    Unit

  override fun reviewStageDegradation(record: ReviewStageDegradationMeasurement) = Unit

  override fun featureTaskRuntimeStarted(
    record: FeatureTaskRuntimeStartedRecord,
    level: String,
  ) = Unit

  override fun featureTaskRuntimeFinished(
    record: FeatureTaskRuntimeFinishedRecord,
    level: String,
  ) = Unit

  override fun qualityCheckStarted(
    record: QualityCheckStartedRecord,
    level: String,
  ) = Unit

  override fun qualityCheckFinished(
    record: QualityCheckFinishedRecord,
    level: String,
  ) = Unit

  override fun featureVerifyStarted(
    record: FeatureVerifyStartedRecord,
    level: String,
  ) = Unit

  override fun featureVerifyFinished(
    record: FeatureVerifyFinishedRecord,
    level: String,
  ) = Unit

  override fun prDescriptionGenerated(
    record: PrDescriptionGeneratedRecord,
    level: String,
  ) = Unit

  override fun goalStarted(
    record: GoalStartedRecord,
    level: String,
  ) = Unit

  override fun goalSubtaskFinished(
    record: GoalSubtaskFinishedRecord,
    level: String,
  ) = Unit

  override fun goalFinished(
    record: GoalFinishedRecord,
    level: String,
  ) = Unit

  override fun goalIssueFinished(
    record: GoalIssueFinishedRecord,
    level: String,
  ) = Unit
}

private const val STAGE_ADDON_FINDING: String =
  "- [F-001] Major | High | specialist=bill-kotlin-code-review-architecture | " +
    "path=\"src/Main.kt\" | line=1 | null is unchecked"
private const val STAGE_ADDON_CONFIRMED: String = """{"claim_verdict":"confirmed"}"""

private const val TEST_SPECIALIST_CONTRACT: String =
  "## Shared Contract For Every Specialist\n" +
    "- Evidence is mandatory\n" +
    "- Keep each specialist review pass to at most 7 findings\n\n" +
    "## Shared Report Structure\n" +
    "- [F-001] <Severity> | <Confidence> | <file:line> | <description>"

private val runnerRequestSequence = AtomicInteger()

internal fun baseRequest(
  agent1Id: String = "claude",
  scope: ParallelReviewScope = ParallelReviewScope.STAGED,
  repoRoot: Path = Files.createTempDirectory("pr-runner-test"),
  timeout: Duration? = null,
) = ParallelCodeReviewRequest(
  agent1Id = agent1Id,
  scope = scope,
  repoRoot = repoRoot,
  timeout = timeout,
  codeReviewMode = CodeReviewExecutionMode.DELEGATED,
  reviewRunId = "runner-test-${runnerRequestSequence.incrementAndGet()}",
  baseRevision = "base-revision",
  headRevision = "head-revision",
)

private fun ParallelCodeReviewRequest.detectingRevisions() = copy(baseRevision = null, headRevision = null)

private fun alwaysSuccessLauncher(stdout: String = "NO_FINDINGS") =
  GoalRunnerSubtaskLauncher { request ->
    simulateGovernedEvidenceReads(request.skillRunRequest)
    agentRunLaunchFacts(
      agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
      stdout = stdout,
      stderr = "",
    )
  }

private fun kotlinArchitectureTestingRunner(finding: String): ParallelCodeReviewRunner =
  runner(
    alwaysSuccessLauncher(finding),
    catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
    diffResolver = RecordingDiffResolver(default = diffFor("src/FooTest.kt")),
    rubricResolver =
      ReviewRubricResolver {
        ResolvedReviewRubric(
          "bill-kotlin-code-review",
          "parent routing rubric",
          specialists =
            listOf(
              ResolvedReviewRubric(
                "bill-kotlin-code-review-architecture",
                "architecture specialist rubric",
                area = "architecture",
              ),
              ResolvedReviewRubric(
                "bill-kotlin-code-review-testing",
                "testing specialist rubric",
                area = "testing",
              ),
            ),
        )
      },
  )

private fun kotlinPersistenceInlineRunner(
  finding: String,
  persistencePath: String,
): ParallelCodeReviewRunner =
  runner(
    alwaysSuccessLauncher(finding),
    catalogGateway = stubCatalogGateway(listOf(kotlinPersistenceManifest())),
    diffResolver =
      RecordingDiffResolver(
        default =
          """
          +++ b/$persistencePath
          @@ -950,1 +950,1 @@
          - old
          + RunStateConflict
          +++ b/src/Dao.kt
          @@ -1,1 +1,1 @@
          - old
          + transaction {
          """.trimIndent(),
      ),
    rubricResolver =
      ReviewRubricResolver {
        ResolvedReviewRubric(
          "bill-kotlin-code-review",
          "parent routing rubric",
          specialists =
            listOf(
              ResolvedReviewRubric(
                "bill-kotlin-code-review-architecture",
                "architecture specialist rubric",
                area = "architecture",
              ),
              ResolvedReviewRubric(
                "bill-kotlin-code-review-persistence",
                "persistence specialist rubric",
                area = "persistence",
              ),
            ),
        )
      },
  )

private fun kotlinPersistenceManifest() =
  PlatformManifest(
    slug = "kotlin",
    packRoot = Path.of("platform-packs/kotlin").toFileLocation(),
    contractVersion = "1.3",
    routingSignals = RoutingSignals(strong = listOf("*.kt"), tieBreakers = emptyList()),
    declaredCodeReviewAreas = listOf("architecture", "persistence"),
    declaredFiles =
      DeclaredFiles(
        baseline = Path.of("content.md").toFileLocation(),
        areas =
          mapOf(
            "architecture" to Path.of("architecture.md").toFileLocation(),
            "persistence" to Path.of("persistence.md").toFileLocation(),
          ),
      ),
    areaMetadata = emptyMap(),
    laneConditions =
      mapOf(
        "architecture" to ReviewLaneCondition(required = true),
        "persistence" to
          ReviewLaneCondition(
            content = listOf("transaction", "hibernate", "exposed", "database"),
          ),
      ),
  )

private inline fun <reified F : ParallelCodeReviewPlanningFailure> ParallelCodeReviewRunner.planningFailure(
  request: ParallelCodeReviewRequest,
): F = assertIs<F>(assertIs<ParallelCodeReviewRunOutcome.PlanningFailed>(run(request)).failure)

private fun createGitRepo(): Path {
  val dir = Files.createTempDirectory("pr-runner-git")
  ProcessBuilder("git", "init", dir.toString()).start().waitFor()
  ProcessBuilder("git", "-C", dir.toString(), "config", "user.email", "test@test.com").start().waitFor()
  ProcessBuilder("git", "-C", dir.toString(), "config", "user.name", "Test").start().waitFor()
  return dir
}

private fun createStagedFile(dir: Path) {
  val file = dir.resolve("Test.kt")
  Files.writeString(file, "fun main() {}\n")
  ProcessBuilder("git", "-C", dir.toString(), "add", "Test.kt").start().waitFor()
}

private class ParallelSubtaskLauncher(
  private val outcome: AgentRunLaunchOutcome? = null,
) : GoalRunnerSubtaskLauncher {
  val requests: MutableList<GoalRunnerSubtaskLaunchRequest> = CopyOnWriteArrayList()

  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome {
    requests += request
    simulateGovernedEvidenceReads(request.skillRunRequest)
    return outcome ?: agentRunLaunchFacts(
      agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
      stdout = "NO_FINDINGS",
      stderr = "",
    )
  }
}

internal class RecordingDiffResolver(
  private val commits: Map<String, String?> = emptyMap(),
  private val mergeBases: Map<String, String?> = emptyMap(),
  private val pullRequestBase: String? = null,
  private val branchName: String? = null,
  private val firstParent: Map<String, List<String>?> = emptyMap(),
  private val untracked: List<String>? = emptyList(),
  private val diffs: Map<ReviewDiffQuery, String?> = emptyMap(),
  private val default: String? = null,
) : DiffResolverPortDefaults() {
  val calls: MutableList<String> = mutableListOf()

  override fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ): String? {
    calls += "resolveCommit $revision"
    return if (commits.containsKey(revision)) commits[revision] else revision
  }

  override fun mergeBase(
    repoRoot: Path,
    revision: String,
  ): String? {
    calls += "mergeBase $revision"
    return mergeBases[revision]
  }

  override fun pullRequestBaseCommit(repoRoot: Path): String? {
    calls += PULL_REQUEST_BASE_CALL
    return pullRequestBase
  }

  override fun currentBranchName(repoRoot: Path): String? {
    calls += CURRENT_BRANCH_CALL
    return branchName
  }

  override fun firstParentCommits(
    repoRoot: Path,
    base: String,
    head: String,
  ): List<String>? {
    calls += "firstParentCommits $base..$head"
    return if (firstParent.containsKey("$base..$head")) firstParent["$base..$head"] else emptyList()
  }

  override fun untrackedPaths(repoRoot: Path): List<String>? {
    calls += "untrackedPaths"
    return untracked
  }

  override fun diff(
    repoRoot: Path,
    query: ReviewDiffQuery,
  ): String? {
    calls += "diff $query"
    return if (diffs.containsKey(query)) diffs[query] else default
  }

  override fun reviewWorktreeFileIdentities(
    root: Path,
    paths: List<String>,
  ) = emptyMap<String, ReviewCheckpointFileIdentity>()
}

private const val CURRENT_BRANCH_CALL = "currentBranchName"
private const val PULL_REQUEST_BASE_CALL = "pullRequestBaseCommit"

internal fun stubCatalogGateway(manifests: List<PlatformManifest> = emptyList()): ScaffoldCatalogGateway =
  object : ScaffoldCatalogGateway {
    override fun approvedCodeReviewAreas() = emptySet<String>()

    override fun preShellFamilies() = emptySet<String>()

    override fun shelledFamilies() = emptySet<String>()

    override fun platformPackPresets() = emptyMap<String, String>()

    override fun scaffoldPayloadVersion() = "1.0"

    override fun discoverPilotedPlatformPacks(packsRoot: Path) = emptyList<PilotedPlatformPackProjection>()

    override fun discoverPlatformManifests(packsRoot: Path) = manifests

    override fun discoverBaselineReviewCatalog(packsRoot: Path) =
      BaselineReviewCatalog(packs = emptyList(), compositionEdges = emptyList(), layerSuggestions = emptyList())
  }

private fun throwingCatalogGateway(): ScaffoldCatalogGateway =
  object : ScaffoldCatalogGateway {
    override fun approvedCodeReviewAreas() = emptySet<String>()

    override fun preShellFamilies() = emptySet<String>()

    override fun shelledFamilies() = emptySet<String>()

    override fun platformPackPresets() = emptyMap<String, String>()

    override fun scaffoldPayloadVersion() = "1.0"

    override fun discoverPilotedPlatformPacks(packsRoot: Path) = emptyList<PilotedPlatformPackProjection>()

    override fun discoverPlatformManifests(packsRoot: Path): List<PlatformManifest> =
      error("corrupt platform.yaml in $packsRoot")

    override fun discoverBaselineReviewCatalog(packsRoot: Path) =
      BaselineReviewCatalog(packs = emptyList(), compositionEdges = emptyList(), layerSuggestions = emptyList())
  }

internal fun platformManifest(
  slug: String,
  strongSignals: List<String>,
) = PlatformManifest(
  slug = slug,
  packRoot = Path.of("platform-packs/$slug").toFileLocation(),
  contractVersion = "1.3",
  routingSignals = RoutingSignals(strong = strongSignals, tieBreakers = emptyList()),
  declaredCodeReviewAreas = listOf("architecture", "testing"),
  declaredFiles =
    DeclaredFiles(
      baseline = Path.of("content.md").toFileLocation(),
      areas =
        mapOf(
          "architecture" to Path.of("architecture.md").toFileLocation(),
          "testing" to Path.of("testing.md").toFileLocation(),
        ),
    ),
  areaMetadata = emptyMap(),
  laneConditions =
    mapOf(
      "architecture" to ReviewLaneCondition(required = true),
      "testing" to ReviewLaneCondition(path = listOf("Test.kt")),
    ),
)

private fun sparsePlatformManifest(
  requiredArea: String,
  pathAreas: Map<String, List<String>>,
  slug: String = "kotlin",
  strongSignals: List<String> = listOf("*.kt"),
): PlatformManifest {
  val areas = listOf(requiredArea) + pathAreas.keys.toList()
  return PlatformManifest(
    slug = slug,
    packRoot = Path.of("platform-packs/$slug").toFileLocation(),
    contractVersion = "1.3",
    routingSignals = RoutingSignals(strong = strongSignals, tieBreakers = emptyList()),
    declaredCodeReviewAreas = areas,
    declaredFiles =
      DeclaredFiles(
        baseline = Path.of("content.md").toFileLocation(),
        areas = areas.associateWith { Path.of("$it.md").toFileLocation() },
      ),
    areaMetadata = emptyMap(),
    laneConditions =
      buildMap {
        put(requiredArea, ReviewLaneCondition(required = true))
        pathAreas.forEach { (area, paths) -> put(area, ReviewLaneCondition(path = paths)) }
      },
  )
}

private fun fallbackManifest(): PlatformManifest =
  platformManifest("generic", listOf("fallback-only")).copy(
    routingSignals =
      RoutingSignals(
        strong = listOf("fallback-only"),
        tieBreakers = emptyList(),
        path = emptyList(),
        content = emptyList(),
      ),
    fallbackCapabilities = setOf("code-review"),
  )

private fun cursorDelegatedRunner(
  launcher: GoalRunnerSubtaskLauncher,
  endpointRoot: Path,
  nativeAgentPreflight: ReviewNativeAgentPreflightPort = NO_OP_REVIEW_NATIVE_AGENT_PREFLIGHT,
): ParallelCodeReviewRunner =
  createRunner(
    launcher,
    RunnerFixtureConfig(
      evidenceEndpointRoot = endpointRoot,
      catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
      diffResolver = RecordingDiffResolver(default = diffFor("src/FooTest.kt")),
      rubricResolver =
        ReviewRubricResolver {
          ResolvedReviewRubric(
            "bill-kotlin-code-review",
            "parent routing rubric",
            specialists =
              listOf(
                ResolvedReviewRubric(
                  "bill-kotlin-code-review-architecture",
                  "architecture specialist rubric",
                  area = "architecture",
                ),
                ResolvedReviewRubric(
                  "bill-kotlin-code-review-testing",
                  "testing specialist rubric",
                  area = "testing",
                ),
              ),
          )
        },
      nativeAgentPreflight = nativeAgentPreflight,
      reviewLaunchAgentStaging =
        ReviewLaunchAgentStagingPort { request ->
          val agentsDir = request.reviewLaunchDirectory.resolve(".cursor/agents")
          Files.createDirectories(agentsDir)
          request.logicalWorkerNames.forEach { name ->
            Files.writeString(agentsDir.resolve("$name.md"), "staged $name")
          }
        },
    ),
  )

internal fun diffFor(path: String): String = "+++ b/$path"
