package dev.skillbill.desktop.core.data.qa

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.FileAttribute
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QaPreviewSourceTest {
  @TempDir
  lateinit var temporary: Path

  @Test
  fun `replacing the invocation path cannot redirect an open preview to another run`() {
    val root = temporary.toRealPath()
    assumeTrue(Files.newDirectoryStream(root).use { it is SecureDirectoryStream<Path> })
    val invocation = Files.createDirectory(root.resolve("selected"))
    val other = Files.createDirectory(root.resolve("other"))
    writeFrame(invocation, 0x112233)
    writeFrame(other, 0x445566)
    val handles = Handles()
    var replaced = false
    val source = QaPreviewSource { path ->
      val stream = Files.newDirectoryStream(path)
      check(stream is SecureDirectoryStream<Path>)
      TrackedDirectory(stream, handles) { name ->
        if (name == invocation.fileName && !replaced) {
          Files.move(invocation, root.resolve("retained"))
          Files.createSymbolicLink(invocation, other)
          replaced = true
        }
      }
    }

    val frame = assertIs<XwdDecodeResult.Frame>(source.read(invocation)).value

    assertTrue(replaced)
    assertContentEquals(intArrayOf(0xff112233.toInt()), frame.argb)
    assertEquals(0, handles.directories)
    assertEquals(0, handles.channels)
  }

  private fun writeFrame(invocation: Path, pixel: Int) {
    val preview = Files.createDirectory(invocation.resolve("live-preview"))
    val bytes = ByteArray(105)
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    intArrayOf(
      101, 7, 2, 24, 1, 1, 0, 0, 32, 0, 32, 32, 4, 4,
      0x00ff0000, 0x0000ff00, 0x000000ff, 8, 256, 0, 1, 1, 0, 0, 0,
    ).forEach { header.putInt(it) }
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(101, pixel)
    Files.write(preview.resolve("Xvfb_screen0"), bytes)
  }

  private class Handles(var directories: Int = 0, var channels: Int = 0)

  private class TrackedDirectory(
    private val delegate: SecureDirectoryStream<Path>,
    private val handles: Handles,
    private val afterOpen: (Path) -> Unit,
  ) : SecureDirectoryStream<Path> by delegate {
    init {
      handles.directories++
    }

    override fun newDirectoryStream(path: Path, vararg options: LinkOption): SecureDirectoryStream<Path> {
      val child = TrackedDirectory(delegate.newDirectoryStream(path, *options), handles, afterOpen)
      try {
        afterOpen(path)
      } catch (failure: Throwable) {
        child.close()
        throw failure
      }
      return child
    }

    override fun newByteChannel(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel {
      val channel = delegate.newByteChannel(path, options, *attrs)
      handles.channels++
      return object : SeekableByteChannel by channel {
        override fun close() {
          try {
            channel.close()
          } finally {
            handles.channels--
          }
        }
      }
    }

    override fun close() {
      try {
        delegate.close()
      } finally {
        handles.directories--
      }
    }
  }
}
