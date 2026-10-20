package skillbill.engine.featuretask.phaserun.qa

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.qa.receipt.QA_TEST_HEAD
import skillbill.engine.featuretask.qa.receipt.QA_TEST_TREE
import skillbill.engine.featuretask.qa.receipt.QaVerdict
import skillbill.engine.featuretask.qa.receipt.qaTestExpected
import skillbill.engine.featuretask.qa.receipt.qaTestFlow
import skillbill.engine.featuretask.qa.receipt.qaTestJson
import skillbill.engine.featuretask.qa.receipt.qaTestReceipt
import skillbill.engine.featuretask.runner.kotlinPackWithValidationGate
import skillbill.ports.idestatus.model.StandaloneQaOutcome
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
  fun `a findings receipt completes, reports the findings, and releases the invocation's leases`() {
    val result =
      assertIs<PhaseRunResult.Completed>(runQa { qaTestJson(qaTestReceipt(QaVerdict.FINDINGS_OPEN, it.expected)) })

    assertEquals(StandaloneQaOutcome.FINDINGS, result.qaOutcome)
    assertContains(result.targetReport, "Findings: F-1")
    assertEquals(listOf(INVOCATION), leases.releasedInvocations)
    val qa = requireNotNull(launched.single())
    assertEquals(qaTestExpected(INVOCATION), qa.expected)
    assertEquals(setOf(QaPlatform.ANDROID), assertIs<QaExecutionTarget.Supported>(qa.target).platforms)
  }

  @Test
  fun `a malformed receipt completes as incomplete with a warning and releases the invocation`() {
    val result = assertIs<PhaseRunResult.Completed>(runQa { "{\"verdict\": \"passed\"}" })

    assertEquals(StandaloneQaOutcome.INCOMPLETE, result.qaOutcome)
    assertTrue(
      result.targetReport.first().startsWith("Warning: QA recorded incomplete"),
      result.targetReport.toString(),
    )
    assertContains(result.targetReport.first(), "schema validation")
    assertEquals(listOf(INVOCATION), leases.releasedInvocations)
  }

  @Test
  fun `a passed receipt with an untested flow completes as incomplete and says why`() {
    val result =
      assertIs<PhaseRunResult.Completed>(
        runQa {
          val untested =
            mapOf(
              "id" to "logout",
              "name" to "Log out",
              "status" to "untested",
              "hardware_only" to false,
              "untested_reason" to "The build has no logout entry.",
            )
          qaTestJson(qaTestReceipt(QaVerdict.PASSED, it.expected) + ("flows" to listOf(qaTestFlow(), untested)))
        },
      )

    assertEquals(StandaloneQaOutcome.INCOMPLETE, result.qaOutcome)
    assertContains(
      result.targetReport,
      "Warning: The QA receipt claimed passed but left 'Log out' untested; recorded incomplete.",
    )
    assertContains(result.targetReport, "Verdict: incomplete")
    assertContains(result.targetReport, "Untested flows: Log out (The build has no logout entry.)")
  }

  @Test
  fun `a production-only skip completes with a warning`() {
    val result =
      assertIs<PhaseRunResult.Completed>(
        runQa {
          qaTestJson(
            qaTestReceipt(QaVerdict.SKIPPED, it.expected) +
              mapOf("skip_reason" to "production_only", "reason_detail" to "Only the prod flavor is installed."),
          )
        },
      )

    assertEquals(StandaloneQaOutcome.SKIPPED, result.qaOutcome)
    assertContains(
      result.targetReport,
      "Warning: QA refused to run because the app offers only a production environment: " +
        "Only the prod flavor is installed.",
    )
    assertContains(result.targetReport, "Skip reason: production_only - Only the prod flavor is installed.")
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
    assertContains(result.targetReport.first(), "agent timed out")
    assertTrue(
      result.targetReport.any { it.startsWith("Incomplete reason: interrupted") },
      result.targetReport.toString(),
    )
  }

  @Test
  fun `a tracked file edited during QA is accepted and a valid receipt settles the run`() {
    git.worktreeStatusSequence += listOf("", " M src/Foo.kt")

    assertIs<PhaseRunResult.Completed>(runQa { qaTestJson(qaTestReceipt(QaVerdict.PASSED, it.expected)) })
  }

  @Test
  fun `no declared capability settles an unsupported_target skip without launching the agent`() {
    val result =
      assertIs<PhaseRunResult.Completed>(
        runQa(manifests = listOf(androidPack.copy(qaCapability = null))) { error("QA must not launch.") },
      )

    assertEquals(StandaloneQaOutcome.SKIPPED, result.qaOutcome)
    assertContains(
      result.targetReport,
      "Skip reason: unsupported_target - no installed pack declares qa_capability for kotlin",
    )
    assertEquals(emptyList(), launched)
    assertNull(workspaces.receipt)
  }

  private fun runQa(
    manifests: List<PlatformManifest> = listOf(androidPack),
    agent: (QaExecutionContext) -> String,
  ): PhaseRunResult =
    qaStandalonePhaseRun(git, manifests, workspaces, leases).run(
      PhaseRunRequest(SkeletonDefinition.QA.id, Path.of("/tmp/qa-repo"), "claude"),
      INVOCATION,
    ) { request ->
      launched += request.qaExecution
      val qa = requireNotNull(request.qaExecution)
      workspaces.receipt = agent(qa)
      PhaseRunResult.Completed(INVOCATION, listOf(SkeletonDefinition.QA.id), null, value = "done")
    }

  private companion object {
    const val INVOCATION = "phr-qa-test"
    const val QA_GUIDE = "qa/android-qa.md"
  }
}
