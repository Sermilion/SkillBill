package skillbill.engine.featuretask.runner

import org.junit.jupiter.api.io.TempDir
import skillbill.contracts.qa.QaReceiptPayloadKeys
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.execution.QaExpectedIdentity
import skillbill.engine.featuretask.phaserun.qa.InMemoryQaWorkspaces
import skillbill.engine.featuretask.phaserun.qa.RecordingQaLeases
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.qa.execution.testQaExecutionLifecycle
import skillbill.engine.featuretask.qa.receipt.QA_TEST_HEAD
import skillbill.engine.featuretask.qa.receipt.QA_TEST_QA_ROOT
import skillbill.engine.featuretask.qa.receipt.QA_TEST_TREE
import skillbill.engine.featuretask.qa.receipt.QaFallbackKind
import skillbill.engine.featuretask.qa.receipt.QaReceiptSettlement
import skillbill.engine.featuretask.qa.receipt.QaReceiptValidator
import skillbill.engine.featuretask.qa.receipt.QaVerdict
import skillbill.engine.featuretask.qa.receipt.qaTestEvidencePrefix
import skillbill.engine.featuretask.qa.receipt.qaTestJson
import skillbill.engine.featuretask.qa.receipt.qaTestReceipt
import skillbill.infrastructure.contracts.qa.QaReceiptSchemaValidator
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.scaffold.model.QaCapabilityDeclaration
import skillbill.scaffold.model.QaPlatform
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
  fun `full QA records each fallback and policy skip without exposing receipt reasons`() {
    val fallbacks =
      fixture(
        QaVerdict.PASSED,
        receiptForVisit = { _, expected ->
          qaTestReceipt(QaVerdict.PASSED, expected) +
            mapOf(
              QaReceiptPayloadKeys.FALLBACKS to
                QaFallbackKind.entries.map { kind ->
                  mapOf(
                    QaReceiptPayloadKeys.KIND to kind.wireValue,
                    QaReceiptPayloadKeys.REASON to "private-device-serial",
                  )
                },
            )
        },
      )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(fallbacks.harness.runner.run(fallbacks.harness.request))
    val messages = fallbacks.diagnostics.messages
    assertEquals(3, messages.size)
    QaFallbackKind.entries.forEach { kind ->
      assertEquals(1, messages.count { it.contains("value_used=${kind.wireValue}") })
    }
    assertTrue(messages.none { it.contains("private-device-serial") })

    val disabled = fixture(QaVerdict.PASSED, FeatureTaskRuntimeQaSelection.OFF)
    assertIs<FeatureTaskRuntimeRunReport.Completed>(disabled.harness.runner.run(disabled.harness.request))
    assertEquals(1, disabled.diagnostics.messages.size)
    assertContains(disabled.diagnostics.messages.single(), "value_used=disabled_policy")
    assertTrue(
      disabled.launcher.requests.none {
        phaseIdFromPrompt(it.skillRunRequest.promptOverride.orEmpty()) == "qa"
      },
    )
  }

  @Test
  fun `full runs prepare QA before launch and advance on valid optional receipts`() {
    for (verdict in listOf(QaVerdict.PASSED, QaVerdict.SKIPPED, QaVerdict.INCOMPLETE)) {
      val fixture = fixture(verdict)
      val report = fixture.harness.runner.run(fixture.harness.request)

      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
      val qaLaunch =
        fixture.launcher.requests.map { it.skillRunRequest }
          .single { phaseIdFromPrompt(it.promptOverride.orEmpty()) == "qa" }
      val prompt = assertNotNull(qaLaunch.promptOverride)
      assertEquals(listOf(Path.of(QA_TEST_QA_ROOT, "qa-test-invocation")), qaLaunch.additionalWritableDirectories)
      assertContains(prompt, "`policy` set to `auto`")
      assertContains(prompt, "qa/android-qa.md")
      assertContains(prompt, QA_TEST_TREE)
      val qa = assertNotNull(fixture.harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("qa"))
      assertContains(assertNotNull(qa.outputArtifact), "\"verdict\":\"${verdict.wireValue}\"")
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
      if (verdict == QaVerdict.INCOMPLETE) assertEquals("device_lost", stored.reason)
      assertEquals(
        when (verdict) {
          QaVerdict.SKIPPED -> emptyList()
          else -> listOf("AC-001")
        },
        stored.report?.criteria?.map { it.id }.orEmpty(),
      )
    }
  }

  @Test
  fun `disabled QA records a receipt without discovering capability or launching QA`() {
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
  }

  @Test
  fun `product findings reach qa_fix after the QA invocation releases its resources`() {
    val fixture = fixture(QaVerdict.FINDINGS_OPEN)

    assertFailsWith<CancellationException> { fixture.harness.runner.run(fixture.harness.request) }

    assertTrue(
      fixture.launcher.requests.any { phaseIdFromPrompt(it.skillRunRequest.promptOverride.orEmpty()) == "qa_fix" },
    )
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
        receiptForVisit = { _, expected ->
          qaTestReceipt(verdicts[qaVisits++], expected)
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
      assertFailsWith<CancellationException> { fixture.harness.runner.run(fixture.harness.request) }
      val state = assertNotNull(fixture.harness.recorder.loadQaRepairState(WORKFLOW_ID))
      assertEquals(listOf("F-1"), state.openFindings.map { it.id })
      assertEquals(it + 1, fixes)
      assertEquals((1..it + 1).toList(), state.attempts.map { attempt -> attempt.attemptNumber })
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
    val auditPrompts =
      fixture.launcher.requests.mapNotNull { it.skillRunRequest.promptOverride }
        .filter { phaseIdFromPrompt(it) == "audit" }
    assertFalse("Post-QA re-audit scope" in auditPrompts.first())
    assertTrue(auditPrompts.drop(1).isNotEmpty())
    auditPrompts.drop(1).forEach { assertContains(it, "Post-QA re-audit scope: AC-001.") }
  }

  @Test
  fun `findings left after the third qa_fix attempt continue to history and are reported`() {
    var qaVisits = 0
    var fixes = 0
    lateinit var fixture: Fixture
    fixture =
      fixture(
        QaVerdict.FINDINGS_OPEN,
        receiptForVisit = { _, expected ->
          qaVisits++
          qaTestReceipt(QaVerdict.FINDINGS_OPEN, expected)
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
  fun `a malformed receipt is recorded incomplete with a warning and the run continues`() {
    val fixture = fixture(QaVerdict.PASSED, receiptForVisit = { _, _ -> mapOf("verdict" to "passed") })

    val report = fixture.harness.runner.run(fixture.harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val qa = assertNotNull(fixture.harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("qa"))
    assertContains(assertNotNull(qa.outputArtifact), "\"verdict\":\"incomplete\"")
    assertContains(assertNotNull(qa.outputArtifact), "QA recorded incomplete because its receipt was unusable")
    assertEquals(StandaloneQaOutcome.INCOMPLETE, assertNotNull(fixture.storedQaOutcome()).outcome)
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
    assertContains(assertNotNull(qa.outputArtifact), "\"incomplete_reason\":\"interrupted\"")
    val stored = assertNotNull(fixture.storedQaOutcome())
    assertEquals(StandaloneQaOutcome.INCOMPLETE, stored.outcome)
    assertTrue(assertNotNull(stored.reason).startsWith("interrupted"))
  }

  @Test
  fun `a tree-changing validation repair after passing QA re-enters audit and QA before history`() {
    var qaVisits = 0
    var edited = false
    lateinit var fixture: Fixture
    fixture =
      fixture(
        QaVerdict.PASSED,
        receiptForVisit = { _, expected ->
          qaVisits++
          qaTestReceipt(QaVerdict.PASSED, expected)
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
        receiptForVisit = { _, expected ->
          qaVisits++
          qaTestReceipt(QaVerdict.PASSED, expected)
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

  private fun fixture(
    verdict: QaVerdict,
    selection: FeatureTaskRuntimeQaSelection = FeatureTaskRuntimeQaSelection.AUTO,
    receiptForVisit: ((Int, QaExpectedIdentity) -> Map<String, Any?>)? = null,
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
        qaCapability = QaCapabilityDeclaration(setOf(QaPlatform.ANDROID), "qa/android-qa.md"),
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
            if (receiptForVisit == null) {
              expected
            } else {
              expected.copy(
                treeIdentity = checkNotNull(git.repositoryFingerprintValue),
                evidencePrefix = qaTestEvidencePrefix("qa-test-invocation-$visit"),
              )
            }
          val receipt = receiptForVisit?.invoke(visit, identity) ?: qaTestReceipt(verdict, identity)
          workspaces.receipt = qaTestJson(receipt).replace("AC-1", "AC-001").replace("\"standalone\"", "\"auto\"")
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
          qaReceipts =
            QaReceiptSettlement(QaReceiptValidator(QaReceiptSchemaValidator()), workspaces, diagnostics),
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
