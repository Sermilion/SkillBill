package skillbill.engine.featuretask.phaserun.qa

import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.qa.execution.testQaExecutionLifecycle
import skillbill.engine.featuretask.qa.receipt.QaReceiptSettlement
import skillbill.engine.featuretask.qa.receipt.QaReceiptValidator
import skillbill.infrastructure.contracts.qa.QaReceiptSchemaValidator
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.qa.QaDeviceLeasePort
import skillbill.ports.qa.QaWorkspacePort
import skillbill.ports.qa.model.QaDeviceLease
import skillbill.ports.qa.model.QaLeaseReleaseOutcome
import skillbill.ports.qa.model.QaLeaseResult
import skillbill.ports.qa.model.QaReceiptSource
import skillbill.ports.qa.model.QaWorkspace
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

internal class InMemoryQaWorkspaces : QaWorkspacePort {
  var receipt: String? = null
  val closedInvocations = mutableListOf<String>()
  val receipts = mutableMapOf<Path, String>()
  private var current: QaWorkspace? = null

  override fun open(
    repoRoot: Path,
    invocationId: String,
  ): QaWorkspace {
    val directory = repoRoot.resolve(".git/skill-bill/qa/$invocationId")
    return QaWorkspace(invocationId, directory, directory.resolve("receipt.json"), ".git/skill-bill/qa/$invocationId/")
      .also { current = it }
  }

  override fun readReceipt(receiptPath: Path): QaReceiptSource? =
    (if (receiptPath == current?.receiptPath) receipt else receipts[receiptPath])?.let { raw ->
      QaReceiptSource(raw, ".git/skill-bill/qa/${receiptPath.parent.fileName}/")
    }

  override fun readRetainedReceipt(
    qaRoot: Path,
    receiptReference: String,
  ): QaReceiptSource? =
    receipts[Path.of(receiptReference)]?.let { raw ->
      val invocationId = Path.of(receiptReference).parent.fileName.toString()
      QaReceiptSource(raw, ".git/skill-bill/qa/$invocationId/", receiptReference)
    }

  override fun close(workspace: QaWorkspace) {
    receipt?.let { receipts[workspace.receiptPath] = it }
    closedInvocations += workspace.invocationId
  }
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
    execution = testQaExecutionLifecycle(gitOperations, workspaces, leases, QaCapabilityResolver { manifests }),
    settlement = QaReceiptSettlement(validator, workspaces, NoopRuntimeDiagnostics),
    validator = validator,
  )
}
