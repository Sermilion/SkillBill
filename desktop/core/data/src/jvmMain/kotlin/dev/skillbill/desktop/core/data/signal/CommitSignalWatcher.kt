package dev.skillbill.desktop.core.data.signal

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import skillbill.contracts.persistence.DesktopCommitSignal
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Publishes the session database's commit signal as [snapshots]. It wakes only on file-system events, never on a timer, and
 * never creates the signal file or its directory. The watch is registered before the first read, so a commit that lands
 * between the two is not missed.
 */
class CommitSignalWatcher internal constructor(
  private val scope: CoroutineScope,
  databasePath: Path,
  private val diagnostics: Diagnostics,
  private val switch: DiagnosticsSwitch,
  private val clock: Clock,
  private val watchFactory: (Path) -> SignalWatch,
) {
  private val signalPath = databasePath.toAbsolutePath().normalize().let { absolute ->
    absolute.resolveSibling("${absolute.fileName}${DesktopCommitSignal.FILE_SUFFIX}")
  }
  private val mutableSnapshots = MutableStateFlow(SignalSnapshot.Initial)
  val snapshots: StateFlow<SignalSnapshot> = mutableSnapshots.asStateFlow()

  /** Runs on `Dispatchers.IO`, not the database dispatcher, because the watch blocks a thread. Ends with an [IOException] when the watch stops. */
  fun start(): Job = scope.launch(Dispatchers.IO) {
    var watch: SignalWatch? = null
    try {
      val active = watchFactory(signalPath).also { watch = it }
      refresh()
      while (true) {
        active.awaitChange()
        refresh()
      }
    } catch (failure: IOException) {
      diagnostics.record("desktop_signal_watch_ended")
      throw failure
    } finally {
      withContext(NonCancellable) { watch?.close() }
    }
  }

  private fun refresh() {
    val parsed = try {
      parseCommitSignal(readSignal() ?: return)
    } catch (_: CharacterCodingException) {
      null
    }
    if (parsed == null) diagnostics.record("desktop_signal_payload_invalid")
    mutableSnapshots.value = mutableSnapshots.value.advance(parsed)
    if (switch.enabled) logLatency()
  }

  /** The file's text, at most one character past the limit so an oversized payload is detected; null when missing or empty. */
  private fun readSignal(): String? = try {
    Files.newBufferedReader(signalPath).use { reader ->
      val buffer = CharArray(DesktopCommitSignal.MAX_SIGNAL_CHARS + 1)
      var length = 0
      while (length < buffer.size) {
        val read = reader.read(buffer, length, buffer.size - length)
        if (read < 0) break
        length += read
      }
      String(buffer, 0, length).ifEmpty { null }
    }
  } catch (_: NoSuchFileException) {
    null
  }

  private fun logLatency() {
    val modified = try {
      Files.getLastModifiedTime(signalPath).toMillis()
    } catch (_: IOException) {
      return
    }
    val latency = (clock.now() - Instant.fromEpochMilliseconds(modified)).inWholeMilliseconds
    diagnostics.recordRequest("desktop_signal", mapOf("latency_ms" to latency))
    System.err.println("skill-bill-desktop signal ${latency}ms")
  }
}
