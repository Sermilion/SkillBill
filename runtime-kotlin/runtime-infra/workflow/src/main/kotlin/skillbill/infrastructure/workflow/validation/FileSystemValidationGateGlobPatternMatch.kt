package skillbill.infrastructure.workflow.validation

import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal fun fileSystemValidationGateExpandGlob(
  repoRoot: Path,
  glob: String,
): List<Path> {
  val normalized = glob.replace('\\', '/')
  val matcher = FileSystems.getDefault().getPathMatcher("glob:$normalized")
  val strippedRootGlob = normalized.removePrefix("**/")
  val rootMatcher =
    strippedRootGlob.takeIf { it != normalized && it.isNotEmpty() }?.let { stripped ->
      FileSystems.getDefault().getPathMatcher("glob:$stripped")
    }
  if (!Files.isDirectory(repoRoot)) return emptyList()
  val matches = ArrayList<Path>()
  Files.walkFileTree(
    repoRoot,
    object : SimpleFileVisitor<Path>() {
      override fun preVisitDirectory(
        dir: Path,
        attrs: BasicFileAttributes,
      ): FileVisitResult {
        if (dir != repoRoot && dir.fileName?.toString() == ".git") {
          return FileVisitResult.SKIP_SUBTREE
        }
        return FileVisitResult.CONTINUE
      }

      override fun visitFile(
        file: Path,
        attrs: BasicFileAttributes,
      ): FileVisitResult {
        val relative = repoRoot.relativize(file).toString().replace('\\', '/')
        val relativePath = Path.of(relative)
        if (matcher.matches(relativePath) || rootMatcher?.matches(relativePath) == true) {
          matches.add(file)
        }
        return FileVisitResult.CONTINUE
      }

      override fun visitFileFailed(
        file: Path,
        exc: IOException,
      ): FileVisitResult {
        if (exc is NoSuchFileException) {
          return FileVisitResult.CONTINUE
        }
        throw exc
      }

      override fun postVisitDirectory(
        dir: Path,
        exc: IOException?,
      ): FileVisitResult {
        if (exc is NoSuchFileException) {
          return FileVisitResult.CONTINUE
        }
        if (exc != null) {
          throw exc
        }
        return FileVisitResult.CONTINUE
      }
    },
  )
  return matches
}
