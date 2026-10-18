package skillbill.engine.featuretask.phaserun.qa

import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.qa.receipt.QaReceiptSettlement
import skillbill.engine.featuretask.qa.receipt.QaReceiptValidator
import skillbill.infrastructure.contracts.qa.QaReceiptSchemaValidator
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.qa.QaDeviceLeasePort
import skillbill.ports.qa.QaWorkspacePort
import skillbill.ports.qa.model.QaDeviceLease
import skillbill.ports.qa.model.QaLeaseReleaseOutcome
import skillbill.ports.qa.model.QaLeaseResult
import skillbill.ports.qa.model.QaWorkspace
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

internal class InMemoryQaWorkspaces : QaWorkspacePort {
  var receipt: String? = null

  override fun open(
    repoRoot: Path,
    invocationId: String,
  ): QaWorkspace {
    val directory = repoRoot.resolve(".git/skill-bill/qa/$invocationId")
    return QaWorkspace(invocationId, directory, directory.resolve("receipt.json"), ".git/skill-bill/qa/$invocationId/")
  }

  override fun readReceipt(workspace: QaWorkspace): String? = receipt

  override fun close(workspace: QaWorkspace) = Unit
}

internal class RecordingQaLeases(
  private val outcomes: List<QaLeaseReleaseOutcome> = emptyList(),
) : QaDeviceLeasePort {
  val releasedInvocations = mutableListOf<String>()

  override fun acquire(
    repoRoot: Path,
    deviceFingerprint: String,
    invocationId: String,
  ): QaLeaseResult = error("The runtime never takes a device lease; the agent does.")

  override fun release(
    repoRoot: Path,
    lease: QaDeviceLease,
  ): QaLeaseReleaseOutcome = error("The runtime releases leases by invocation only.")

  override fun releaseAllFor(
    repoRoot: Path,
    invocationId: String,
  ): List<QaLeaseReleaseOutcome> {
    releasedInvocations += invocationId
    return outcomes
  }
}

internal fun qaStandalonePhaseRun(
  gitOperations: WorkflowGitOperations,
  manifests: List<PlatformManifest> = emptyList(),
  workspaces: QaWorkspacePort = InMemoryQaWorkspaces(),
  leases: QaDeviceLeasePort = RecordingQaLeases(),
): QaStandalonePhaseRun {
  val validator = QaReceiptValidator(QaReceiptSchemaValidator())
  return QaStandalonePhaseRun(
    workspaces = workspaces,
    leases = leases,
    capabilities = QaCapabilityResolver { manifests },
    settlement = QaReceiptSettlement(validator, workspaces),
    validator = validator,
    gitOperations = gitOperations,
    diagnostics = NoopRuntimeDiagnostics,
  )
}
