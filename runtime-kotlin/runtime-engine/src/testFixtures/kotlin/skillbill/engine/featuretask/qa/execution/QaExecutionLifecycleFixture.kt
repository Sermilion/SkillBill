package skillbill.engine.featuretask.qa.execution

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.qa.QaDeviceLeasePort
import skillbill.ports.qa.QaOwnedResourcePort
import skillbill.ports.qa.QaWorkspacePort
import skillbill.ports.qa.model.QaDeviceLease
import skillbill.ports.qa.model.QaLeaseReleaseOutcome
import skillbill.ports.qa.model.QaLeaseResult
import skillbill.ports.qa.model.QaOwnedResource
import skillbill.ports.qa.model.QaReportSource
import skillbill.ports.qa.model.QaResourceRegistration
import skillbill.ports.qa.model.QaResourceReleaseOutcome
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
  capabilities: QaCapabilityResolver = QaCapabilityResolver(gitOperations) { emptyList() },
  identifiers: IdentifierGeneratorPort = IdentifierGeneratorPort { "test-invocation" },
  diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
  resources: QaOwnedResourcePort = TestQaOwnedResources(),
): QaExecutionLifecycle =
  QaExecutionLifecycle(
    workspaces,
    leases,
    capabilities,
    QaCheckouts(gitOperations, diagnostics),
    gitOperations,
    diagnostics,
    identifiers,
    resources,
  )

private class TestQaWorkspaces : QaWorkspacePort {
  override fun open(
    repoRoot: Path,
    invocationId: String,
  ): QaWorkspace {
    val directory = Path.of("/home/qa/.skill-bill/qa/app-0123456789abcdef", invocationId)
    return QaWorkspace(invocationId, directory, directory.resolve("report.md"), "$directory/")
  }

  override fun readReport(reportPath: Path): QaReportSource? = null

  override fun readRetainedReport(
    repoRoot: Path,
    reference: String,
  ): QaReportSource? = null

  override fun close(workspace: QaWorkspace) = Unit
}

private class TestQaOwnedResources : QaOwnedResourcePort {
  override fun register(
    repoRoot: Path,
    invocationId: String,
    resource: QaOwnedResource,
  ): QaResourceRegistration = QaResourceRegistration.Registered

  override fun release(
    repoRoot: Path,
    invocationId: String,
    resource: QaOwnedResource,
  ): QaResourceReleaseOutcome = QaResourceReleaseOutcome.NotHeld(resource)

  override fun releaseAllFor(
    repoRoot: Path,
    invocationId: String,
  ): List<QaResourceReleaseOutcome> = emptyList()

  override fun reclaimAbandoned(repoRoot: Path): List<QaResourceReleaseOutcome> = emptyList()
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
