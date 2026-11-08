package dev.skillbill.desktop.core.data.qa

import dev.skillbill.desktop.core.domain.model.QaPreviewRequest
import dev.skillbill.desktop.core.domain.model.QaPreviewState
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QaPreviewReaderTest {
  @TempDir
  lateinit var temporary: Path

  @Test
  fun `a display with nothing drawn waits instead of showing a blank live frame`() = runBlocking {
    val invocation = temporary.toRealPath()
    val diagnostics = RecordingDiagnostics()
    val reader = QaPreviewReader(diagnostics, pause = {})
    val request = QaPreviewRequest(1, "work", "qa-1", invocation.toString(), active = true)

    writeFrame(invocation, intArrayOf(0x000000, 0x000000))
    assertEquals(QaPreviewState.Waiting(QaPreviewState.Reason.BlankDisplay), reader.observe(request).drop(1).first())
    assertEquals(listOf("qa_preview_display_blank"), diagnostics.categories)

    writeFrame(invocation, intArrayOf(0x000000, 0x112233))
    val live = assertIs<QaPreviewState.Live>(reader.observe(request).drop(1).first())
    assertContentEquals(intArrayOf(0xff000000.toInt(), 0xff112233.toInt()), live.frame.argb)
  }

  @Test
  fun `an open preview reconnects when a restarted display replaces its framebuffer`() = runBlocking {
    val invocation = temporary.toRealPath()
    val diagnostics = RecordingDiagnostics()
    val firstPixels = intArrayOf(0x000000, 0x112233)
    val replacementPixels = intArrayOf(0x445566, 0x778899)
    writeFrame(invocation, firstPixels)
    val framebuffer = invocation.resolve("live-preview/Xvfb_screen0")
    val transitions = ArrayDeque<() -> Unit>()
    transitions.add { Files.delete(framebuffer) }
    transitions.add { writeFrame(invocation, replacementPixels) }
    val reader = QaPreviewReader(diagnostics, pause = { transitions.removeFirst().invoke() })
    val request = QaPreviewRequest(1, "work", "qa-1", invocation.toString(), active = true)

    val states = reader.observe(request).take(4).toList()

    assertContentEquals(firstPixels.map { it or 0xff000000.toInt() }.toIntArray(), assertIs<QaPreviewState.Live>(states[1]).frame.argb)
    assertEquals(QaPreviewState.Waiting(QaPreviewState.Reason.SourceUnavailable), states[2])
    assertContentEquals(replacementPixels.map { it or 0xff000000.toInt() }.toIntArray(), assertIs<QaPreviewState.Live>(states[3]).frame.argb)
    assertEquals(listOf("qa_preview_source_unavailable"), diagnostics.categories)
  }

  private fun writeFrame(invocation: Path, pixels: IntArray) {
    val preview = Files.createDirectories(invocation.resolve("live-preview"))
    val width = pixels.size
    val bytes = ByteArray(HEADER_BYTES + width * 4)
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    intArrayOf(
      HEADER_BYTES, 7, 2, 24, width, 1, 0, 0, 32, 0, 32, 32, width * 4, 4,
      0x00ff0000, 0x0000ff00, 0x000000ff, 8, 256, 0, width, 1, 0, 0, 0,
    ).forEach { header.putInt(it) }
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    pixels.forEachIndexed { index, pixel -> data.putInt(HEADER_BYTES + index * 4, pixel) }
    Files.write(preview.resolve("Xvfb_screen0"), bytes)
  }

  private companion object {
    const val HEADER_BYTES = 100
  }
}
