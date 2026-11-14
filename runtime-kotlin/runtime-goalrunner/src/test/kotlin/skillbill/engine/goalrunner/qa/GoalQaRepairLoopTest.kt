package skillbill.engine.goalrunner.qa

import org.junit.jupiter.api.io.TempDir
import skillbill.application.testProjectStore
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.lifecycle.execution.workflowAgentAssignmentStoreForTests
import skillbill.engine.featuretask.model.execution.QaExpectedIdentity
import skillbill.engine.featuretask.model.execution.QaNamedDevice
import skillbill.engine.featuretask.model.execution.QaNamedDeviceKind
import skillbill.engine.featuretask.phaserun.PhaseRunEntryFixture
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.phaserun.phaseRunEntry
import skillbill.engine.featuretask.phaserun.plans
import skillbill.engine.featuretask.phaserun.qa.InMemoryQaWorkspaces
import skillbill.engine.featuretask.phaserun.qa.RecordingQaLeases
import skillbill.engine.featuretask.phaserun.testStandalonePhaseRegistration
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.qa.execution.testQaExecutionLifecycle
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecorder
import skillbill.engine.featuretask.qa.report.QA_TEST_HEAD
import skillbill.engine.featuretask.qa.report.QA_TEST_TREE
import skillbill.engine.featuretask.qa.report.QaReportSettlement
import skillbill.engine.featuretask.qa.report.QaSettledRun
import skillbill.engine.featuretask.qa.report.QaVerdict
import skillbill.engine.featuretask.qa.report.qaTestEvidencePrefix
import skillbill.engine.featuretask.qa.report.qaTestReport
import skillbill.engine.featuretask.qa.report.recordSettledForTest
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.committedRepoBranchSetup
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.kotlinPackWithBuildGate
import skillbill.engine.featuretask.runner.phaseIdFromPrompt
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingGoalRunnerAssessmentGate
import skillbill.engine.goalrunner.RecordingPullRequestPort
import skillbill.engine.goalrunner.TestGoalQualityOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerFinalHistoryWriter
import skillbill.engine.goalrunner.execution.core.GoalRunnerFinalization
import skillbill.engine.goalrunner.execution.core.GoalRunnerFinalizationStages
import skillbill.engine.goalrunner.execution.core.GoalRunnerPauseBoundary
import skillbill.engine.goalrunner.execution.core.GoalRunnerPullRequestWriter
import skillbill.engine.goalrunner.execution.core.GoalRunnerPullRequests
import skillbill.engine.goalrunner.execution.core.goalRunnerDefaultPhaseRecorder
import skillbill.engine.goalrunner.execution.core.testActivityStampWriter
import skillbill.engine.goalrunner.execution.core.testGoalFinalizationRoutingBinder
import skillbill.engine.goalrunner.execution.support.GoalSpecScratchCleanup
import skillbill.engine.goalrunner.execution.support.toPullRequestRequest
import skillbill.engine.goalrunner.model.GoalQaSummary
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.monitoring.GoalRunnerCiMonitor
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.quality.GoalQualityPreQaBuildState
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskQaFailureKind
import skillbill.ports.featuretask.model.FeatureTaskQaFindingStatus
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.ports.workflow.specscratch.UnavailableSpecScratchStore
import skillbill.scaffold.model.QaCapabilityDeclaration
import skillbill.scaffold.model.QaTargetKind
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GoalQaRepairLoopTest {
  @TempDir lateinit var home: Path

  @Test
  fun `goal QA prompt carries the device from the current launch`() {
    val fixture =
      Fixture(
        verdicts = listOf(QaVerdict.PASSED),
        qaFixOutput = { "" },
        qaDevice = QaNamedDevice(QaNamedDeviceKind.AVD, "Pixel_8_API_35"),
      )

    fixture.gate.run(fixture.state, fixture.request)

    assertContains(fixture.prompts("qa").single(), "AVD `Pixel_8_API_35`")
  }

  @Test
  fun `goal QA hands the finding to qa_fix, builds and retests the repaired tree, then validates once`() {
    val fixture =
      Fixture(
        verdicts = listOf(QaVerdict.FINDINGS_OPEN, QaVerdict.PASSED),
        onQaFix = { git -> git.repositoryFingerprintValue = REPAIRED_TREE },
        onValidate = { git -> git.repositoryFingerprintValue = FORMATTED_TREE },
        qaFixOutput = { "F-1: fixed: HomeScreen.kt now renders the signed-in user name." },
      )

    val result = fixture.finalizeAfterAssessment()

    assertEquals(
      listOf("build", "qa", "qa_fix", "build", "qa", "validate"),
      fixture.startedPhases(),
      result.toString(),
    )
    val repairPrompt = fixture.prompts("qa_fix").single()
    val evidence = "${qaTestEvidencePrefix("qa-1")}home-F-1.png"
    assertContains(
      repairPrompt,
      "### F-1 Home screen stays blank\n" +
        "Steps: open the app, log in.\n" +
        "Expected: the home screen shows the user name.\n" +
        "Observed: the home screen stays blank.\n" +
        "Evidence: $evidence\n\n\n\nEvidence: $evidence",
    )
    val outcome = assertNotNull(fixture.storedOutcome())
    assertEquals(StandaloneQaOutcome.INCOMPLETE, outcome.outcome)
    assertContains(assertNotNull(outcome.reason), "validate changed code after QA settled")
    assertNull(outcome.failureKind)
    assertEquals(1, outcome.qaFixAttempts)
    assertEquals(REPAIRED_TREE, outcome.testedTreeIdentity)
    assertContains(assertNotNull(outcome.reportText), "Verdict: passed")
    assertContains(assertIs<GoalQaGateResult.Warned>(result).reason, "validate changed code after QA settled")
  }

  @Test
  fun `a qa_fix that leaves the app failing to compile past the build repair cap settles QA with a warning`() {
    val fixture =
      Fixture(
        verdicts = listOf(QaVerdict.FINDINGS_OPEN),
        onQaFix = { git -> git.repositoryFingerprintValue = REPAIRED_TREE },
        qaFixOutput = { "F-1: fixed: HomeScreen.kt now renders the signed-in user name." },
        buildGateRunner = InitialBuildThen(FailingBuildGateRunner),
      )

    val result = fixture.gate.run(fixture.state, fixture.request)

    val phases = fixture.startedPhases()
    assertEquals(listOf("build", "qa", "qa_fix"), phases.take(3))
    assertEquals(1, phases.count { it == "qa" })
    assertEquals("validate", phases.last())
    assertEquals(3, fixture.launchedPhases().count { it == "build" }, result.toString())
    val warning = assertIs<GoalQaGateResult.Warned>(result).reason
    assertContains(warning, "qa_fix left the app without a passing build, so QA did not re-test the fix")
    assertContains(warning, "after 3 repair turns")
    val outcome = assertNotNull(fixture.storedOutcome())
    assertEquals(StandaloneQaOutcome.INCOMPLETE, outcome.outcome)
    assertEquals(FeatureTaskQaFailureKind.ENVIRONMENT, outcome.failureKind)
    assertContains(assertNotNull(outcome.reason), "QA did not re-test the fix")
    assertEquals(listOf("F-1"), outcome.findings.map { it.id })
  }

  @Test
  fun `a build gate command that cannot start settles QA with its real error and validation still runs`() {
    val fixture =
      Fixture(
        verdicts = listOf(QaVerdict.FINDINGS_OPEN),
        onQaFix = { git -> git.repositoryFingerprintValue = REPAIRED_TREE },
        qaFixOutput = { "F-1: fixed: HomeScreen.kt now renders the signed-in user name." },
        buildGateRunner =
          InitialBuildThen(
            ThrowingBuildGateRunner(
              SkillBillRuntimeException(
                FeatureTaskRuntimeFailureCode.INVALID_QUALITY_GATE_CONFIGURATION,
                "Failed to launch process: No such file or directory",
              ),
            ),
          ),
      )

    val result = fixture.gate.run(fixture.state, fixture.request)

    assertEquals("validate", fixture.startedPhases().last())
    assertEquals(0, fixture.launchedPhases().count { it == "build" })
    val warning = assertIs<GoalQaGateResult.Warned>(result).reason
    assertContains(warning, "The build gate did not run after qa_fix")
    assertContains(warning, "Failed to launch process: No such file or directory")
    assertContains(warning, "validation_gate.gradle_wrapper")
    assertFalse(warning.contains("without a passing build"), warning)
  }

  @Test
  fun `an unexpected gate failure keeps its real error when the command outcome is unknown`() {
    val failure = IllegalStateException("gate runner exploded")
    val fixture =
      Fixture(
        verdicts = listOf(QaVerdict.FINDINGS_OPEN),
        onQaFix = { git -> git.repositoryFingerprintValue = REPAIRED_TREE },
        qaFixOutput = { "F-1: fixed: HomeScreen.kt now renders the signed-in user name." },
        buildGateRunner = ThrowingBuildGateRunner(failure),
      )

    val blocked = assertIs<GoalQaGateResult.Blocked>(fixture.gate.run(fixture.state, fixture.request))

    assertEquals("WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION", blocked.failureCode)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD, blocked.stepId)
    assertContains(blocked.reason, "gate runner exploded")
    assertFalse(blocked.reason.contains("after interruption"), blocked.reason)
  }

  @Test
  fun `a qa_fix that leaves a finding unfixed is sent back until it fixes it in source`() {
    var launches = 0
    var reports = 0
    val fixture =
      Fixture(
        verdicts = List(4) { QaVerdict.FINDINGS_OPEN },
        onQaFix = { git -> if (++launches == 2) git.repositoryFingerprintValue = "5".repeat(64) },
        qaFixOutput = {
          if (reports++ == 0) {
            "F-1: not changed: the tested APK predates HEAD."
          } else {
            "F-1: fixed: HomeScreen.kt binds the explicit name."
          }
        },
      )

    fixture.gate.run(fixture.state, fixture.request)

    assertEquals(listOf("qa", "qa_fix", "qa_fix", "qa"), fixture.launchedPhases().take(4))
    val retry = fixture.prompts("qa_fix")[1]
    assertContains(retry, "none may be left unchanged")
    assertContains(retry, "Not fixed: F-1")
  }

  @Test
  fun `a relaunched goal continues the recorded qa_fix attempt count instead of starting over`() {
    var repairs = 0
    val fixture =
      Fixture(
        verdicts = List(4) { QaVerdict.FINDINGS_OPEN },
        onQaFix = { git -> git.repositoryFingerprintValue = (++repairs + 4).toString().repeat(64) },
        qaFixOutput = { "F-1: fixed: retried the home screen binding." },
        recordedAttempts = 2,
      )

    fixture.gate.run(fixture.state, fixture.request)

    assertEquals(listOf("qa", "qa_fix", "qa", "validate"), fixture.launchedPhases())
    val outcome = assertNotNull(fixture.storedOutcome())
    assertEquals(StandaloneQaOutcome.FINDINGS, outcome.outcome, outcome.reason)
    assertEquals(3, outcome.qaFixAttempts)
    assertContains(assertNotNull(outcome.reason), "remain after 3 qa_fix attempts")
  }

  @Test
  fun `findings QA cannot fix in three attempts are recorded as not fixed beside the ones it fixed`() {
    var repairs = 0
    val fixture =
      Fixture(
        verdicts = List(4) { QaVerdict.FINDINGS_OPEN },
        findingIds = listOf(listOf("F-1", "F-2"), listOf("F-2"), listOf("F-2"), listOf("F-2")),
        onQaFix = { git -> git.repositoryFingerprintValue = (++repairs + 4).toString().repeat(64) },
        qaFixOutput = { "F-1: fixed: bound the user name.\nF-2: fixed: retried the settings crash." },
      )

    fixture.gate.run(fixture.state, fixture.request)

    assertEquals("validate", fixture.startedPhases().last())
    val outcome = assertNotNull(fixture.storedOutcome())
    assertEquals(StandaloneQaOutcome.FINDINGS, outcome.outcome, outcome.reason)
    assertEquals(3, outcome.qaFixAttempts)
    assertEquals(
      listOf("F-1" to FeatureTaskQaFindingStatus.FIXED, "F-2" to FeatureTaskQaFindingStatus.NOT_FIXED),
      outcome.findings.map { it.id to it.status },
    )
    val open = outcome.notFixedFindings.single()
    assertEquals("Home screen stays blank", open.title)
    assertContains(open.evidencePaths.single(), "home-F-2.png")
    val body = fixture.state.manifest.toPullRequestRequest(home, GoalQaSummary.Recorded(outcome)).body
    assertContains(body, "fixed: F-1")
    assertContains(body, "not fixed, review them yourself: F-2 \"Home screen stays blank\"")
  }

  @Test
  fun `a goal QA that passes after qa_fix records every finding it caught as fixed`() {
    val fixture =
      Fixture(
        verdicts = listOf(QaVerdict.FINDINGS_OPEN, QaVerdict.PASSED),
        findingIds = listOf(listOf("F-1", "F-2"), emptyList()),
        onQaFix = { git -> git.repositoryFingerprintValue = REPAIRED_TREE },
        qaFixOutput = { "F-1: fixed: bound the user name.\nF-2: fixed: retried the settings crash." },
      )

    fixture.gate.run(fixture.state, fixture.request)

    val outcome = assertNotNull(fixture.storedOutcome())
    assertEquals(StandaloneQaOutcome.PASSED, outcome.outcome)
    assertEquals(1, outcome.qaFixAttempts)
    assertEquals(
      listOf("F-1" to FeatureTaskQaFindingStatus.FIXED, "F-2" to FeatureTaskQaFindingStatus.FIXED),
      outcome.findings.map { it.id to it.status },
    )
  }

  @Test
  fun `a QA agent that writes no report settles QA incomplete with a warning and never blocks the goal`() {
    val fixture = Fixture(verdicts = listOf(null), qaFixOutput = { error("qa_fix must not run without findings.") })

    val result = fixture.gate.run(fixture.state, fixture.request)

    assertEquals(listOf("qa", "validate"), fixture.launchedPhases())
    assertContains(assertIs<GoalQaGateResult.Warned>(result).reason, "QA agent wrote no report.md")
    val outcome = assertNotNull(fixture.storedOutcome())
    assertEquals(StandaloneQaOutcome.INCOMPLETE, outcome.outcome)
    assertContains(assertNotNull(outcome.reason), "QA agent wrote no report.md")
    assertEquals(emptyList(), outcome.findings)
  }

  @Test
  fun `a free-form report with no verdict word settles incomplete and keeps its full text`() {
    val prose = "I tapped around the login flow and it looked fine to me, but the device dropped twice."
    val fixture =
      Fixture(verdicts = listOf(null), qaFixOutput = { error("qa_fix must not run.") }, freeFormReport = prose)

    fixture.gate.run(fixture.state, fixture.request)

    val outcome = assertNotNull(fixture.storedOutcome())
    assertEquals(StandaloneQaOutcome.INCOMPLETE, outcome.outcome)
    assertEquals(prose, outcome.reportText)
  }

  @Test
  fun `QA resume retains the admitted agent after a provider limit`() {
    val fixture = Fixture(verdicts = listOf(QaVerdict.PASSED), qaFixOutput = { "unused" }, limitFirstQa = true)

    assertIs<GoalQaGateResult.Paused>(fixture.gate.run(fixture.state, fixture.request))
    fixture.gate.run(fixture.state, fixture.request.copy(invokedAgentId = "codex"))

    assertEquals(listOf("claude", "claude"), fixture.qaAgents())
    assertEquals(StandaloneQaOutcome.PASSED, assertNotNull(fixture.storedOutcome()).outcome)
  }

  private inner class Fixture(
    verdicts: List<QaVerdict?>,
    findingIds: List<List<String>>? = null,
    qaFixOutput: () -> String,
    onQaFix: (RecordingWorkflowGitOperations) -> Unit = {},
    onValidate: (RecordingWorkflowGitOperations) -> Unit = {},
    recordedAttempts: Int = 0,
    buildGateRunner: ValidationGateRunner? = null,
    freeFormReport: String? = null,
    limitFirstQa: Boolean = false,
    qaDevice: QaNamedDevice? = null,
  ) {
    private val clock = Clock.systemUTC()
    private val database: DatabaseSessionFactory = phaseRunDatabase(home, clock)
    private val branchSetup = committedRepoBranchSetup()
    private val git =
      branchSetup.gitOperations.also {
        it.currentBranchValue = BRANCH
        it.runtimePhaseHeadCommitSequence += List(100) { QA_TEST_HEAD }
        it.repositoryFingerprintValue = QA_TEST_TREE
        it.trackedPathsValue = listOf("src/HomeScreen.kt")
      }
    private val workspaces = InMemoryQaWorkspaces()
    private var qaVisits = 0
    private val launcher =
      RuntimeRecordingLauncher { request ->
        val phase = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phase == "qa" && limitFirstQa && qaVisits == 0) {
          qaVisits++
          agentRunLaunchFacts(
            agent = SupportedAgent.CLAUDE,
            termination = AgentRunTermination.Exited(1),
            stdout = "",
            stderr = "You've hit your usage limit",
          )
        } else {
          val output =
            when (phase) {
              "qa" -> {
                val quality = assertNotNull(progress.goalQualityExecution(PARENT)?.execution)
                assertEquals(GoalQualityPreQaBuildState.PASSED, quality.preQaBuild)
                qaVisits++
                val identity =
                  QaExpectedIdentity(
                    QA_TEST_HEAD,
                    checkNotNull(git.repositoryFingerprintValue),
                    qaTestEvidencePrefix("qa-$qaVisits"),
                  )
                val verdict = verdicts[qaVisits - 1 - if (limitFirstQa) 1 else 0]
                val ids = findingIds?.get(qaVisits - 1 - if (limitFirstQa) 1 else 0)
                workspaces.report =
                  when {
                    freeFormReport != null -> freeFormReport
                    verdict == null -> null
                    ids == null -> qaTestReport(verdict, identity)
                    else -> qaTestReport(verdict, identity, ids)
                  }?.let { report ->
                    if (freeFormReport == null) "$report\n## Coverage\n- android: passed\n" else report
                  }
                "QA settled."
              }
              "qa_fix" -> qaFixOutput().also { onQaFix(git) }
              "build" -> "Build repair attempted."
              else -> "Validation passed.".also { onValidate(git) }
            }
          facts(output)
        }
      }
    private val outcomes = FeatureTaskQaOutcomeRecorder(database, clock)
    private val progress = DatabaseGoalQaOutcomes(database)

    val state =
      GoalRunnerManifestState(
        parentWorkflowId = PARENT,
        dbPath = home.resolve("metrics.db").toString(),
        manifest =
          DecompositionManifest(
            issueKey = "SKILL-500",
            featureName = "goal-qa-repair",
            parentSpecPath = "parent-spec.md",
            baseBranch = "main",
            featureBranch = BRANCH,
            currentSubtaskIntent = CurrentSubtaskIntent(1, "complete"),
            subtasks = listOf(DecompositionSubtask(id = 1, name = "Implementation", specPath = "subtask.md")),
          ),
      )
    val request =
      GoalRunnerRunRequest(
        issueKey = "SKILL-500",
        repoRoot = home,
        invokedAgentId = "claude",
        qaDevice = qaDevice,
      )
    val gate: DefaultGoalRunnerQaGate

    init {
      if (recordedAttempts > 0) {
        outcomes.recordSettledForTest(
          "$PARENT:test-quality",
          QaVerdict.FINDINGS_OPEN,
          null,
          recordedAttempts,
          QaSettledRun(PREVIOUS_TREE),
        )
      }
      val pack =
        kotlinPackWithBuildGate().copy(
          qaCapability = QaCapabilityDeclaration(setOf(QaTargetKind("android")), "qa/android-qa.md"),
        )
      var invocations = 0
      val config =
        RuntimeHarnessConfig(
          seedDurableWorkflow = false,
          branchSetup = branchSetup,
          repoRoot = home,
          launcher = launcher,
          qaExecution =
            testQaExecutionLifecycle(
              git,
              workspaces,
              RecordingQaLeases(),
              QaCapabilityResolver(git) { listOf(pack) },
              IdentifierGeneratorPort { "${++invocations}" },
            ),
          qaReports = QaReportSettlement(workspaces, NoopRuntimeDiagnostics),
          validationGateRunner = buildGateRunner,
          validationGatePlatformManifests = listOf(kotlinPackWithBuildGate()),
        )
      val harness = telemetryRunnerHarness(runtimeConfig = config, databaseFactory = { database })
      val entry =
        phaseRunEntry(
          harness.strategies,
          config.harnessGitOperations,
          database,
          clock,
          harness.runLoopEntry.delegateTo(TestFeatureTaskRuntimeRunLoopEntry(gitOperations = git)),
        )
      val phasePlans =
        PhaseRunEntryFixture(
          harness.strategies,
          config.harnessGitOperations,
          database,
          clock,
          qaManifests = listOf(pack),
        ).plans()
      val requests = GoalQualityPhaseRequests(progress, git, clock, testActivityStampWriter())
      gate =
        DefaultGoalRunnerQaGate(
          entry,
          phasePlans,
          progress,
          git,
          GoalQualityPreparation(
            NoopRuntimeDiagnostics,
            phasePlans,
            requests,
            progress,
            git,
            object : FeatureTaskRuntimeRunInvariantsSource {
              override fun read(specPath: Path) =
                FeatureTaskRuntimeRunInvariantsRead.Read(
                  FeatureTaskRuntimeRunInvariants(
                    specReference = "SKILL-500",
                    acceptanceCriteria = listOf("The home screen shows the signed-in user name."),
                    mandatesAndOverrides = emptyList(),
                  ),
                )
            },
            testProjectStore,
            testStandalonePhaseRegistration(clock, NoopRuntimeDiagnostics),
          ),
          requests,
          GoalQualityBuildBoundary(entry, phasePlans, progress, git, requests, NoopRuntimeDiagnostics),
          outcomes,
          QaCapabilityResolver(git) { listOf(pack) },
          NoopRuntimeDiagnostics,
          workflowAgentAssignmentStoreForTests(database),
        )
    }

    fun finalizeAfterAssessment(): GoalQaGateResult {
      val events = mutableListOf<String>()
      var qualityResult: GoalQaGateResult? = null
      val cleanup = GoalSpecScratchCleanup(UnavailableSpecScratchStore, testProjectStore, NoopRuntimeDiagnostics)
      val finalizationRouting =
        testGoalFinalizationRoutingBinder(database, clock, NoopRuntimeDiagnostics)
      val publication =
        GoalRunnerPullRequests(
          progress,
          RecordingPullRequestPort(),
          PullRequestIdentityLookup { _, _ -> PullRequestIdentity.Absent },
          cleanup,
          null,
          NoopRuntimeDiagnostics,
          GoalRunnerPullRequestWriter { _, _, _ -> error("Publication must follow the history boundary.") },
          finalizationRouting,
        )
      val finalization =
        GoalRunnerFinalization(
          progress,
          cleanup,
          git,
          NoopRuntimeDiagnostics,
          goalRunnerDefaultPhaseRecorder(),
          GoalRunnerFinalizationStages(
            GoalRunnerCiMonitor { _, _ -> error("CI must follow the history boundary.") },
            GoalRunnerQaGate { state, request ->
              assertEquals(listOf("assessment"), events)
              gate.run(state, request).also { qualityResult = it }
            },
            RecordingGoalRunnerAssessmentGate(events),
          ),
          GoalRunnerPauseBoundary(InMemoryGoalManifestStore(state.manifest), clock),
          GoalRunnerFinalHistoryWriter { _, _, _ ->
            events += "history"
            PhaseRunResult.Blocked("history", emptyList(), null, "write_history", "test history boundary")
          },
          publication,
          finalizationRouting,
        )

      val report = finalization.finalizePublication(state, request, listOf(1))

      assertContains(
        assertIs<GoalRunnerRunReport.Stopped>(report).stop.blockedReason.orEmpty(),
        "The boundary history writer did not complete with accepted output.",
      )
      assertEquals(listOf("assessment", "history"), events)
      return assertNotNull(qualityResult)
    }

    fun qaAgents(): List<String> =
      launcher.requests.filter { phaseIdFromPrompt(requireNotNull(it.skillRunRequest.promptOverride)) == "qa" }
        .map { it.configuredAgentOverrideId ?: it.invokedAgentId }

    fun launchedPhases(): List<String> =
      launcher.requests.map { phaseIdFromPrompt(requireNotNull(it.skillRunRequest.promptOverride)) }

    fun startedPhases(): List<String> = progress.startedPhases

    fun prompts(phase: String): List<String> =
      launcher.requests.mapNotNull { it.skillRunRequest.promptOverride }.filter { phaseIdFromPrompt(it) == phase }

    fun storedOutcome(): FeatureTaskQaOutcome? = database.read { it.featureTaskQaOutcomes.find("$PARENT:test-quality") }
  }

  private class DatabaseGoalQaOutcomes(
    private val database: DatabaseSessionFactory,
  ) : TestGoalQualityOutcomeStore() {
    override fun recordedGoalQaOutcome(parentWorkflowId: String): FeatureTaskQaOutcome? =
      database.read { it.featureTaskQaOutcomes.find(parentWorkflowId) }

    val startedPhases = mutableListOf<String>()

    override fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest): Boolean {
      if (request.draft.eventKind == GoalProgressEventKind.PHASE_STARTED) startedPhases += request.draft.workflowPhase
      return true
    }
  }

  private class InitialBuildThen(private val later: ValidationGateRunner) : ValidationGateRunner {
    private var calls = 0

    override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
      if (calls++ == 0) {
        return ValidationGateRunResult(
          exitCode = 0,
          durationMs = 1,
          outcome = ValidationGateRunOutcome.PASSED,
          cacheMode = request.cacheMode,
          executedWorkUnits = 1,
          executedCheckIdentities = emptyList(),
          findings = emptyList(),
          command = request.argv.joinToString(" "),
        )
      }
      return later.run(request)
    }
  }

  private class ThrowingBuildGateRunner(private val failure: Exception) : ValidationGateRunner {
    override fun run(request: ValidationGateRunRequest): ValidationGateRunResult = throw failure
  }

  private object FailingBuildGateRunner : ValidationGateRunner {
    override fun run(request: ValidationGateRunRequest) =
      ValidationGateRunResult(
        exitCode = 1,
        durationMs = 1,
        outcome = ValidationGateRunOutcome.FAILED,
        cacheMode = request.cacheMode,
        executedWorkUnits = 1,
        executedCheckIdentities = emptyList(),
        findings = listOf(ValidationGateFinding("app", "compileKotlin", "Unresolved reference: userName", "A.kt")),
        command = request.argv.joinToString(" "),
      )
  }

  private companion object {
    const val PARENT = "goal-parent"
    const val BRANCH = "feat/SKILL-500-goal"
    val REPAIRED_TREE = "4".repeat(64)
    val FORMATTED_TREE = "5".repeat(64)
    val PREVIOUS_TREE = "9".repeat(64)
  }
}
