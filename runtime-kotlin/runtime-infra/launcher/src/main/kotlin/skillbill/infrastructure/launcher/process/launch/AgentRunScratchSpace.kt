package skillbill.infrastructure.launcher.process.launch

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.host.skillBillStateDir
import skillbill.model.EnvironmentContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Inject
class AgentRunScratchRoot(
  context: EnvironmentContext,
) {
  val path: Path = context.skillBillStateDir().resolve("scratch")
}

class AgentRunScratchSpace(
  private val root: Path,
  private val ownerPid: Long = ProcessHandle.current().pid(),
  private val isProcessAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).isPresent },
) {
  fun <T> withScratch(block: (Path?) -> T): T {
    val directory =
      catchingNonCancellation { create() }.getOrElse { failure ->
        logger.warning("skillbill agent run: scratch directory unavailable under '$root': ${failure.message}")
        return block(null)
      }
    return try {
      block(directory)
    } finally {
      delete(directory)
    }
  }

  fun environment(directory: Path?): Map<String, String> =
    directory?.let { SCRATCH_ENVIRONMENT_KEYS.associateWith { directory.toString() } }.orEmpty()

  private fun create(): Path {
    Files.createDirectories(root)
    sweepAbandoned()
    val directory = Files.createTempDirectory(root, DIRECTORY_PREFIX)
    directory.resolve(OWNER_FILE).writeText(ownerPid.toString())
    return directory
  }

  private fun sweepAbandoned() {
    root.listDirectoryEntries("$DIRECTORY_PREFIX*")
      .filter { it.isDirectory() && !ownedByLiveProcess(it) }
      .forEach(::delete)
  }

  private fun ownedByLiveProcess(directory: Path): Boolean {
    val pid =
      try {
        directory.resolve(OWNER_FILE).readText().trim().toLongOrNull()
      } catch (failure: IOException) {
        logger.warning(
          "skillbill agent run: scratch directory '$directory' has no readable owner; " +
            "treating it as abandoned: ${failure.message}",
        )
        null
      } ?: return false
    return pid == ownerPid || isProcessAlive(pid)
  }

  @OptIn(ExperimentalPathApi::class)
  private fun delete(directory: Path) {
    try {
      directory.deleteRecursively()
    } catch (failure: IOException) {
      logger.warning("skillbill agent run: scratch directory '$directory' was not deleted: ${failure.message}")
    }
  }

  companion object {
    const val DIRECTORY_PREFIX: String = "run-"
    const val OWNER_FILE: String = ".owner-pid"
    val SCRATCH_ENVIRONMENT_KEYS: List<String> = listOf("TMPDIR", "TMP", "TEMP")

    private val logger: Logger = Logger.getLogger("skillbill.agent.run.scratch")
  }
}
