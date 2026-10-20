package skillbill.engine.goalrunner.qa

import org.junit.jupiter.api.io.TempDir
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.execution.QaExpectedIdentity
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.phaserun.phaseRunEntry
import skillbill.engine.featuretask.phaserun.qa.InMemoryQaWorkspaces
import skillbill.engine.featuretask.phaserun.qa.RecordingQaLeases
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.qa.execution.testQaExecutionLifecycle
import skillbill.engine.featuretask.qa.receipt.FeatureTaskQaOutcomeRecorder
import skillbill.engine.featuretask.qa.receipt.QA_TEST_HEAD
import skillbill.engine.featuretask.qa.receipt.QA_TEST_TREE
import skillbill.engine.featuretask.qa.receipt.QaReceiptSettlement
import skillbill.engine.featuretask.qa.receipt.QaReceiptValidator
import skillbill.engine.featuretask.qa.receipt.QaVerdict
import skillbill.engine.featuretask.qa.receipt.qaTestEvidencePrefix
import skillbill.engine.featuretask.qa.receipt.qaTestJson
import skillbill.engine.featuretask.qa.receipt.qaTestReceipt
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.committedRepoBranchSetup
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.kotlinPackWithBuildGate
import skillbill.engine.featuretask.runner.kotlinPackWithValidationGate
import skillbill.engine.featuretask.runner.phaseIdFromPrompt
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.goalrunner.execution.core.testActivityStampWriter
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.work.EmptyOutcomeStore
import skillbill.infrastructure.contracts.qa.QaReceiptSchemaValidator
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.scaffold.model.QaCapabilityDeclaration
import skillbill.scaffold.model.QaPlatform
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class GoalQaRepairLoopTest {
  @TempDir lateinit var home: Path

  @Test
  fun `goal QA hands the finding to qa_fix, builds and retests the repaired tree, then validates once`() {
    val fixture =
      Fixture(
        verdicts = listOf(QaVerdict.FINDINGS_OPEN, QaVerdict.PASSED),
        onQaFix = { git -> git.repositoryFingerprintValue = REPAIRED_TREE },
        onValidate = { git -> git.repositoryFingerprintValue = FORMATTED_TREE },
        qaFixOutput = { "F-1: fixed: HomeScreen.kt now renders the signed-in user name." },
      )

    val result = fixture.gate.run(fixture.state, fixture.request)

    assertEquals(listOf("qa", "qa_fix", "build", "qa", "validate"), fixture.startedPhases())
    val repairPrompt = fixture.prompts("qa_fix").single()
    assertContains(repairPrompt, "### F-1")
    assertContains(repairPrompt, "- Steps: Open the app; Log in")
    assertContains(repairPrompt, "- Expected: The home screen shows the user name.")
    assertContains(repairPrompt, "- Observed: The home screen stays blank.")
    val outcome = assertNotNull(fixture.storedOutcome())
    assertEquals(StandaloneQaOutcome.PASSED, outcome.outcome)
    assertEquals(1, outcome.qaFixAttempts)
    assertEquals(REPAIRED_TREE, outcome.testedTreeIdentity)
    assertContains(assertIs<GoalQaGateResult.Warned>(result).reason, "validate changed code after QA settled")
  }

  @Test
  fun `a qa_fix that leaves the app failing to compile past the build repair cap settles QA with a warning`() {
    val fixture =
      Fixture(
        verdicts = listOf(QaVerdict.FINDINGS_OPEN),
        onQaFix = { git -> git.repositoryFingerprintValue = REPAIRED_TREE },
        qaFixOutput = { "F-1: fixed: HomeScreen.kt now renders the signed-in user name." },
        buildGateRunner = FailingBuildGateRunner,
      )

    val result = fixture.gate.run(fixture.state, fixture.request)

    val phases = fixture.startedPhases()
    assertEquals(listOf("qa", "qa_fix", "build"), phases.take(3))
    assertEquals(1, phases.count { it == "qa" })
    assertEquals("validate", phases.last())
    assertEquals(3, fixture.launchedPhases().count { it == "build" })
    val warning = assertIs<GoalQaGateResult.Warned>(result).reason
    assertContains(warning, "qa_fix left the app without a passing build, so QA did not re-test the fix")
    assertContains(warning, "after 3 repair turns")
    val outcome = assertNotNull(fixture.storedOutcome())
    assertEquals(StandaloneQaOutcome.FINDINGS, outcome.outcome)
    assertContains(assertNotNull(outcome.reason), "QA did not re-test the fix")
  }

  @Test
  fun `a qa_fix that changes no file is sent back until it gives each finding a reason, and the reason is reported`() {
    val reason = "F-1: not changed: the tested APK predates HEAD; HomeScreen.kt:42 already renders the name."
    var reports = 0
    val fixture =
      Fixture(
        verdicts = List(4) { QaVerdict.FINDINGS_OPEN },
        qaFixOutput = { if (reports++ == 0) "I looked at the home screen." else reason },
      )

    fixture.gate.run(fixture.state, fixture.request)

    val phases = fixture.launchedPhases()
    assertEquals(listOf("qa", "qa_fix", "qa_fix", "qa"), phases.take(4))
    assertFalse("validate" in phases)
    assertContains(fixture.prompts("qa_fix")[1], "Missing: F-1")
    val outcome = assertNotNull(fixture.storedOutcome())
    assertContains(assertNotNull(outcome.reason), "changed no file for QA findings F-1")
    assertContains(assertNotNull(outcome.reason), "the tested APK predates HEAD")
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
    assertEquals(StandaloneQaOutcome.FINDINGS, outcome.outcome)
    assertEquals(3, outcome.qaFixAttempts)
    assertContains(assertNotNull(outcome.reason), "remain after 3 qa_fix attempts")
  }

  private inner class Fixture(
    verdicts: List<QaVerdict>,
    qaFixOutput: () -> String,
    onQaFix: (RecordingWorkflowGitOperations) -> Unit = {},
    onValidate: (RecordingWorkflowGitOperations) -> Unit = {},
    recordedAttempts: Int = 0,
    buildGateRunner: ValidationGateRunner? = null,
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
        val output =
          when (phase) {
            "qa" -> {
              qaVisits++
              val identity =
                QaExpectedIdentity(
                  QA_TEST_HEAD,
                  checkNotNull(git.repositoryFingerprintValue),
                  qaTestEvidencePrefix("qa-$qaVisits"),
                )
              workspaces.receipt =
                qaTestJson(qaTestReceipt(verdicts[qaVisits - 1], identity))
                  .replace("AC-1", "AC-001")
                  .replace("\"standalone\"", "\"auto\"")
              "QA settled."
            }
            "qa_fix" -> qaFixOutput().also { onQaFix(git) }
            "build" -> "Build repair attempted."
            else -> "Validation passed.".also { onValidate(git) }
          }
        facts(output)
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
    val request = GoalRunnerRunRequest(issueKey = "SKILL-500", repoRoot = home, invokedAgentId = "claude")
    val gate: DefaultGoalRunnerQaGate

    init {
      if (recordedAttempts > 0) {
        outcomes.recordSettled(PARENT, QaVerdict.FINDINGS_OPEN, null, recordedAttempts, PREVIOUS_TREE)
      }
      val pack =
        kotlinPackWithValidationGate().copy(
          qaCapability = QaCapabilityDeclaration(setOf(QaPlatform.ANDROID), "qa/android-qa.md"),
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
              QaCapabilityResolver { listOf(pack) },
              IdentifierGeneratorPort { "${++invocations}" },
            ),
          qaReceipts =
            QaReceiptSettlement(QaReceiptValidator(QaReceiptSchemaValidator()), workspaces, NoopRuntimeDiagnostics),
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
          harness.runLoopEntry.delegateTo(TestFeatureTaskRuntimeRunLoopEntry()),
        )
      gate =
        DefaultGoalRunnerQaGate(
          entry,
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
          clock,
          testActivityStampWriter(),
          outcomes,
        )
    }

    fun launchedPhases(): List<String> =
      launcher.requests.map { phaseIdFromPrompt(requireNotNull(it.skillRunRequest.promptOverride)) }

    fun startedPhases(): List<String> = progress.startedPhases

    fun prompts(phase: String): List<String> =
      launcher.requests.mapNotNull { it.skillRunRequest.promptOverride }.filter { phaseIdFromPrompt(it) == phase }

    fun storedOutcome(): FeatureTaskQaOutcome? = database.read { it.featureTaskQaOutcomes.find(PARENT) }
  }

  private class DatabaseGoalQaOutcomes(
    private val database: DatabaseSessionFactory,
  ) : GoalRunnerWorkflowOutcomeStore by EmptyOutcomeStore {
    override fun recordedGoalQaOutcome(parentWorkflowId: String): FeatureTaskQaOutcome? =
      database.read { it.featureTaskQaOutcomes.find(parentWorkflowId) }

    val startedPhases = mutableListOf<String>()

    override fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest): Boolean {
      if (request.draft.eventKind == GoalProgressEventKind.PHASE_STARTED) startedPhases += request.draft.workflowPhase
      return true
    }
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
