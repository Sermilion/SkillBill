package skillbill.infrastructure.workflow.filesystem

import me.tatarka.inject.annotations.Inject
import skillbill.ports.diagnostics.ChildFailureOutputStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet

@Inject
class FileSystemChildFailureOutputStore : ChildFailureOutputStore {
  override fun retain(
    evidenceRoot: Path,
    workflowId: String,
    output: String,
  ): String {
    require(workflowId.matches(Regex("[A-Za-z0-9_-]+")))
    Files.createDirectories(evidenceRoot)
    val directory = evidenceRoot.resolve(workflowId)
    Files.createDirectories(directory)
    require(!Files.isSymbolicLink(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
    val posix = Files.getFileStore(directory).supportsFileAttributeView("posix")
    val file =
      if (posix) {
        Files.createTempFile(
          directory,
          "child-failure-",
          ".log",
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
      } else {
        Files.createTempFile(directory, "child-failure-", ".log")
      }
    try {
      if (!posix) restrictAcl(file)
      Files.writeString(file, output)
    } catch (failure: IOException) {
      try {
        Files.deleteIfExists(file)
      } catch (cleanup: IOException) {
        failure.addSuppressed(cleanup)
      }
      throw failure
    }
    return file.fileName.toString()
  }

  private fun restrictAcl(file: Path) {
    val view =
      Files.getFileAttributeView(file, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        ?: throw IOException("Cannot restrict child failure output permissions on this filesystem.")
    view.acl =
      listOf(
        AclEntry.newBuilder()
          .setType(AclEntryType.ALLOW)
          .setPrincipal(view.owner)
          .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
          .build(),
      )
  }
}
