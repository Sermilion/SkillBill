package skillbill.infrastructure.workflow.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.ports.featuretask.PhaseInstructionFileSource
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemPhaseInstructionFileSource : PhaseInstructionFileSource {
  override fun markdownFileNames(directory: Path): List<String>? =
    if (Files.isDirectory(directory)) {
      Files.newDirectoryStream(directory, "*$MARKDOWN_SUFFIX").use { entries ->
        entries.map { entry -> entry.fileName.toString() }
      }
    } else {
      null
    }

  override fun regularFileSize(file: Path): Long? = if (Files.isRegularFile(file)) Files.size(file) else null

  override fun readBytes(file: Path): ByteArray = Files.readAllBytes(file)

  private companion object {
    const val MARKDOWN_SUFFIX = ".md"
  }
}
