package dev.skillbill.desktop.core.data.qa

import dev.skillbill.desktop.core.domain.model.QaPreviewState
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView

internal class QaPreviewSource(
  private val openDirectory: (Path) -> DirectoryStream<Path> = { Files.newDirectoryStream(it) },
) {
  fun read(invocation: Path): XwdDecodeResult {
    val directories = invocation.resolve(PREVIEW_DIRECTORY).iterator()
    return openDirectory(invocation.root).use { root ->
      if (root !is SecureDirectoryStream<Path>) return rejected(QaPreviewState.Reason.InvalidSource)
      readDirectory(root, directories, invocation.fileSystem.getPath(SOURCE_FILE))
    }
  }

  private fun readDirectory(
    parent: SecureDirectoryStream<Path>,
    directories: Iterator<Path>,
    source: Path,
  ): XwdDecodeResult {
    if (!directories.hasNext()) return readFrame(parent, source)
    val name = directories.next()
    val attributes = parent.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
    if (attributes.isSymbolicLink || !attributes.isDirectory) return rejected(QaPreviewState.Reason.InvalidSource)
    return parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { child ->
      readDirectory(child, directories, source)
    }
  }

  private fun readFrame(parent: SecureDirectoryStream<Path>, source: Path): XwdDecodeResult {
    val attributes = parent.getFileAttributeView(source, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
    if (attributes.isSymbolicLink || !attributes.isRegularFile) return rejected(QaPreviewState.Reason.InvalidSource)
    return parent.newByteChannel(source, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
      val size = channel.size()
      if (size > MAX_SNAPSHOT_BYTES) return rejected(QaPreviewState.Reason.LimitsExceeded)
      val bytes = ByteArray(size.toInt())
      val buffer = ByteBuffer.wrap(bytes)
      while (buffer.hasRemaining()) {
        if (Thread.currentThread().isInterrupted) throw CancellationException("QA preview reading was cancelled")
        if (channel.read(buffer) < 0) break
      }
      if (channel.size() > MAX_SNAPSHOT_BYTES) return rejected(QaPreviewState.Reason.LimitsExceeded)
      XwdFrameDecoder.decode(bytes, buffer.position())
    }
  }

  private fun rejected(reason: QaPreviewState.Reason) = XwdDecodeResult.Rejected(reason)

  private companion object {
    const val PREVIEW_DIRECTORY = "live-preview"
    const val SOURCE_FILE = "Xvfb_screen0"
    const val MAX_SNAPSHOT_BYTES = 33_554_432L
  }
}
