package dev.skillbill.desktop.app.tray

import dev.skillbill.desktop.app.tray.macos.MacStatusItemBridge
import dev.skillbill.desktop.app.tray.macos.MacStatusItemOperations
import dev.skillbill.desktop.core.domain.model.TrayStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

class MacStatusItemBridgeTest {
  @Test
  fun `owned image reaches native update after pruning and failed update disposes callbacks`(@TempDir root: Path) {
    val files = TrayIconFiles { root }
    val image = byteArrayOf(4, 8, 15, 16, 23, 42)
    val rendered = TrayIconRenderResult(files.write(image, image), image)
    repeat(3) { index -> files.write(byteArrayOf(index.toByte(), 2), byteArrayOf(index.toByte(), 3)) }
    assertFalse(Files.exists(Path.of(rendered.paths.png)))
    var consumed = byteArrayOf()
    var available = 0
    var failed = 0
    var actions = 0
    var updateResult = true
    var disposed = 0
    val prior = MacStatusItemBridge.operations
    MacStatusItemBridge.operations = object : MacStatusItemOperations {
      override fun install(imagePng: ByteArray): Boolean {
        consumed = imagePng.copyOf()
        return true
      }
      override fun update(imagePng: ByteArray, kind: String, count: Int, goals: Array<String>, phases: Array<String>): Boolean {
        consumed = imagePng.copyOf()
        return updateResult
      }
      override fun dispose() { disposed++ }
    }
    try {
      val savedCallback = { MacStatusItemBridge.onMenuAction(0) }
      MacStatusItemBridge.install(rendered.imagePng, { actions++ }, { actions++ }, { available++ }, { failed++ }, {})
      assertEquals(1, available)
      assertContentEquals(byteArrayOf(4, 8, 15, 16, 23, 42), consumed)
      MacStatusItemBridge.onMenuAction(0)
      assertEquals(1, actions)
      updateResult = false
      assertFalse(MacStatusItemBridge.update(rendered.imagePng, TrayStatus.Running, 3, emptyList(), emptyList()))
      assertFalse(Files.exists(Path.of(rendered.paths.png)))
      assertContentEquals(byteArrayOf(4, 8, 15, 16, 23, 42), consumed)
      savedCallback()
      assertEquals(1, actions)
      MacStatusItemBridge.dispose()
      savedCallback()
      assertEquals(1, failed)
      assertEquals(1, actions)
      assertEquals(2, disposed)
    } finally {
      MacStatusItemBridge.dispose()
      MacStatusItemBridge.operations = prior
      files.close()
    }
  }

  @Test
  fun `failed image install stays unavailable and releases native ownership`() {
    var available = 0
    var failed = 0
    var disposed = 0
    val prior = MacStatusItemBridge.operations
    MacStatusItemBridge.operations = object : MacStatusItemOperations {
      override fun install(imagePng: ByteArray) = false
      override fun update(imagePng: ByteArray, kind: String, count: Int, goals: Array<String>, phases: Array<String>) = true
      override fun dispose() { disposed++ }
    }
    try {
      MacStatusItemBridge.install(byteArrayOf(), {}, {}, { available++ }, { failed++ }, {})
      assertEquals(0, available)
      assertEquals(1, failed)
      assertEquals(1, disposed)
    } finally {
      MacStatusItemBridge.dispose()
      MacStatusItemBridge.operations = prior
    }
  }
}
