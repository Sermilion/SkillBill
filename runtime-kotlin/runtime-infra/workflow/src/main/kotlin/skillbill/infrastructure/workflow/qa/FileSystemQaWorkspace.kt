package skillbill.infrastructure.workflow.qa

import me.tatarka.inject.annotations.Inject
import skillbill.error.featuretask.qa.qaDeviceLeaseFailed
import skillbill.error.featuretask.qa.qaRepositoryUnreadable
import skillbill.model.EnvironmentContext
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.qa.QaWorkspacePort
import skillbill.ports.qa.model.QaReportSource
import skillbill.ports.qa.model.QaWorkspace
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.util.UUID

private const val REPORT_FILE = "report.md"
private const val LEGACY_REPORT_FILE = "receipt.json"

@Inject
class FileSystemQaWorkspace(
  private val inspector: MonitorProcessInspector,
  private val clock: Clock,
  private val environment: EnvironmentContext,
) : QaWorkspacePort {
  override fun open(
    repoRoot: Path,
    invocationId: String,
  ): QaWorkspace {
    val directory = QaPaths.invocationDirectory(QaPaths.qaRoot(environment, repoRoot), invocationId)
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
      reportPath = directory.resolve(REPORT_FILE),
      evidencePrefix = QaPaths.evidencePrefix(directory),
    )
  }

  override fun readReport(reportPath: Path): QaReportSource? =
    try {
      if (Files.notExists(reportPath, LinkOption.NOFOLLOW_LINKS)) {
        null
      } else if (!Files.isRegularFile(reportPath, LinkOption.NOFOLLOW_LINKS)) {
        throw qaRepositoryUnreadable("the report path is not a regular file: $reportPath")
      } else {
        val invocation = reportPath.parent ?: throw qaRepositoryUnreadable("the report has no invocation directory.")
        QaReportSource(readText(reportPath), QaPaths.evidencePrefix(invocation))
      }
    } catch (failure: IOException) {
      throw qaRepositoryUnreadable("the report could not be read: ${failure.message}", failure)
    }

  override fun readRetainedReport(
    repoRoot: Path,
    reference: String,
  ): QaReportSource? {
    val qaRoot = QaPaths.qaRoot(environment, repoRoot).toAbsolutePath().normalize()
    if (Files.notExists(qaRoot, LinkOption.NOFOLLOW_LINKS)) return null
    if (Files.isSymbolicLink(qaRoot)) {
      throw qaRepositoryUnreadable("the QA root is a symbolic link: $qaRoot")
    }
    if (!Files.isDirectory(qaRoot, LinkOption.NOFOLLOW_LINKS)) {
      throw qaRepositoryUnreadable("the QA root is not a directory: $qaRoot")
    }
    val reportPath = retainedReportPath(qaRoot, reference)
    val invocation =
      reportPath.parent ?: throw qaRepositoryUnreadable("the retained report has no invocation directory.")
    if (Files.isSymbolicLink(invocation) || Files.isSymbolicLink(reportPath)) {
      throw qaRepositoryUnreadable("the retained report path contains a symbolic link: $reportPath")
    }
    return try {
      if (Files.notExists(reportPath, LinkOption.NOFOLLOW_LINKS)) {
        null
      } else if (!Files.isRegularFile(reportPath, LinkOption.NOFOLLOW_LINKS)) {
        throw qaRepositoryUnreadable("the retained report path is not a regular file: $reportPath")
      } else {
        QaReportSource(readText(reportPath), QaPaths.evidencePrefix(invocation))
      }
    } catch (failure: IOException) {
      throw qaRepositoryUnreadable("the retained report could not be read: ${failure.message}", failure)
    }
  }

  private fun retainedReportPath(
    qaRoot: Path,
    reference: String,
  ): Path {
    val persisted =
      try {
        Path.of(reference)
      } catch (failure: InvalidPathException) {
        unreadable("the retained report reference is not a valid path: $reference: ${failure.message}", failure)
      }
    if (!persisted.isAbsolute) {
      unreadable("the retained report reference is not absolute: $reference")
    }
    val normalized = persisted.toAbsolutePath().normalize()
    if (!normalized.startsWith(qaRoot)) {
      unreadable("the retained report reference is outside its QA root: $reference")
    }
    val relative = qaRoot.relativize(normalized)
    retainedReportPathViolation(persisted, normalized, relative)?.let {
        violation ->
      unreadable("$violation: $reference")
    }
    return normalized
  }

  private fun retainedReportPathViolation(
    persisted: Path,
    normalized: Path,
    relative: Path,
  ): String? =
    when {
      persisted != normalized -> "the retained report reference is not normalized"
      relative.nameCount != 2 -> "the retained report reference is outside its invocation report"
      relative.getName(0).toString() == "leases" -> "the retained report reference points into leases"
      relative.getName(0).toString() == RESOURCES_DIRECTORY -> "the retained report reference points into resources"
      relative.getName(1).toString() !in setOf(REPORT_FILE, LEGACY_REPORT_FILE) ->
        "the retained report reference is not a report file"
      else -> null
    }

  private fun unreadable(
    detail: String,
    cause: Throwable? = null,
  ): Nothing = throw qaRepositoryUnreadable(detail, cause)

  private fun readText(file: Path): String = String(Files.readAllBytes(file), Charsets.UTF_8)

  override fun close(workspace: QaWorkspace) {
    val ownerFile = QaPaths.ownerFile(workspace.directory)
    try {
      Files.deleteIfExists(ownerFile)
    } catch (failure: IOException) {
      throw qaDeviceLeaseFailed("could not delete $ownerFile: ${failure.message}", failure)
    }
  }
}
