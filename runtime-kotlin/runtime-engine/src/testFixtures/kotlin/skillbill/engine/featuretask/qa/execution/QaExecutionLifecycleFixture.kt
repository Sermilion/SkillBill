package skillbill.engine.featuretask.qa.execution

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.qa.QaDeviceLeasePort
import skillbill.ports.qa.QaWorkspacePort
import skillbill.ports.qa.model.QaDeviceLease
import skillbill.ports.qa.model.QaLeaseReleaseOutcome
import skillbill.ports.qa.model.QaLeaseResult
import skillbill.ports.qa.model.QaReceiptSource
import skillbill.ports.qa.model.QaWorkspace
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.nio.file.Path

fun testQaExecutionLifecycle(
  gitOperations: WorkflowGitOperations =
    RecordingWorkflowGitOperations().apply {
      headCommitShaValue = "1".repeat(40)
      repositoryFingerprintValue = "2".repeat(64)
    },
  workspaces: QaWorkspacePort = TestQaWorkspaces(),
  leases: QaDeviceLeasePort = TestQaLeases(),
  capabilities: QaCapabilityResolver = QaCapabilityResolver { emptyList() },
  identifiers: IdentifierGeneratorPort = IdentifierGeneratorPort { "test-invocation" },
): QaExecutionLifecycle =
  QaExecutionLifecycle(
    workspaces,
    leases,
    capabilities,
    gitOperations,
    NoopRuntimeDiagnostics,
    identifiers,
  )

private class TestQaWorkspaces : QaWorkspacePort {
  override fun open(
    repoRoot: Path,
    invocationId: String,
  ): QaWorkspace {
    val directory = repoRoot.resolve(".git/skill-bill/qa/$invocationId")
    return QaWorkspace(invocationId, directory, directory.resolve("receipt.json"), ".git/skill-bill/qa/$invocationId/")
  }

  override fun readReceipt(receiptPath: Path): QaReceiptSource? = null

  override fun readRetainedReceipt(
    qaRoot: Path,
    receiptReference: String,
  ): QaReceiptSource? = null

  override fun close(workspace: QaWorkspace) = Unit
}

private class TestQaLeases : QaDeviceLeasePort {
  override fun acquire(
    repoRoot: Path,
    deviceFingerprint: String,
    invocationId: String,
  ): QaLeaseResult = error("The runtime does not acquire QA device leases.")

  override fun release(
    repoRoot: Path,
    lease: QaDeviceLease,
  ): QaLeaseReleaseOutcome = error("The runtime releases QA device leases by invocation.")

  override fun releaseAllFor(
    repoRoot: Path,
    invocationId: String,
  ): List<QaLeaseReleaseOutcome> = emptyList()
}
