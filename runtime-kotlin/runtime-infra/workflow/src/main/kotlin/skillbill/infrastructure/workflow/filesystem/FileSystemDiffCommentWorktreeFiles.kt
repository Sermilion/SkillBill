package skillbill.infrastructure.workflow.filesystem

import me.tatarka.inject.annotations.Inject
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.review.diffcomment.DiffCommentWorktreeFiles
import skillbill.ports.review.diffcomment.model.DiffCommentLineObservation
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemDiffCommentWorktreeFiles(private val diagnostics: RuntimeDiagnostics) : DiffCommentWorktreeFiles {
  override fun lineText(
    worktreeRoot: Path,
    path: String,
    line: Int,
  ): DiffCommentLineObservation {
    if (invalidPath(path) || line < 1) return DiffCommentLineObservation.Rejected
    val root = worktreeRoot.toAbsolutePath().normalize()
    val candidate = root.resolve(path).normalize()
    if (!candidate.startsWith(root)) return DiffCommentLineObservation.Rejected
    return try {
      observe(root, candidate, line)
    } catch (failure: IOException) {
      diagnostics.warning("Unable to read diff comment anchor file.", failure)
      DiffCommentLineObservation.Missing
    }
  }

  private fun invalidPath(path: String): Boolean =
    when {
      path.isBlank() || '\u0000' in path -> true
      Path.of(path).isAbsolute -> true
      else -> path.split('/').any { it == ".." }
    }

  private fun observe(
    root: Path,
    candidate: Path,
    line: Int,
  ): DiffCommentLineObservation {
    if (!Files.exists(candidate)) return DiffCommentLineObservation.Missing
    val realRoot = root.toRealPath()
    val realCandidate = candidate.toRealPath()
    if (!realCandidate.startsWith(realRoot)) return DiffCommentLineObservation.Rejected
    return if (Files.isRegularFile(realCandidate)) {
      readLine(realCandidate, line)
    } else {
      DiffCommentLineObservation.Missing
    }
  }

  private fun readLine(
    candidate: Path,
    line: Int,
  ): DiffCommentLineObservation {
    val decoder =
      StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    return Files.newInputStream(candidate).use { stream ->
      InputStreamReader(stream, decoder).buffered().use { reader ->
        var current = 1
        while (current < line) {
          if (reader.readLine() == null) return DiffCommentLineObservation.Missing
          current++
        }
        val value =
          reader.readLine()
            ?: if (line == 1 && Files.size(candidate) == 0L) "" else return DiffCommentLineObservation.Missing
        DiffCommentLineObservation.Text(value)
      }
    }
  }
}
