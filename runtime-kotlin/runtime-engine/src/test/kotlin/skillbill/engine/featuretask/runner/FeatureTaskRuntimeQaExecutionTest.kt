package skillbill.engine.featuretask.runner

import org.junit.jupiter.api.io.TempDir
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.execution.QaExpectedIdentity
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.phaserun.qa.InMemoryQaWorkspaces
import skillbill.engine.featuretask.phaserun.qa.RecordingQaLeases
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.qa.execution.testQaExecutionLifecycle
import skillbill.engine.featuretask.qa.report.QA_TEST_HEAD
import skillbill.engine.featuretask.qa.report.QA_TEST_QA_ROOT
import skillbill.engine.featuretask.qa.report.QA_TEST_TREE
import skillbill.engine.featuretask.qa.report.QaReportSettlement
import skillbill.engine.featuretask.qa.report.QaVerdict
import skillbill.engine.featuretask.qa.report.qaTestEvidencePrefix
import skillbill.engine.featuretask.qa.report.qaTestReport
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.scaffold.model.QaCapabilityDeclaration
import skillbill.scaffold.model.QaTargetKind
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.qa.QaOpenFinding
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeQaExecutionTest {
  @TempDir
  lateinit var repo: Path

  private var qaOutputOverride: String? = null

  @Test
  fun `full runs prepare QA before launch and advance on a prose report with any verdict`() {
    for (verdict in listOf(QaVerdict.PASSED, QaVerdict.SKIPPED, QaVerdict.INCOMPLETE)) {
      val fixture = fixture(verdict)
      val report = fixture.harness.runner.run(fixture.harness.request)

      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
      val qaLaunch =
        fixture.launcher.requests.map { it.skillRunRequest }
          .single { phaseIdFromPrompt(it.promptOverride.orEmpty()) == "qa" }
      val prompt = assertNotNull(qaLaunch.promptOverride)
      assertEquals(listOf(Path.of(QA_TEST_QA_ROOT, "qa-test-invocation")), qaLaunch.additionalWritableDirectories)
      assertEquals(Path.of(QA_TEST_QA_ROOT, "qa-test-invocation", "checkout"), qaLaunch.repoRoot)
      assertContains(prompt, "qa/android-qa.md")
      assertContains(prompt, "Report path: `${qaTestEvidencePrefix("qa-test-invocation")}report.md`")
      val qa = assertNotNull(fixture.harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("qa"))
      assertContains(assertNotNull(qa.outputArtifact), "\"qa_verdict\":\"${verdict.wireValue}\"")
      assertEquals(listOf("qa-test-invocation"), fixture.leases.releasedInvocations)
      assertEquals(listOf("qa-test-invocation"), fixture.workspaces.closedInvocations)
      val stored = assertNotNull(fixture.storedQaOutcome())
      assertEquals(
        mapOf(
          QaVerdict.PASSED to StandaloneQaOutcome.PASSED,
          QaVerdict.SKIPPED to StandaloneQaOutcome.SKIPPED,
          QaVerdict.INCOMPLETE to StandaloneQaOutcome.INCOMPLETE,
        ).getValue(verdict),
        stored.outcome,
      )
      assertEquals(QA_TEST_TREE, stored.testedTreeIdentity)
      assertEquals(0, stored.qaFixAttempts)
      assertContains(assertNotNull(stored.reportText), "Verdict: ${verdict.wireValue}")
      assertTrue(stored.findings.isEmpty())
    }
  }

  @Test
  fun `disabled QA records a skipped outcome without discovering capability or launching QA`() {
    val fixture = fixture(QaVerdict.PASSED, FeatureTaskRuntimeQaSelection.OFF)

    val report = fixture.harness.runner.run(fixture.harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    assertTrue(
      fixture.launcher.requests.none { phaseIdFromPrompt(it.skillRunRequest.promptOverride.orEmpty()) == "qa" },
    )
    val record = assertNotNull(fixture.harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("qa"))
    assertContains(assertNotNull(record.outputArtifact), "disabled_policy")
    val stored = assertNotNull(fixture.storedQaOutcome())
    assertEquals(StandaloneQaOutcome.SKIPPED, stored.outcome)
    assertEquals("disabled_policy", stored.reason)
    assertEquals(0, fixture.capabilityReads())
    assertEquals(listOf("qa-test-invocation"), fixture.workspaces.closedInvocations)
    assertEquals(1, fixture.diagnostics.messages.size)
    assertContains(fixture.diagnostics.messages.single(), "value_used=disabled_policy")
  }

  @Test
  fun `product findings reach qa_fix after the QA invocation releases its resources`() {
    val fixture = fixture(QaVerdict.FINDINGS_OPEN)

    assertFailsWith<CancellationException> { fixture.harness.runner.run(fixture.harness.request) }

    val repair =
      fixture.launcher.requests.map { it.skillRunRequest }
        .first { phaseIdFromPrompt(it.promptOverride.orEmpty()) == "qa_fix" }
    assertEquals(fixture.harness.request.repoRoot, repair.repoRoot)
    assertEquals(listOf("qa-test-invocation"), fixture.leases.releasedInvocations)
    assertEquals(listOf("qa-test-invocation"), fixture.workspaces.closedInvocations)
  }

  @Test
  fun `findings survive repair claims output invalidation and resume until passing QA verifies the repair`() {
    val verdicts = listOf(QaVerdict.FINDINGS_OPEN, QaVerdict.SKIPPED, QaVerdict.INCOMPLETE, QaVerdict.PASSED)
    var qaVisits = 0
    var fixes = 0
    var pauseAudit = false
    lateinit var fixture: Fixture
    fixture =
      fixture(
        QaVerdict.FINDINGS_OPEN,
        reportForVisit = { _, expected ->
          qaTestReport(verdicts[qaVisits++], expected)
        },
        onPhase = { phase ->
          if (phase == "qa_fix") {
            fixes++
            fixture.git.repositoryFingerprintValue = (fixes + 3).toString().repeat(64)
            assertEquals(
              listOf("F-1"),
              fixture.harness.recorder.loadQaRepairState(WORKFLOW_ID)?.openFindings?.map { it.id },
            )
            pauseAudit = true
          }
          if (phase == "audit" && pauseAudit) throw CancellationException("restart after the completed repair")
        },
        identifiers = IdentifierGeneratorPort { "test-invocation-${qaVisits + 1}" },
      )
    repeat(3) {
      assertRepairRelaunch(fixture, it + 1)
      assertEquals(it + 1, fixes)
      pauseAudit = false
    }

    val report = fixture.harness.runner.run(fixture.harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    assertEquals(4, qaVisits)
    assertEquals(3, fixes)
    val stored = assertNotNull(fixture.storedQaOutcome())
    assertEquals(StandaloneQaOutcome.PASSED, stored.outcome)
    assertEquals(3, stored.qaFixAttempts)
    val settled = assertNotNull(fixture.harness.recorder.loadQaRepairState(WORKFLOW_ID))
    assertTrue(settled.openFindings.isEmpty())
    assertEquals(3, settled.consumedAttempts)
    val repairPrompts =
      fixture.launcher.requests.mapNotNull { it.skillRunRequest.promptOverride }
        .filter { phaseIdFromPrompt(it) == "qa_fix" }
    assertEquals(3, repairPrompts.size)
    repairPrompts.forEach { assertContains(it, "F-1") }
    val firstRepair = repairPrompts.first()
    assertContains(firstRepair, "### F-1 Home screen stays blank")
    assertContains(firstRepair, "Observed: the home screen stays blank.")
    assertContains(firstRepair, "Evidence: ${qaTestEvidencePrefix("qa-test-invocation-1")}home-F-1.png")
    val auditPrompts =
      fixture.launcher.requests.mapNotNull { it.skillRunRequest.promptOverride }
        .filter { phaseIdFromPrompt(it) == "audit" }
    assertFalse("Post-QA re-audit scope" in auditPrompts.first())
    assertTrue(auditPrompts.drop(1).isNotEmpty())
    auditPrompts.drop(1).forEach { assertContains(it, "Post-QA re-audit scope:") }
  }

  private fun assertRepairRelaunch(
    fixture: Fixture,
    expectedAttempt: Int,
  ) {
    assertFailsWith<CancellationException> { fixture.harness.runner.run(fixture.harness.request) }
    val state = assertNotNull(fixture.harness.recorder.loadQaRepairState(WORKFLOW_ID))
    assertEquals(listOf("F-1"), state.openFindings.map { it.id })
    assertEquals((1..expectedAttempt).toList(), state.attempts.map { attempt -> attempt.attemptNumber })
    assertEquals(listOf("F-1"), state.attempts.last().consumedFindingIds)
    fixture.harness.database.transaction { unit ->
      val record = assertNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, WORKFLOW_ID))
      val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS
      val records = (family.value(record.artifacts) as Map<*, *>).filterKeys { it != "qa" }
      unit.workflowStates.save(
        WorkflowFamily.TASK_RUNTIME,
        record.copy(artifacts = DurableWorkflowArtifacts.fromMap(record.artifacts + mapOf(family.entry(records)))),
      )
    }
  }

  @Test
  fun `findings left after the third qa_fix attempt continue to history and are reported`() {
    var qaVisits = 0
    var fixes = 0
    lateinit var fixture: Fixture
    fixture =
      fixture(
        QaVerdict.FINDINGS_OPEN,
        reportForVisit = { _, expected ->
          qaVisits++
          qaTestReport(QaVerdict.FINDINGS_OPEN, expected)
        },
        onPhase = { phase ->
          if (phase == "qa_fix") {
            fixes++
            fixture.git.repositoryFingerprintValue = (fixes + 3).toString().repeat(64)
          }
        },
        identifiers = IdentifierGeneratorPort { "test-invocation-${qaVisits + 1}" },
      )

    val report = fixture.harness.runner.run(fixture.harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    assertEquals(3, fixes)
    assertEquals(4, qaVisits)
    val launched = fixture.launcher.requests.map { phaseIdFromPrompt(it.skillRunRequest.promptOverride.orEmpty()) }
    assertEquals(1, launched.count { it == "write_history" })
    val stored = assertNotNull(fixture.storedQaOutcome())
    assertEquals(StandaloneQaOutcome.FINDINGS, stored.outcome)
    assertTrue(assertNotNull(stored.reason).startsWith("findings left open: F-1"))
    assertEquals(3, stored.qaFixAttempts)
    val qa = assertNotNull(fixture.harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("qa"))
    assertContains(assertNotNull(qa.outputArtifact), "remain after 3 qa_fix attempts")
  }

  @Test
  fun `a report with no verdict word is recorded incomplete with its text and the run continues`() {
    val freeForm = "I tapped through the login flow and the home screen looked fine to me."
    val fixture = fixture(QaVerdict.PASSED, reportForVisit = { _, _ -> freeForm })

    val report = fixture.harness.runner.run(fixture.harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val qa = assertNotNull(fixture.harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("qa"))
    assertContains(assertNotNull(qa.outputArtifact), "\"qa_verdict\":\"incomplete\"")
    val stored = assertNotNull(fixture.storedQaOutcome())
    assertEquals(StandaloneQaOutcome.INCOMPLETE, stored.outcome)
    assertEquals(freeForm, stored.reportText)
  }

  @Test
  fun `a QA agent that writes no report is recorded incomplete with a warning and the run continues`() {
    val fixture = fixture(QaVerdict.PASSED, reportForVisit = { _, _ -> null })

    val report = fixture.harness.runner.run(fixture.harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val qa = assertNotNull(fixture.harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("qa"))
    assertContains(assertNotNull(qa.outputArtifact), "\"qa_verdict\":\"incomplete\"")
    assertContains(assertNotNull(qa.outputArtifact), "QA agent wrote no report.md")
    val stored = assertNotNull(fixture.storedQaOutcome())
    assertEquals(StandaloneQaOutcome.INCOMPLETE, stored.outcome)
    assertContains(assertNotNull(stored.reason), "QA agent wrote no report.md")
  }

  @Test
  fun `a passed report that lists findings stays passed with a warning and opens no repair`() {
    val fixture =
      fixture(
        QaVerdict.PASSED,
        reportForVisit = { _, expected -> qaTestReport(QaVerdict.PASSED, expected, listOf("F-1")) },
      )

    val report = fixture.harness.runner.run(fixture.harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    assertTrue(
      fixture.launcher.requests.none { phaseIdFromPrompt(it.skillRunRequest.promptOverride.orEmpty()) == "qa_fix" },
    )
    val stored = assertNotNull(fixture.storedQaOutcome())
    assertEquals(StandaloneQaOutcome.PASSED, stored.outcome)
    assertContains(assertNotNull(stored.reason), "no repair opened")
    assertEquals(0, stored.qaFixAttempts)
  }

  @Test
  fun `a QA agent step that stops is recorded incomplete and the run continues`() {
    val stopped =
      """
      {
        "contract_version": "0.7",
        "phase_id": "qa",
        "status": "blocked",
        "summary": "The emulator never booted.",
        "produced_outputs": {"blocking_reasons": ["The emulator never booted."]}
      }
      """.trimIndent()
    qaOutputOverride = stopped
    val fixture = fixture(QaVerdict.PASSED)

    val report = fixture.harness.runner.run(fixture.harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val qa = assertNotNull(fixture.harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("qa"))
    assertContains(assertNotNull(qa.outputArtifact), "\"qa_verdict\":\"incomplete\"")
    val stored = assertNotNull(fixture.storedQaOutcome())
    assertEquals(StandaloneQaOutcome.INCOMPLETE, stored.outcome)
    assertTrue(
      assertNotNull(stored.reason).startsWith("QA recorded incomplete because the QA agent step stopped"),
    )
  }

  @Test
  fun `a tree-changing validation repair after passing QA re-enters audit and QA before history`() {
    var qaVisits = 0
    var edited = false
    lateinit var fixture: Fixture
    fixture =
      fixture(
        QaVerdict.PASSED,
        reportForVisit = { _, expected ->
          qaVisits++
          qaTestReport(QaVerdict.PASSED, expected)
        },
        onPhase = { phase ->
          if (phase == "qa") assertEquals(StandaloneQaOutcome.ATTEMPTED, fixture.storedQaOutcome()?.outcome)
          if (phase == "validate" && !edited) {
            edited = true
            fixture.git.repositoryFingerprintValue = "7".repeat(64)
          }
        },
        identifiers = IdentifierGeneratorPort { "test-invocation-${qaVisits + 1}" },
      )

    val report = fixture.harness.runner.run(fixture.harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    assertEquals(2, qaVisits)
    val launched = fixture.launcher.requests.map { phaseIdFromPrompt(it.skillRunRequest.promptOverride.orEmpty()) }
    assertEquals(2, launched.count { it == "audit" })
    assertTrue(launched.lastIndexOf("audit") > launched.indexOf("validate"))
    assertEquals(1, launched.count { it == "write_history" })
    val qa = assertNotNull(fixture.harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("qa"))
    assertContains(assertNotNull(qa.outputArtifact), "7".repeat(64))
  }

  @Test
  fun `a resumed revision-3 plan whose tree keeps changing after QA reaches commit with a stale-QA warning`() {
    var qaVisits = 0
    var interrupted = false
    lateinit var fixture: Fixture
    fixture =
      fixture(
        QaVerdict.PASSED,
        reportForVisit = { _, expected ->
          qaVisits++
          qaTestReport(QaVerdict.PASSED, expected)
        },
        onPhase = { phase ->
          if (phase == "validate") fixture.git.repositoryFingerprintValue = "%064x".format(qaVisits + 10)
          if (phase == "audit" && qaVisits == 2 && !interrupted) {
            interrupted = true
            throw CancellationException("restart inside the second QA invalidation")
          }
        },
        identifiers = IdentifierGeneratorPort { "test-invocation-${qaVisits + 1}" },
      )

    assertFailsWith<CancellationException> { fixture.harness.runner.run(fixture.harness.request) }
    val report = fixture.harness.runner.run(fixture.harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val launched = fixture.launcher.requests.map { phaseIdFromPrompt(it.skillRunRequest.promptOverride.orEmpty()) }
    assertTrue(qaVisits > 3, launched.toString())
    assertEquals(1, launched.count { it == "write_history" })
    val records = assertNotNull(fixture.harness.recorder.loadPhaseRecords(WORKFLOW_ID))
    assertEquals(WorkflowStepStatus.COMPLETED, records["write_history"]?.status)
    assertEquals(WorkflowStepStatus.COMPLETED, records["commit_push"]?.status)
    assertContains(assertNotNull(fixture.storedQaOutcome()?.reason), "QA results are stale")
  }

  @Test
  fun `a worktree change made during subtask QA is reported in qa_warnings and the outcome`() {
    lateinit var fixture: Fixture
    fixture =
      fixture(
        QaVerdict.PASSED,
        onPhase = { phase ->
          if (phase == "qa") fixture.git.worktreeStatusValue = " M src/Foo.kt\n?? qa-scratch.txt"
        },
      )

    val report = fixture.harness.runner.run(fixture.harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val qa = assertNotNull(fixture.harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("qa"))
    val warnings =
      Regex("\"qa_warnings\":\\[[^\\]]*\\]").find(assertNotNull(qa.outputArtifact))?.value.orEmpty()
    assertContains(warnings, "the worktree status differs from before QA")
    assertContains(assertNotNull(fixture.storedQaOutcome()?.reason), "qa-scratch.txt")
  }

  @Test
  fun `a stored legacy qa_receipt envelope still loads its repair state and settles its outcome`() {
    val fixture = fixture(QaVerdict.PASSED)
    fixture.harness.recorder.openTestWorkflow(WORKFLOW_ID, "legacy-qa-session")

    fixture.harness.recorder.recordPhaseState(legacyQaPhaseState(withRepairState = true))

    val state = assertNotNull(fixture.harness.recorder.loadQaRepairState(WORKFLOW_ID))
    assertEquals(listOf("F-9"), state.openFindings.map { it.id })
    val stored = assertNotNull(fixture.storedQaOutcome())
    assertEquals(StandaloneQaOutcome.FINDINGS, stored.outcome)
    assertEquals(listOf("F-9"), stored.findings.map { it.id })
  }

  @Test
  fun `a stored legacy qa_receipt envelope without repair state is refused rather than read as no QA`() {
    val fixture = fixture(QaVerdict.PASSED)
    fixture.harness.recorder.openTestWorkflow(WORKFLOW_ID, "legacy-qa-session")
    fixture.harness.recorder.recordPhaseState(legacyQaPhaseState(withRepairState = false))

    val failure = assertFails { fixture.harness.recorder.loadQaRepairState(WORKFLOW_ID) }

    assertContains(failure.message.orEmpty(), "no authoritative findings state")
  }

  private fun legacyQaPhaseState(withRepairState: Boolean): FeatureTaskRuntimePhaseStateRequest {
    val receipt =
      mapOf(
        "verdict" to "findings_open",
        "findings" to
          listOf(
            mapOf(
              "id" to "F-9",
              "observed" to "Home blank",
              "evidence_refs" to listOf(mapOf("path" to "/qa/old/home.png")),
            ),
          ),
      )
    val envelope =
      JsonCodec.mapToJsonString(
        mapOf(
          SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
          SharedPayloadKeys.PHASE_ID to "qa",
          SharedPayloadKeys.STATUS to "completed",
          SharedPayloadKeys.SUMMARY to "QA settled",
          SharedPayloadKeys.PRODUCED_OUTPUTS to
            mapOf("qa_receipt" to receipt, "qa_receipt_ref" to "/qa/old/receipt.json"),
        ),
      )
    val repairState =
      QaRepairState(
        subtaskId = 1,
        openFindings = listOf(QaOpenFinding("F-9", emptyList(), listOf("/qa/old/receipt.json"))),
      )
    return FeatureTaskRuntimePhaseStateRequest(
      workflowId = WORKFLOW_ID,
      phaseId = "qa",
      status = "completed",
      attemptCount = 1,
      resolvedAgentId = "claude",
      finished = true,
      outputArtifact = envelope.takeUnless { withRepairState },
      normalizedOutput =
        NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(envelope, "test")
          .copy(qaRepairState = repairState)
          .takeIf { withRepairState },
    )
  }

  private fun fixture(
    verdict: QaVerdict,
    selection: FeatureTaskRuntimeQaSelection = FeatureTaskRuntimeQaSelection.AUTO,
    reportForVisit: ((Int, QaExpectedIdentity) -> String?)? = null,
    onPhase: (String) -> Unit = { phase ->
      if (phase == "qa_fix") throw CancellationException("stop after proving QA repair routing")
    },
    identifiers: IdentifierGeneratorPort = IdentifierGeneratorPort { "test-invocation" },
  ): Fixture {
    val branch = committedRepoBranchSetup()
    val git = branch.gitOperations
    git.currentBranchValue = "feat/qa-execution"
    git.runtimePhaseHeadCommitSequence += List(100) { QA_TEST_HEAD }
    git.repositoryFingerprintValue = QA_TEST_TREE
    git.trackedPathsValue = listOf("src/Foo.kt")
    val workspaces = InMemoryQaWorkspaces()
    val diagnostics = RecordingQaDiagnostics()
    val leases = RecordingQaLeases()
    val expected = QaExpectedIdentity(QA_TEST_HEAD, QA_TEST_TREE, qaTestEvidencePrefix("qa-test-invocation"))
    val pack =
      kotlinPackWithValidationGate().copy(
        qaCapability = QaCapabilityDeclaration(setOf(QaTargetKind("android")), "qa/android-qa.md"),
      )
    var capabilityReads = 0
    val execution =
      testQaExecutionLifecycle(
        git,
        workspaces,
        leases,
        QaCapabilityResolver(git) {
          capabilityReads++
          listOf(pack)
        },
        identifiers,
      )
    var visit = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val phase = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        onPhase(phase)
        if (phase == "qa") {
          visit++
          val identity =
            if (reportForVisit == null) {
              expected
            } else {
              expected.copy(
                treeIdentity = checkNotNull(git.repositoryFingerprintValue),
                evidencePrefix = qaTestEvidencePrefix("qa-test-invocation-$visit"),
              )
            }
          workspaces.report =
            if (reportForVisit == null) qaTestReport(verdict, identity) else reportForVisit(visit, identity)
        }
        facts(launchOutput(phase, request))
      }
    val harness =
      telemetryRunnerHarness(
        RuntimeHarnessConfig(
          definition = SkeletonDefinition.GOAL_CHILD_REVISION_3,
          repoRoot = repo.resolve(verdict.wireValue),
          branchSetup = branch,
          launcher = launcher,
          goalContinuation = harnessGoalContinuation(branch).copy(qaSelection = selection),
          qaExecution = execution,
          qaReports = QaReportSettlement(workspaces, diagnostics),
        ),
      )
    return Fixture(harness, launcher, workspaces, leases, git, diagnostics) { capabilityReads }
  }

  private fun launchOutput(
    phase: String,
    request: GoalRunnerSubtaskLaunchRequest,
  ): String =
    when {
      phase == "audit" -> auditSatisfiedOutput()
      phase == "qa" && qaOutputOverride != null -> checkNotNull(qaOutputOverride)
      else -> defaultPhaseOutput(request)
    }

  private data class Fixture(
    val harness: TelemetryRunnerHarness,
    val launcher: RuntimeRecordingLauncher,
    val workspaces: InMemoryQaWorkspaces,
    val leases: RecordingQaLeases,
    val git: RecordingWorkflowGitOperations,
    val diagnostics: RecordingQaDiagnostics,
    val capabilityReads: () -> Int,
  ) {
    fun storedQaOutcome(): FeatureTaskQaOutcome? = harness.database.read { it.featureTaskQaOutcomes.find(WORKFLOW_ID) }
  }

  private class RecordingQaDiagnostics : RuntimeDiagnostics {
    val messages = mutableListOf<String>()

    override fun info(message: String) {
      messages += message
    }

    override fun warning(
      message: String,
      error: Throwable?,
    ) = Unit

    override fun error(
      message: String,
      error: Throwable?,
    ) = Unit
  }
}
