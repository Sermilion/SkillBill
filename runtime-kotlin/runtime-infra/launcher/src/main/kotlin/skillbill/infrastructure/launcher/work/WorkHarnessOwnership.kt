package skillbill.infrastructure.launcher.work

import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.work.model.WorkRunStopResult
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class WorkHarnessOwnership(private val directory: Path, private val diagnostics: RuntimeDiagnostics) {
  fun <T> admitted(
    database: Path,
    key: String,
    busy: () -> T,
    operation: (Path) -> T,
  ): T {
    Files.createDirectories(directory)
    val digest =
      MessageDigest.getInstance("SHA-256")
        .digest((database.toString() + key).toByteArray()).joinToString("") { "%02x".format(it) }
    FileChannel.open(directory.resolve("$digest.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use {
        channel ->
      val lock =
        try {
          channel.tryLock()
        } catch (_: OverlappingFileLockException) {
          null
        }
      if (lock == null) return busy()
      lock.use { return operation(directory.resolve("$digest.owner")) }
    }
  }

  fun running(owner: Path): Boolean = read(owner)?.let { exact(it) != null } == true

  fun save(
    owner: Path,
    process: Process,
    scopeUnit: String?,
  ) {
    val birth = process.toHandle().info().startInstant().orElse(null)
    if (birth == null && process.waitFor(EXIT_RACE_WAIT_MILLIS, TimeUnit.MILLISECONDS)) return
    if (birth == null) throw IOException("The harness exposes no process birth evidence.")
    val staged = Files.createTempFile(directory, "owner-", ".tmp")
    try {
      Files.writeString(staged, "${process.pid()}\n$birth\n${scopeUnit.orEmpty()}\n")
      Files.move(staged, owner, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
      Files.deleteIfExists(staged)
    }
  }

  fun clear(
    owner: Path,
    pid: Long,
  ) {
    if (read(owner)?.pid == pid) Files.deleteIfExists(owner)
  }

  fun stop(owner: Path): WorkRunStopResult {
    val saved = read(owner) ?: return WorkRunStopResult.NotRunning
    return stopOwned(owner, saved)
  }

  fun stopStarted(
    process: Process,
    unit: String?,
  ) {
    val birth = process.toHandle().info().startInstant().orElse(null)
    if (birth == null) {
      process.destroyForcibly()
    } else {
      stopOwned(null, OwnedHarness(process.pid(), birth, unit))
    }
  }

  private fun stopOwned(
    owner: Path?,
    saved: OwnedHarness,
  ): WorkRunStopResult {
    val handle = exact(saved) ?: return WorkRunStopResult.NotRunning
    val descendants =
      handle.descendants().use { it.toList() }.mapNotNull { child ->
        child.info().startInstant().orElse(null)?.let { birth -> OwnedHarness(child.pid(), birth, null) }
      }
    if (saved.scopeUnit != null) {
      val stop =
        ProcessBuilder("systemctl", "--user", "stop", saved.scopeUnit)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
      if (!stop.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS) || stop.exitValue() != 0) {
        stop.destroyForcibly()
        diagnostics.warning("seam=work-run.cancel outcome=scope_stop_failed unit=${saved.scopeUnit}")
      }
    }
    exact(saved)?.destroy()
    descendants.asReversed().forEach { exact(it)?.destroy() }
    await(handle)
    (descendants + saved).forEach { exact(it)?.destroyForcibly() }
    await(handle)
    val remaining = (descendants + saved).any { exact(it) != null }
    return if (remaining) {
      WorkRunStopResult.Refused("The installed harness has not stopped yet.")
    } else {
      if (owner != null) clear(owner, saved.pid)
      diagnostics.info("seam=work-run.cancel outcome=harness_stopped pid=${saved.pid}")
      WorkRunStopResult.Stopped
    }
  }

  private fun await(handle: ProcessHandle) {
    if (!handle.isAlive) return
    try {
      handle.onExit().get(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)
    } catch (
      _: TimeoutException,
    ) {
      diagnostics.warning("seam=work-run.cancel outcome=exit_timeout pid=${handle.pid()}")
    }
  }

  private fun read(owner: Path): OwnedHarness? {
    if (!Files.exists(owner)) return null
    val fields = Files.readAllLines(owner)
    if (fields.size != OWNER_FIELDS) throw IOException("Invalid saved harness ownership.")
    val unit = fields[2].takeIf(String::isNotBlank)
    val pid = fields[0].toLongOrNull()
    val validUnit = unit == null || unit.matches(Regex("skillbill-run-[a-f0-9-]+\\.scope"))
    if (pid == null || pid <= 0 || !validUnit) {
      throw IOException("Invalid saved harness ownership fields.")
    }
    val birth = parseBirth(fields[1])
    return OwnedHarness(pid, birth, unit)
  }

  private fun parseBirth(value: String): Instant =
    try {
      Instant.parse(value)
    } catch (failure: DateTimeParseException) {
      throw IOException("Invalid saved harness birth evidence.", failure)
    }

  private companion object {
    const val EXIT_WAIT_SECONDS = 5L
    const val EXIT_RACE_WAIT_MILLIS = 500L
    const val OWNER_FIELDS = 3
  }

  private fun exact(owner: OwnedHarness): ProcessHandle? =
    ProcessHandle.of(owner.pid).orElse(null)?.takeIf {
      it.isAlive && it.info().startInstant().orElse(null) == owner.birth
    }

  private data class OwnedHarness(val pid: Long, val birth: Instant, val scopeUnit: String?)
}
