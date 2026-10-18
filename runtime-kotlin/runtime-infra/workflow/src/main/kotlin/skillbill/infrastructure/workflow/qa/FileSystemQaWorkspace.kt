package skillbill.infrastructure.workflow.qa

import me.tatarka.inject.annotations.Inject
import skillbill.error.featuretask.qa.invalidQaReceipt
import skillbill.error.featuretask.qa.qaDeviceLeaseFailed
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.qa.QaWorkspacePort
import skillbill.ports.qa.model.QaReceiptSource
import skillbill.ports.qa.model.QaWorkspace
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
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
      evidencePrefix = QaPaths.evidencePrefix(invocationId),
    )
  }

  override fun readReceipt(receiptPath: Path): QaReceiptSource? =
    try {
      val file = receiptPath
      when {
        !Files.isRegularFile(file) -> null
        Files.size(file) > MAX_RECEIPT_BYTES ->
          throw invalidQaReceipt("the receipt is larger than $MAX_RECEIPT_BYTES bytes")
        else -> {
          val invocation = file.parent?.fileName ?: throw invalidQaReceipt("receipt has no invocation directory")
          QaReceiptSource(Files.readString(file), QaPaths.evidencePrefix(invocation.toString()))
        }
      }
    } catch (failure: IOException) {
      throw invalidQaReceipt("the receipt could not be read: ${failure.message}", failure)
    }

  override fun readRetainedReceipt(
    qaRoot: Path,
    receiptReference: String,
  ): QaReceiptSource? {
    val reference = absoluteReference(receiptReference)
    val normalizedQaRoot = qaRoot.toAbsolutePath().normalize()
    val invocationDirectory =
      reference.parent
        ?: throw invalidQaReceipt("the retained receipt reference has no invocation directory.")
    val invocationId =
      invocationDirectory.fileName?.toString()
        ?: throw invalidQaReceipt("the retained receipt reference has no invocation id.")
    val expected = QaPaths.invocationDirectory(normalizedQaRoot, invocationId).resolve(RECEIPT_FILE).normalize()
    val linked = listOf(normalizedQaRoot, invocationDirectory, reference).any(Files::isSymbolicLink)
    if (reference != expected || linked) {
      throw invalidQaReceipt("the retained receipt reference is outside its repository QA directory.")
    }
    if (!Files.exists(reference, LinkOption.NOFOLLOW_LINKS)) return null
    try {
      val realRoot = normalizedQaRoot.toRealPath()
      val realFile = reference.toRealPath()
      if (!realFile.startsWith(realRoot) || !Files.isRegularFile(realFile, LinkOption.NOFOLLOW_LINKS)) {
        throw invalidQaReceipt("the retained receipt is not a regular file under the repository QA directory.")
      }
      val bytes = Files.newInputStream(realFile).use { input -> input.readNBytes(MAX_RECEIPT_BYTES.toInt() + 1) }
      if (bytes.size > MAX_RECEIPT_BYTES) {
        throw invalidQaReceipt("the receipt is larger than $MAX_RECEIPT_BYTES bytes")
      }
      return QaReceiptSource(
        String(bytes, Charsets.UTF_8),
        QaPaths.evidencePrefix(invocationId),
        reference.toString(),
      )
    } catch (failure: IOException) {
      throw invalidQaReceipt("the retained receipt could not be read: ${failure.message}", failure)
    }
  }

  private fun absoluteReference(receiptReference: String): Path {
    val parsed =
      try {
        Path.of(receiptReference)
      } catch (failure: InvalidPathException) {
        throw invalidQaReceipt("the retained receipt reference is invalid.", failure)
      }
    if (!parsed.isAbsolute) throw invalidQaReceipt("the retained receipt reference must be absolute.")
    return parsed.normalize()
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
