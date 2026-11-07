package skillbill.infrastructure.host.system

import me.tatarka.inject.annotations.Inject
import skillbill.agent.model.BuildRepositoryFiles
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.nio.file.Files
import java.nio.file.Path

@Inject
class JdkBuildRepositoryFiles(private val diagnostics: RuntimeDiagnostics) : BuildRepositoryFiles {
  override fun exists(path: String): Boolean =
    catchingNonCancellation { Files.exists(Path.of(path)) }.getOrElse { failure ->
      warn("exists", failure.javaClass.simpleName, failure)
      false
    }

  override fun readText(path: String): String? =
    catchingNonCancellation {
      Path.of(path).takeIf { Files.isRegularFile(it) }?.let { file ->
        Files.newInputStream(file).use { input ->
          val bytes = input.readNBytes(MAX_BYTES + 1)
          if (bytes.size > MAX_BYTES) {
            warn("read", "size_limit")
            null
          } else {
            bytes.toString(Charsets.UTF_8)
          }
        }
      }
    }.getOrElse { failure ->
      warn("read", failure.javaClass.simpleName, failure)
      null
    }

  private fun warn(
    op: String,
    cause: String,
    failure: Throwable? = null,
  ) {
    diagnostics.warning(
      "seam=build_repository_files op=$op value_expected=readable value_used=unavailable cause=$cause",
      failure,
    )
  }

  private companion object {
    const val MAX_BYTES = 64 * 1024
  }
}
