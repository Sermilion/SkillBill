package skillbill.engine.featuretask.phaserun.qa

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.qa.report.QA_TEST_HEAD
import skillbill.engine.featuretask.qa.report.QA_TEST_TREE
import skillbill.engine.featuretask.qa.report.QaVerdict
import skillbill.engine.featuretask.qa.report.qaTestExpected
import skillbill.engine.featuretask.qa.report.qaTestReport
import skillbill.engine.featuretask.runner.kotlinPackWithValidationGate
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.qa.model.QaLeaseReleaseOutcome
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.QaCapabilityDeclaration
import skillbill.scaffold.model.QaPlatform
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    kotlinPackWithValidationGate().copy(qaCapability = QaCapabilityDeclaration(setOf(QaPlatform.ANDROID), QA_GUIDE))
  private val launched = mutableListOf<QaExecutionContext?>()

  @Test
  fun `a findings report completes with its text verbatim and releases the invocation's leases`() {
    val text = qaTestReport(QaVerdict.FINDINGS_OPEN, qaTestExpected(INVOCATION))

    val result = assertIs<PhaseRunResult.Completed>(runQa { text })

    assertEquals(StandaloneQaOutcome.FINDINGS, result.qaOutcome)
    assertEquals(text, result.qaReportText)
    assertEquals("QA verdict: findings_open", result.value)
    assertEquals(listOf("Verdict: findings_open", text), result.targetReport)
    assertEquals(listOf(INVOCATION), leases.releasedInvocations)
    val qa = requireNotNull(launched.single())
    assertEquals(qaTestExpected(INVOCATION), qa.expected)
    assertEquals(setOf(QaPlatform.ANDROID), assertIs<QaExecutionTarget.Supported>(qa.target).platforms)
  }

  @Test
  fun `a report that names no verdict completes as incomplete and keeps the agent's text`() {
    val text = "I could not decide whether the login flow works."

    val result = assertIs<PhaseRunResult.Completed>(runQa { text })

    assertEquals(StandaloneQaOutcome.INCOMPLETE, result.qaOutcome)
    assertEquals(text, result.qaReportText)
    assertEquals(listOf("Verdict: incomplete", text), result.targetReport)
  }

  @Test
  fun `no report completes as incomplete with a warning and releases the invocation`() {
    val result = assertIs<PhaseRunResult.Completed>(runQa { null })

    assertEquals(StandaloneQaOutcome.INCOMPLETE, result.qaOutcome)
    assertNull(result.qaReportText)
    assertEquals(
      listOf(
        "Warning: QA agent wrote no report.md",
        "Verdict: incomplete: QA agent wrote no report.md",
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
    val text = qaTestReport(QaVerdict.SKIPPED, qaTestExpected(INVOCATION))

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
    assertContains(cleanup, "qa lease release --invocation")
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
    assertTrue(
      result.targetReport.any { it.contains("no installed pack declares qa_capability for kotlin") },
      result.targetReport.toString(),
    )
    assertEquals(emptyList(), launched)
    assertNull(workspaces.report)
  }

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
