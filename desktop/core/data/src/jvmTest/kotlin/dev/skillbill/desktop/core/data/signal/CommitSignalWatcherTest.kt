package dev.skillbill.desktop.core.data.signal

import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

// macOS's JDK WatchService polls internally, so the hang guard is generous.
private const val HANG_GUARD_MS = 30_000L

class CommitSignalWatcherTest {
  @TempDir
  lateinit var directory: Path

  @Test
  fun `an atomic replace publishes a new snapshot and a generation change resynchronizes`() = runBlocking {
    val database = directory.resolve("review-metrics.db")
    val signal = directory.resolve("review-metrics.db.skill-bill-commit-signal")
    coroutineScope {
      val watcher = CommitSignalWatcher(this, database, RecordingDiagnostics(), DiagnosticsSwitch(false), Clock.System, ::NativeSignalWatch)
      val job = watcher.start()
      withTimeout(HANG_GUARD_MS) {
        replace(signal, "g1", revision = 1)
        watcher.snapshots.first { it.generation == "g1" }
        replace(signal, "g2", revision = 2)
        val next = watcher.snapshots.first { it.generation == "g2" }
        assertTrue(next.resynchronize)
      }
      job.cancelAndJoin()
    }
  }

  private fun replace(signal: Path, generation: String, revision: Long) {
    val staged = Files.createTempFile(signal.parent, "signal", ".tmp")
    Files.writeString(staged, "generation=$generation\ngoals=$revision\nphases=$revision\nactivity=$revision\nspecs=$revision\n")
    Files.move(staged, signal, ATOMIC_MOVE, REPLACE_EXISTING)
  }
}
