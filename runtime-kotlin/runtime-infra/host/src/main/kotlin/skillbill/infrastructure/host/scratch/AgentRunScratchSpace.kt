package skillbill.infrastructure.host.scratch

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.host.jvm.RuntimeTempEnvironmentKeys
import skillbill.infrastructure.host.skillBillStateDir
import skillbill.model.EnvironmentContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
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
  private val clock: Clock,
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

  fun environment(
    directory: Path?,
    inherited: Map<String, String>,
  ): Map<String, String> {
    if (directory == null) return emptyMap()
    return SCRATCH_ENVIRONMENT_KEYS.associateWith { directory.toString() } +
      mapOf(
        RuntimeTempEnvironmentKeys.SKILL_BILL_RUN_SCRATCH_DIR to directory.toString(),
        RuntimeTempEnvironmentKeys.JAVA_TOOL_OPTIONS to
          javaToolOptions(inherited[RuntimeTempEnvironmentKeys.JAVA_TOOL_OPTIONS].orEmpty()),
      )
  }

  private fun javaToolOptions(inherited: String): String {
    val kept =
      JVM_OPTION_TOKEN.findAll(inherited)
        .map { it.value }
        .filterNot { it.trimStart('"', '\'').startsWith(TEMP_ROOT_OPTION) }
    val jvmRoot = root.resolve(JVM_DIRECTORY).toString()
    return (kept + "$TEMP_ROOT_OPTION${quotedForJvmOptions(jvmRoot)}").joinToString(" ")
  }

  private fun quotedForJvmOptions(path: String): String {
    val needsQuoting = path.any { it.isWhitespace() || it == '"' || it == '\'' }
    return if (needsQuoting) "\"${path.replace("\"", JVM_OPTION_LITERAL_DOUBLE_QUOTE)}\"" else path
  }

  private fun create(): Path {
    Files.createDirectories(root)
    sweepAbandoned()
    prepareJvmRoot()
    val directory = Files.createTempDirectory(root, DIRECTORY_PREFIX)
    directory.resolve(OWNER_FILE).writeText(ownerPid.toString())
    return directory
  }

  private fun prepareJvmRoot() {
    val jvmRoot = root.resolve(JVM_DIRECTORY)
    catchingNonCancellation {
      Files.createDirectories(jvmRoot)
      val cutoff = clock.instant().minus(JVM_ENTRY_RETENTION)
      jvmRoot.listDirectoryEntries().forEach { entry -> deleteIfOlderThan(entry, cutoff) }
    }.onFailure { failure ->
      logger.warning("skillbill agent run: JVM temp root '$jvmRoot' was not swept: ${failure.message}")
    }
  }

  private fun deleteIfOlderThan(
    entry: Path,
    cutoff: Instant,
  ) {
    val modified =
      try {
        Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toInstant()
      } catch (failure: IOException) {
        logger.warning("skillbill agent run: JVM temp entry '$entry' was not swept: ${failure.message}")
        return
      }
    if (modified.isBefore(cutoff)) delete(entry)
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
    const val JVM_DIRECTORY: String = "jvm"

    private val SCRATCH_ENVIRONMENT_KEYS: List<String> = listOf("TMPDIR", "TMP", "TEMP")
    private const val TEMP_ROOT_OPTION = "-Djava.io.tmpdir="
    private const val JVM_OPTION_LITERAL_DOUBLE_QUOTE = "\"'\"'\""
    private val JVM_OPTION_TOKEN = Regex("""(?:[^\s"']+|"[^"]*"?|'[^']*'?)+""")
    private val JVM_ENTRY_RETENTION: Duration = Duration.ofDays(7)
    private val logger: Logger = Logger.getLogger("skillbill.agent.run.scratch")
  }
}
