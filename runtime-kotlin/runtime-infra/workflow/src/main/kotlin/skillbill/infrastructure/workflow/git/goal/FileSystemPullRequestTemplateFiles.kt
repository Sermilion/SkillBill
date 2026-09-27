package skillbill.infrastructure.workflow.git.goal

import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.runner.PullRequestTemplateFiles
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemPullRequestTemplateFiles : PullRequestTemplateFiles {
  override fun regularFile(path: Path): Path? = path.takeIf { Files.isRegularFile(it) }?.toRealPath()

  override fun markdownFiles(directory: Path): List<Path> =
    if (Files.isDirectory(directory)) {
      Files.list(directory).use { entries -> entries.toList() }
        .filter { entry -> Files.isRegularFile(entry) && entry.fileName.toString().endsWith(".md") }
        .map { entry -> entry.toRealPath() }
        .sortedBy { entry -> entry.fileName.toString() }
    } else {
      emptyList()
    }

  override fun readText(file: Path): String = Files.readString(file)
}
