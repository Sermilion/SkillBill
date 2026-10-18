package skillbill.infrastructure.workflow.qa

import me.tatarka.inject.annotations.Inject
import skillbill.error.featuretask.qa.invalidQaReceipt
import skillbill.error.featuretask.qa.qaDeviceLeaseFailed
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.qa.QaWorkspacePort
import skillbill.ports.qa.model.QaWorkspace
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID

private const val MAX_RECEIPT_BYTES = 256L * 1024L
private const val RECEIPT_FILE = "receipt.json"

@Inject
class FileSystemQaWorkspace(
  private val inspector: MonitorProcessInspector,
  private val clock: Clock,
) : QaWorkspacePort {
  override fun open(
    repoRoot: Path,
    invocationId: String,
  ): QaWorkspace {
    val directory = QaPaths.invocationDirectory(QaPaths.qaRoot(repoRoot), invocationId)
    try {
      Files.createDirectories(directory)
    } catch (failure: IOException) {
      throw qaDeviceLeaseFailed("could not create $directory: ${failure.message}", failure)
    }
    val owner =
      QaInvocationOwner(
        invocationId = invocationId,
        ownerToken = UUID.randomUUID().toString(),
        process = inspector.currentProcess(),
        acquiredAt = clock.instant(),
      )
    writeQaRecordAtomically(QaPaths.ownerFile(directory), QaLeaseRecordCodec.encodeOwner(owner))
    return QaWorkspace(
      invocationId = invocationId,
      directory = directory,
      receiptPath = directory.resolve(RECEIPT_FILE),
      evidencePrefix = ".git/skill-bill/qa/$invocationId/",
    )
  }

  override fun readReceipt(workspace: QaWorkspace): String? =
    try {
      val file = workspace.receiptPath
      when {
        !Files.isRegularFile(file) -> null
        Files.size(file) > MAX_RECEIPT_BYTES ->
          throw invalidQaReceipt("the receipt is larger than $MAX_RECEIPT_BYTES bytes")
        else -> Files.readString(file)
      }
    } catch (failure: IOException) {
      throw invalidQaReceipt("the receipt could not be read: ${failure.message}", failure)
    }

  override fun close(workspace: QaWorkspace) {
    val ownerFile = QaPaths.ownerFile(workspace.directory)
    try {
      Files.deleteIfExists(ownerFile)
    } catch (failure: IOException) {
      throw qaDeviceLeaseFailed("could not delete $ownerFile: ${failure.message}", failure)
    }
  }
}
