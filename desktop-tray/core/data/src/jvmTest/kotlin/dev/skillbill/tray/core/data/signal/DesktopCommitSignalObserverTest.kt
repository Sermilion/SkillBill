package dev.skillbill.tray.core.data.signal

import dev.skillbill.tray.core.data.RecordingDiagnostics
import dev.skillbill.tray.core.data.port.StoreChange
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DesktopCommitSignalObserverTest {
  @Test
  fun `revision changes under a stable generation publish even when changed id lines exceed the old limit`() {
    runBlocking {
      val databasePath = createTempDirectory("tray-commit-signal").resolve("metrics.db")
      val signalPath = databasePath.resolveSibling("metrics.db.skill-bill-commit-signal")
      val hints = Channel<StoreWatchHint>(Channel.UNLIMITED)
      val diagnostics = RecordingDiagnostics()
      signalPath.writeSignal("stable", goals = 1, changedLines = emptyList())
      val observer = DesktopCommitSignalObserver(databasePath, diagnostics) { ChannelStoreWatch(hints) }
      val received = Channel<StoreChange>(Channel.UNLIMITED)
      val collector = launch { observer.changes().collect(received::send) }
      withTimeout(10.seconds) {
        received.receive()

        val longIds = (1..16).joinToString(",") { index -> "wftr-changed-work-item-$index" }
        signalPath.writeSignal("stable", goals = 2, listOf("goals.changed=1:$longIds", "phases.changed=0:$longIds"))
        hints.send(StoreWatchHint.COMMIT)
        assertEquals(
          StoreChange(resynchronize = false, revisions = mapOf("goals" to 2L, "phases" to 2L, "activity" to 0L)),
          received.receive(),
        )

        signalPath.writeSignal("migrated", goals = 2, changedLines = emptyList())
        hints.send(StoreWatchHint.COMMIT)
        assertTrue(received.receive().resynchronize)
      }
      collector.cancel()
      assertTrue("commit_signal_payload_invalid" !in diagnostics.categories)
    }
  }

  private fun Path.writeSignal(
    generation: String,
    goals: Long,
    changedLines: List<String>,
  ) {
    val header = listOf("generation=$generation", "goals=$goals", "phases=$goals", "activity=0", "specs=0")
    writeText((header + changedLines).joinToString("\n", postfix = "\n"))
  }

  private class ChannelStoreWatch(private val hints: Channel<StoreWatchHint>) : StoreWatch {
    override suspend fun awaitHint(): StoreWatchHint = hints.receive()

    override fun close() = Unit
  }
}
