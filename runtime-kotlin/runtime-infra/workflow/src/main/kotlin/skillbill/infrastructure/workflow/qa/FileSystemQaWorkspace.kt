package skillbill.infrastructure.workflow.qa

import me.tatarka.inject.annotations.Inject
import skillbill.error.featuretask.qa.qaDeviceLeaseFailed
import skillbill.error.featuretask.qa.qaRepositoryUnreadable
import skillbill.infrastructure.contracts.sha256Hex
import skillbill.model.EnvironmentContext
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.qa.QaWorkspacePort
import skillbill.ports.qa.model.QaReportSource
import skillbill.ports.qa.model.QaRetainedScreenshot
import skillbill.ports.qa.model.QaWorkspace
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.util.UUID
import javax.imageio.ImageIO

private const val REPORT_FILE = "report.md"
private const val LEGACY_REPORT_FILE = "receipt.json"
private const val MAX_SCREENSHOT_LABEL_LENGTH = 160
private const val MAX_SCREENSHOT_BYTES = 12 * 1024 * 1024
private const val MAX_SCREENSHOT_DIMENSION = 4096
private const val MAX_SCREENSHOT_PIXELS = 16_777_216L

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

  override fun retainScreenshot(
    repoRoot: Path,
    invocationId: String,
    sourcePath: Path,
    label: String,
    asFrame: Boolean,
  ): QaRetainedScreenshot {
    if (label.isBlank() || label.length > MAX_SCREENSHOT_LABEL_LENGTH) unreadable("the screenshot label is invalid")
    val directory =
      QaPaths.invocationDirectory(QaPaths.qaRoot(environment, repoRoot), invocationId)
        .toAbsolutePath().normalize()
    validateOwnedDirectory(directory, invocationId)
    val source = sourcePath.toAbsolutePath().normalize()
    if (!source.startsWith(directory.resolve("evidence")) || source == directory.resolve("evidence")) {
      unreadable("the screenshot source is outside invocation evidence")
    }
    validateNoLinks(directory, source)
    if (!Files.isRegularFile(
        source,
        LinkOption.NOFOLLOW_LINKS,
      )
    ) {
      unreadable("the screenshot source is not a regular file")
    }
    val bytes =
      Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use {
          input ->
        input.readNBytes(MAX_SCREENSHOT_BYTES + 1)
      }
    if (bytes.isEmpty() || bytes.size > MAX_SCREENSHOT_BYTES) unreadable("the screenshot size is invalid")
    val (format, width, height) = inspectScreenshot(bytes, asFrame)
    val target = retainScreenshotBytes(directory, bytes, format)
    return QaRetainedScreenshot(target.toString(), format, bytes.size.toLong(), width, height)
  }

  private fun retainScreenshotBytes(
    directory: Path,
    bytes: ByteArray,
    format: String,
  ): Path {
    val retainedDirectory = directory.resolve("evidence").resolve("retained")
    Files.createDirectories(retainedDirectory)
    validateNoLinks(directory, retainedDirectory)
    val digest = sha256Hex(bytes)
    val target = retainedDirectory.resolve("$digest.$format")
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(
          target,
        ) ||
        !Files.isRegularFile(
          target,
          LinkOption.NOFOLLOW_LINKS,
        ) || sha256Hex(Files.readAllBytes(target)) != digest
      ) {
        unreadable("the retained screenshot target is not an immutable regular file")
      }
    } else {
      val temporary = retainedDirectory.resolve(".$digest.${UUID.randomUUID()}.tmp")
      try {
        FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
          val buffer = ByteBuffer.wrap(bytes)
          while (buffer.hasRemaining()) channel.write(buffer)
          channel.force(true)
        }
        try {
          Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: FileAlreadyExistsException) {
          if (sha256Hex(Files.readAllBytes(target)) != digest) throw failure
          Files.deleteIfExists(temporary)
        }
      } catch (failure: IOException) {
        Files.deleteIfExists(temporary)
        unreadable("the screenshot could not be retained: ${failure.message}", failure)
      }
    }
    return target
  }

  private fun validateOwnedDirectory(
    directory: Path,
    invocationId: String,
  ) {
    if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      unreadable("the QA invocation directory is unavailable")
    }
    val ownerFile = QaPaths.ownerFile(directory)
    if (Files.isSymbolicLink(ownerFile) || !Files.isRegularFile(ownerFile, LinkOption.NOFOLLOW_LINKS)) {
      unreadable("the QA invocation owner record is unavailable")
    }
    val owner =
      QaLeaseRecordCodec.decodeOwner(
        readQaRecord(ownerFile) ?: unreadable("the QA invocation owner record is empty"),
        ownerFile.toString(),
      )
    if (owner.invocationId != invocationId) unreadable("the QA invocation owner does not match")
  }

  private fun validateNoLinks(
    root: Path,
    path: Path,
  ) {
    var current = root
    if (Files.isSymbolicLink(current)) unreadable("the QA invocation path contains a symbolic link")
    for (part in root.relativize(path)) {
      current = current.resolve(part)
      if (Files.isSymbolicLink(current)) unreadable("the QA evidence path contains a symbolic link")
    }
  }

  private fun inspectScreenshot(
    bytes: ByteArray,
    asFrame: Boolean,
  ): Triple<String, Int, Int> {
    val stream =
      ImageIO.createImageInputStream(
        ByteArrayInputStream(bytes),
      ) ?: unreadable("the screenshot format is invalid")
    stream.use {
      val readers = ImageIO.getImageReaders(it)
      if (!readers.hasNext()) unreadable("the screenshot format is unsupported")
      val reader = readers.next()
      try {
        reader.input = it
        val format = reader.formatName.lowercase()
        if (format !in setOf("png", "jpeg")) unreadable("the screenshot format is unsupported")
        val width = reader.getWidth(0)
        val height = reader.getHeight(0)
        if (width !in 1..MAX_SCREENSHOT_DIMENSION || height !in 1..MAX_SCREENSHOT_DIMENSION ||
          width.toLong() * height.toLong() > MAX_SCREENSHOT_PIXELS
        ) {
          unreadable("the screenshot dimensions exceed the limits")
        }
        val image = reader.read(0) ?: unreadable("the screenshot is incomplete")
        if (asFrame && isUniform(image)) unreadable("the captured frame is blank")
        return Triple(format, width, height)
      } catch (failure: IOException) {
        unreadable("the screenshot could not be decoded: ${failure.message}", failure)
      } finally {
        reader.dispose()
      }
    }
  }

  private fun isUniform(image: BufferedImage): Boolean {
    val first = image.getRGB(0, 0)
    for (y in 0 until image.height) {
      for (x in 0 until image.width) {
        if (image.getRGB(x, y) != first) return false
      }
    }
    return true
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
