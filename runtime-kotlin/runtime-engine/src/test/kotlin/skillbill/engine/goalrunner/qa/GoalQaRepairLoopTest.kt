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
import skillbill.engine.featuretask.qa.receipt.QaSettledRun
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
import skillbill.engine.goalrunner.execution.support.toPullRequestRequest
import skillbill.engine.goalrunner.model.GoalQaSummary
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.work.EmptyOutcomeStore
import skillbill.infrastructure.contracts.qa.QaReceiptSchemaValidator
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskQaFindingStatus
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
    assertEquals(listOf("AC-001"), outcome.report?.criteria?.map { it.id })
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
    assertEquals(StandaloneQaOutcome.FINDINGS, outcome.outcome)
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
    assertEquals(StandaloneQaOutcome.FINDINGS, outcome.outcome)
    assertEquals(3, outcome.qaFixAttempts)
    assertEquals(
      listOf("F-1" to FeatureTaskQaFindingStatus.FIXED, "F-2" to FeatureTaskQaFindingStatus.NOT_FIXED),
      outcome.findings.map { it.id to it.status },
    )
    val open = outcome.notFixedFindings.single()
    assertEquals("The F-2 screen stays blank.", open.title)
    assertContains(assertNotNull(open.evidencePath), "home.png")
    val body = fixture.state.manifest.toPullRequestRequest(home, GoalQaSummary.Recorded(outcome)).body
    assertContains(body, "fixed: F-1")
    assertContains(body, "not fixed, review them yourself: F-2 \"The F-2 screen stays blank.\"")
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

  private inner class Fixture(
    verdicts: List<QaVerdict>,
    findingIds: List<List<String>>? = null,
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
              val receipt = qaTestReceipt(verdicts[qaVisits - 1], identity)
              workspaces.receipt =
                qaTestJson(findingIds?.let { receipt.withFindings(it[qaVisits - 1]) } ?: receipt)
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
        outcomes.recordSettled(PARENT, QaVerdict.FINDINGS_OPEN, null, recordedAttempts, QaSettledRun(PREVIOUS_TREE))
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
              QaCapabilityResolver(git) { listOf(pack) },
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
          QaCapabilityResolver(git) { listOf(pack) },
        )
    }

    fun launchedPhases(): List<String> =
      launcher.requests.map { phaseIdFromPrompt(requireNotNull(it.skillRunRequest.promptOverride)) }

    fun startedPhases(): List<String> = progress.startedPhases

    fun prompts(phase: String): List<String> =
      launcher.requests.mapNotNull { it.skillRunRequest.promptOverride }.filter { phaseIdFromPrompt(it) == phase }

    fun storedOutcome(): FeatureTaskQaOutcome? = database.read { it.featureTaskQaOutcomes.find(PARENT) }
  }

  private fun Map<String, Any?>.withFindings(ids: List<String>): Map<String, Any?> {
    if (ids.isEmpty()) return this
    val template = (this["findings"] as List<*>).single() as Map<*, *>
    return this +
      (
        "findings" to
          ids.map { id ->
            template.entries.associate { (key, value) -> key.toString() to value } +
              mapOf("id" to id, "observed" to "The $id screen stays blank.")
          }
      )
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
