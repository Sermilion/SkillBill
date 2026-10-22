package skillbill.engine.featuretask.qa.execution

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.execution.QaPolicy
import skillbill.engine.featuretask.phaserun.qa.InMemoryQaWorkspaces
import skillbill.engine.featuretask.phaserun.qa.RecordingQaLeases
import skillbill.engine.featuretask.qa.report.QA_TEST_HEAD
import skillbill.engine.featuretask.qa.report.QA_TEST_TREE
import skillbill.engine.featuretask.qa.report.QaReportSettlement
import skillbill.engine.featuretask.qa.report.QaVerdict
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class QaExecutionLifecycleTest {
  private val repo = Path.of("/tmp/qa-lifecycle")
  private val git =
    RecordingWorkflowGitOperations().also {
      it.runtimePhaseHeadCommitSequence += List(20) { QA_TEST_HEAD }
      it.repositoryFingerprintValue = QA_TEST_TREE
    }
  private val workspaces = InMemoryQaWorkspaces()
  private val leases = RecordingQaLeases()
  private val execution = testQaExecutionLifecycle(git, workspaces, leases)

  @Test
  fun `cancellation releases leases and closes the invocation without becoming a skip`() {
    val cancellation = CancellationException("operator cancelled QA")

    val thrown =
      assertFailsWith<CancellationException> {
        execution.withExecution(repo, QaPolicy.AUTO) { throw cancellation }
      }

    assertSame(cancellation, thrown)
    assertEquals(listOf("qa-test-invocation"), leases.releasedInvocations)
    assertEquals(listOf("qa-test-invocation"), workspaces.closedInvocations)
  }

  @Test
  fun `a missing report settles incomplete with a warning and still releases the invocation`() {
    val settlement = QaReportSettlement(workspaces, NoopRuntimeDiagnostics)

    val report = execution.withExecution(repo, QaPolicy.AUTO) { settlement.settle(it) }.value

    assertEquals(QaVerdict.INCOMPLETE, report.verdict)
    assertNull(report.text)
    assertContains(report.warnings.single(), "no report.md")
    assertEquals(listOf("qa-test-invocation"), leases.releasedInvocations)
    assertEquals(listOf("qa-test-invocation"), workspaces.closedInvocations)
  }

  @Test
  fun `a later QA pass captures the repaired tree and accepts changes during that pass`() {
    val first = execution.withExecution(repo, QaPolicy.AUTO) { it.expected.treeIdentity }
    val repairedTree = "4".repeat(64)
    git.repositoryFingerprintValue = repairedTree
    val second = execution.withExecution(repo, QaPolicy.AUTO) { it.expected.treeIdentity }

    assertEquals(QA_TEST_TREE, first.value)
    assertEquals(repairedTree, second.value)
    execution.withExecution(repo, QaPolicy.AUTO) { git.repositoryFingerprintValue = "5".repeat(64) }
    assertEquals(3, workspaces.closedInvocations.size)
  }
}
