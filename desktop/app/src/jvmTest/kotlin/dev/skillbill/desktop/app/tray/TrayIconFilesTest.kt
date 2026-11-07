package dev.skillbill.desktop.app.tray

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class TrayIconFilesTest {
  @Test
  fun `repeated writes of different icons leave the current and previous icon only`(@TempDir root: Path) {
    val directory = root.resolve("tray-icons")
    val files = TrayIconFiles { directory }
    val paths = (1..5).map { files.write(byteArrayOf(it.toByte()), byteArrayOf(it.toByte())) }
    val expected = paths.takeLast(2).flatMap { listOf(it.png, it.ico) }.toSet()
    assertEquals(expected, Files.list(directory).use { entries -> entries.map(Path::toString).toList() }.toSet())
  }

  @Test
  fun `identical content keeps the same paths`(@TempDir root: Path) {
    val files = TrayIconFiles { root }
    val bytes = byteArrayOf(1, 2, 3)
    assertEquals(files.write(bytes, bytes), files.write(bytes, bytes))
  }

  @Test
  fun `one tray does not delete another tray's icons`(@TempDir root: Path) {
    val first = TrayIconFiles { root }
    val second = TrayIconFiles { root }
    val firstPaths = first.write(byteArrayOf(1), byteArrayOf(1))
    (2..4).forEach { second.write(byteArrayOf(it.toByte()), byteArrayOf(it.toByte())) }
    assertTrue(Files.exists(Path.of(firstPaths.png)))
    assertTrue(Files.exists(Path.of(firstPaths.ico)))
    second.close()
    assertTrue(Files.exists(Path.of(firstPaths.png)))
    assertEquals(setOf(firstPaths.png, firstPaths.ico), Files.list(root).use { entries -> entries.map(Path::toString).toList() }.toSet())
  }
}
