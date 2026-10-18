package skillbill.engine.featuretask.phaserun.qa

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.qa.receipt.QA_TEST_HEAD
import skillbill.engine.featuretask.qa.receipt.QA_TEST_TREE
import skillbill.engine.featuretask.qa.receipt.QaVerdict
import skillbill.engine.featuretask.qa.receipt.qaTestExpected
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
    assertEquals(setOf(QaPlatform.ANDROID), qa.platforms)
  }

  @Test
  fun `a malformed receipt blocks the run`() {
    val result = assertIs<PhaseRunResult.Blocked>(runQa { "{\"verdict\": \"passed\"}" })

    assertContains(result.reason, "schema validation")
    assertEquals(listOf(INVOCATION), leases.releasedInvocations)
  }

  @Test
  fun `an agent that edits a tracked file fails the run even with a valid receipt`() {
    git.worktreeStatusSequence += listOf("", " M src/Foo.kt")

    val result = assertIs<PhaseRunResult.Blocked>(runQa { qaTestJson(qaTestReceipt(QaVerdict.PASSED, it.expected)) })

    assertContains(result.reason, "changed the repository")
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
