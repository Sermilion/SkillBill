package skillbill.engine.featuretask.phaserun.qa

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.qa.checklist.QaChecklistRecorder
import skillbill.engine.featuretask.qa.checklist.QaStepReport
import skillbill.engine.featuretask.qa.report.QA_TEST_HEAD
import skillbill.engine.featuretask.qa.report.QA_TEST_TREE
import skillbill.engine.featuretask.qa.report.QaVerdict
import skillbill.engine.featuretask.qa.report.qaTestExpected
import skillbill.engine.featuretask.qa.report.qaTestReport
import skillbill.engine.featuretask.runner.kotlinPackWithValidationGate
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandaloneQaChecklist
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaStepStatus
import skillbill.ports.idestatus.model.StandaloneQaTargetState
import skillbill.ports.qa.model.QaLeaseReleaseOutcome
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.QaCapabilityDeclaration
import skillbill.scaffold.model.QaTargetKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeProviderLimitSignal
import skillbill.workflow.taskruntime.model.qa.QaRunRetainedResource
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
import java.nio.file.Files.createTempDirectory
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QaStandalonePhaseRunTest {
  private val git =
    RecordingWorkflowGitOperations().also {
      it.runtimePhaseHeadCommitSequence += listOf(QA_TEST_HEAD, QA_TEST_HEAD)
      it.repositoryFingerprintValue = QA_TEST_TREE
      it.trackedPathsValue = listOf("src/Foo.kt")
    }
  private val workspaces = InMemoryQaWorkspaces()
  private val leases = RecordingQaLeases()
  private val androidPack =
    kotlinPackWithValidationGate()
      .copy(qaCapability = QaCapabilityDeclaration(setOf(QaTargetKind("android")), QA_GUIDE))
  private val launched = mutableListOf<QaExecutionContext?>()

  @Test
  fun `a findings report completes with its text verbatim and releases the invocation's leases`() {
    val text = androidReport(QaVerdict.FINDINGS_OPEN)

    val result = assertIs<PhaseRunResult.Completed>(runQa { text })

    assertEquals(StandaloneQaOutcome.FINDINGS, result.qaOutcome)
    assertEquals(text, result.qaReportText)
    assertEquals("QA verdict: findings_open; coverage: android findings_open", result.value)
    assertEquals(listOf("Verdict: findings_open", text), result.targetReport)
    assertEquals(listOf(INVOCATION), leases.releasedInvocations)
    val qa = requireNotNull(launched.single())
    assertEquals(qaTestExpected(INVOCATION), qa.expected)
    assertEquals(
      listOf(setOf(QaTargetKind("android"))),
      assertIs<QaExecutionTarget.Supported>(qa.target).targets.map { it.kinds },
    )
  }

  @Test
  fun `a report that names no verdict completes as incomplete and keeps the agent's text`() {
    val text = "I could not decide whether the login flow works."

    val result = assertIs<PhaseRunResult.Completed>(runQa { text })

    assertEquals(StandaloneQaOutcome.INCOMPLETE, result.qaOutcome)
    assertEquals(text, result.qaReportText)
    assertEquals(
      listOf("Warning: QA report has no coverage for target android", "Verdict: incomplete", text),
      result.targetReport,
    )
  }

  @Test
  fun `no report completes as incomplete with a warning and releases the invocation`() {
    val result = assertIs<PhaseRunResult.Completed>(runQa { null })

    assertEquals(StandaloneQaOutcome.INCOMPLETE, result.qaOutcome)
    assertNull(result.qaReportText)
    assertEquals(
      listOf(
        "Warning: QA agent wrote no report.md",
        "Verdict: incomplete",
      ),
      result.targetReport,
    )
    assertEquals(listOf(INVOCATION), leases.releasedInvocations)
  }

  @Test
  fun `a passed report that lists a finding heading stays passed and warns`() {
    val text = qaTestReport(QaVerdict.PASSED, qaTestExpected(INVOCATION), listOf("F-1"))

    val result = assertIs<PhaseRunResult.Completed>(runQa { text })

    assertEquals(StandaloneQaOutcome.PASSED, result.qaOutcome)
    assertEquals(
      "Warning: QA report states passed but lists findings F-1; no repair opened",
      result.targetReport.first(),
    )
    assertEquals(text, result.qaReportText)
  }

  @Test
  fun `an agent reported skip completes as skipped`() {
    val text = androidReport(QaVerdict.SKIPPED)

    val result = assertIs<PhaseRunResult.Completed>(runQa { text })

    assertEquals(StandaloneQaOutcome.SKIPPED, result.qaOutcome)
    assertEquals(listOf("Verdict: skipped", text), result.targetReport)
  }

  @Test
  fun `a retained device lease adds a cleanup note to the report`() {
    val retained = RecordingQaLeases(listOf(QaLeaseReleaseOutcome.Failed("device-1", "adb offline")))
    val text = qaTestReport(QaVerdict.PASSED, qaTestExpected(INVOCATION))

    val result = assertIs<PhaseRunResult.Completed>(runQa(leaseSet = retained) { text })

    val cleanup = result.targetReport.last()
    assertContains(cleanup, "Cleanup: retained device_lease device-1.")
    assertContains(cleanup, "sona qa lease release --invocation")
    assertFalse(cleanup.contains("skill-bill qa"), cleanup)
  }

  @Test
  fun `a stopped QA agent step completes as incomplete with a warning`() {
    val result =
      assertIs<PhaseRunResult.Completed>(
        qaStandalonePhaseRun(git, listOf(androidPack), workspaces, leases).run(
          PhaseRunRequest(SkeletonDefinition.QA.id, Path.of("/tmp/qa-repo"), "claude"),
          INVOCATION,
        ) { PhaseRunResult.Blocked(INVOCATION, emptyList(), null, "qa", "agent timed out") },
      )

    assertEquals(StandaloneQaOutcome.INCOMPLETE, result.qaOutcome)
    assertNull(result.qaReportText)
    assertTrue(result.targetReport.first().startsWith("Warning:"), result.targetReport.toString())
    assertContains(result.targetReport.first(), "agent timed out")
    val details = assertNotNull(result.qaDetails)
    assertNotNull(details.reportPath)
    assertNotNull(details.invocationDirectory)
    assertEquals(emptyList(), details.assertions)
  }

  @Test
  fun `provider limit blocked QA passes through without a quality verdict`() {
    val reason =
      FeatureTaskRuntimeProviderLimitPause.reason(
        phaseLabel = "Feature-task-runtime phase 'qa'",
        agentId = "claude",
        resumePhaseId = "qa",
        signal = FeatureTaskRuntimeProviderLimitSignal(evidence = "usage limit", resetHint = null),
      )
    val result =
      assertIs<PhaseRunResult.Blocked>(
        qaStandalonePhaseRun(git, listOf(androidPack), workspaces, leases).run(
          PhaseRunRequest(SkeletonDefinition.QA.id, Path.of("/tmp/qa-repo"), "claude"),
          INVOCATION,
        ) { PhaseRunResult.Blocked(INVOCATION, emptyList(), null, "qa", reason) },
      )

    assertEquals(reason, result.reason)
    assertEquals(listOf(INVOCATION), leases.releasedInvocations)
  }

  @Test
  fun `a tracked file edited during QA is accepted and the report settles the run`() {
    git.worktreeStatusSequence += listOf("", " M src/Foo.kt")
    val text = qaTestReport(QaVerdict.PASSED, qaTestExpected(INVOCATION))

    val result = assertIs<PhaseRunResult.Completed>(runQa { text })

    assertEquals(StandaloneQaOutcome.PASSED, result.qaOutcome)
  }

  @Test
  fun `no declared capability settles an unsupported_target skip without launching the agent`() {
    val result =
      assertIs<PhaseRunResult.Completed>(
        runQa(manifests = listOf(androidPack.copy(qaCapability = null))) { error("QA must not launch.") },
      )

    assertEquals(StandaloneQaOutcome.SKIPPED, result.qaOutcome)
    assertContains(result.targetReport, "Verdict: skipped: unsupported_target")
    assertEquals("unsupported_target", result.qaDetails?.skipReason)
    assertTrue(
      result.targetReport.any { it.contains("no installed pack declares qa_capability for kotlin") },
      result.targetReport.toString(),
    )
    assertEquals(emptyList(), launched)
    assertNull(workspaces.report)
  }

  @Test
  fun `a supported run seeds the checklist before launching the agent`() {
    val database = sqliteDatabaseSessionFactory(createTempDirectory("qa-seed"), environment = mapOf("TEST" to "1"))
    database.transaction {
      it.standalonePhaseStatuses.register(
        StandalonePhaseStatusRegistration(
          repositoryIdentity = "repo", branchCorrelation = "main", issueKey = null, workflowId = null,
          invocationId = INVOCATION, phaseId = SkeletonDefinition.QA.id, executionId = "execution",
          lifecycleState = "active", currentStep = "qa", startedAt = Instant.EPOCH, leaseOwner = "owner",
          leaseGeneration = 1, leaseExpiresAt = Instant.EPOCH.plusSeconds(3600),
        ),
      )
    }
    var seenAtLaunch: StandaloneQaChecklist? = null

    qaStandalonePhaseRun(git, listOf(androidPack), workspaces, leases, database).run(
      PhaseRunRequest(SkeletonDefinition.QA.id, Path.of("/tmp/qa-repo"), "claude", intake = "skill-457 extra words"),
      INVOCATION,
    ) {
      seenAtLaunch = database.read { unit -> unit.standalonePhaseStatuses.readByInvocation(INVOCATION) }?.qaChecklist
      PhaseRunResult.Completed(INVOCATION, listOf(SkeletonDefinition.QA.id), null, value = "done")
    }

    val seeded = requireNotNull(seenAtLaunch)
    assertEquals(QA_TEST_HEAD, seeded.testedCommit)
    assertEquals("SKILL-457", seeded.criteriaSource)
    assertEquals(listOf("android"), seeded.targets.map { it.name })
    assertEquals(listOf(StandaloneQaTargetState.PENDING), seeded.targets.map { it.state })
  }

  @Test
  fun `plan steps left pending or running are named in a warning while skipped steps count as settled`() {
    val database = sqliteDatabaseSessionFactory(createTempDirectory("qa-steps"), environment = mapOf("TEST" to "1"))
    database.transaction {
      it.standalonePhaseStatuses.register(
        StandalonePhaseStatusRegistration(
          repositoryIdentity = "repo", branchCorrelation = "main", issueKey = null, workflowId = null,
          invocationId = INVOCATION, phaseId = SkeletonDefinition.QA.id, executionId = "execution",
          lifecycleState = "active", currentStep = "qa", startedAt = Instant.EPOCH, leaseOwner = "owner",
          leaseGeneration = 1, leaseExpiresAt = Instant.EPOCH.plusSeconds(3600),
        ),
      )
    }
    val agentProgress = QaChecklistRecorder(database, Clock.systemUTC(), NoopRuntimeDiagnostics, workspaces)

    val result =
      qaStandalonePhaseRun(git, listOf(androidPack), workspaces, leases, database).run(
        PhaseRunRequest(SkeletonDefinition.QA.id, Path.of("/tmp/qa-repo"), "claude"),
        INVOCATION,
      ) {
        agentProgress.registerPlan(
          INVOCATION,
          """{"contract_version":"0.1","cases":[{"id":"S1","title":"Sync","steps":""" +
            """[{"text":"a"},{"text":"b"},{"text":"c"},{"text":"d"}]}]}""",
        )
        agentProgress.recordStep(QaStepReport(INVOCATION, "S1", 1, StandaloneQaStepStatus.PASSED))
        agentProgress.recordStep(QaStepReport(INVOCATION, "S1", 2, StandaloneQaStepStatus.SKIPPED, note = "no web"))
        agentProgress.recordStep(QaStepReport(INVOCATION, "S1", 3, StandaloneQaStepStatus.RUNNING))
        workspaces.report = androidReport(QaVerdict.PASSED)
        PhaseRunResult.Completed(INVOCATION, listOf(SkeletonDefinition.QA.id), null, value = "done")
      }

    val completed = assertIs<PhaseRunResult.Completed>(result)
    assertContains(
      completed.targetReport,
      "Warning: QA left 2 plan steps without a passed, failed or skipped status: S1.3, S1.4",
    )
  }

  @Test
  fun `a failing seed leaves the settled verdict and report unchanged`() {
    val text = androidReport(QaVerdict.FINDINGS_OPEN)

    val result = assertIs<PhaseRunResult.Completed>(runQa { text })

    assertEquals(StandaloneQaOutcome.FINDINGS, result.qaOutcome)
    assertEquals(text, result.qaReportText)
  }

  @Test
  fun `retained resources, finding fields and absolute paths reach the completed details`() {
    val leaseSet = RecordingQaLeases(listOf(QaLeaseReleaseOutcome.Failed("device-1", "adb offline")))

    val result =
      assertIs<PhaseRunResult.Completed>(runQa(leaseSet = leaseSet) { androidReport(QaVerdict.FINDINGS_OPEN) })

    val details = requireNotNull(result.qaDetails)
    val qa = requireNotNull(launched.single())
    assertEquals(listOf(QaRunRetainedResource("device_lease", "device-1")), details.retainedResources)
    assertEquals("the home screen stays blank.", details.findings.single().observed)
    assertEquals(qa.workspace.reportPath.toAbsolutePath().normalize().toString(), details.reportPath)
    assertEquals(qa.workspace.directory.toAbsolutePath().normalize().toString(), details.invocationDirectory)
  }

  private fun androidReport(verdict: QaVerdict): String =
    qaTestReport(verdict, qaTestExpected(INVOCATION)) + "\n## Coverage\n- android: ${verdict.wireValue}\n"

  private fun runQa(
    manifests: List<PlatformManifest> = listOf(androidPack),
    leaseSet: RecordingQaLeases = leases,
    agent: (QaExecutionContext) -> String?,
  ): PhaseRunResult =
    qaStandalonePhaseRun(git, manifests, workspaces, leaseSet).run(
      PhaseRunRequest(SkeletonDefinition.QA.id, Path.of("/tmp/qa-repo"), "claude"),
      INVOCATION,
    ) { request ->
      launched += request.qaExecution
      val qa = requireNotNull(request.qaExecution)
      workspaces.report = agent(qa)
      PhaseRunResult.Completed(INVOCATION, listOf(SkeletonDefinition.QA.id), null, value = "done")
    }

  private companion object {
    const val INVOCATION = "phr-qa-test"
    const val QA_GUIDE = "qa/android-qa.md"
  }
}
