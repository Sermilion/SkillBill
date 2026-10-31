package skillbill.infrastructure.launcher.agentrun

import skillbill.ports.agentrun.ExecutableLookup
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class PathExecutableLookup(
  private val pathVariable: () -> String? = { System.getenv("PATH") },
) : ExecutableLookup {
  override fun onPath(executable: String): Boolean {
    if (executable.isBlank()) return false
    if (executable.contains(File.separatorChar) || executable.contains('/')) {
      return isRunnable(Path.of(executable))
    }
    val search = pathVariable() ?: return false
    return search.splitToSequence(File.pathSeparatorChar)
      .filter(String::isNotBlank)
      .any { directory -> isRunnable(Path.of(directory).resolve(executable)) }
  }

  private fun isRunnable(candidate: Path): Boolean = Files.isRegularFile(candidate) && Files.isExecutable(candidate)
}
