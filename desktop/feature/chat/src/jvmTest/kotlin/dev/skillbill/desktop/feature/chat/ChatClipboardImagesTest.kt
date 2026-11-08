package dev.skillbill.desktop.feature.chat

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import java.awt.Color
import java.awt.Image
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import javax.swing.ImageIcon
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatClipboardImagesTest {
  @OptIn(InternalComposeUiApi::class)
  @Test
  fun `macOS pastes with Command and Windows and Linux paste with Control only on key down`() {
    val command = KeyEvent(Key.V, KeyEventType.KeyDown, isMetaPressed = true)
    val control = KeyEvent(Key.V, KeyEventType.KeyDown, isCtrlPressed = true)

    assertTrue(command.isPasteShortcut(usesMeta = true))
    assertFalse(control.isPasteShortcut(usesMeta = true))
    assertTrue(control.isPasteShortcut(usesMeta = false))
    assertFalse(command.isPasteShortcut(usesMeta = false))
    for (usesMeta in listOf(true, false)) {
      assertFalse(KeyEvent(Key.V, KeyEventType.KeyUp, isMetaPressed = true, isCtrlPressed = true).isPasteShortcut(usesMeta))
      assertFalse(KeyEvent(Key.C, KeyEventType.KeyDown, isMetaPressed = true, isCtrlPressed = true).isPasteShortcut(usesMeta))
      assertFalse(KeyEvent(Key.V, KeyEventType.KeyDown).isPasteShortcut(usesMeta))
    }
  }

  @Test
  fun `clipboard screenshots keep pixels for AWT images and the largest Retina resolution`() {
    val screenshot = BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, Color.RED.rgb) }
    val toolkitImage = screenshot.getScaledInstance(8, 6, Image.SCALE_DEFAULT)
    ImageIcon(toolkitImage)
    assertFalse(toolkitImage is BufferedImage)
    val retinaImage = BaseMultiResolutionImage(BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB), screenshot)

    for (image in listOf(screenshot, toolkitImage, retinaImage)) {
      val clipboard = Clipboard("screenshot").apply { setContents(ImageTransferable(image), null) }
      val decoded = assertNotNull(AwtClipboardImageReader { clipboard }.read())
      assertEquals(8, decoded.width)
      assertEquals(6, decoded.height)
      assertEquals(Color.RED.rgb, decoded.getRGB(0, 0))
      val attachment = normalizedChatImage(decoded)
      assertEquals(Color.RED.rgb, assertNotNull(ImageIO.read(attachment.bytes.inputStream())).getRGB(0, 0))
    }
  }

  @Test
  fun `copied text falls through even when the clipboard also offers an image`() {
    val clipboard = Clipboard("text")
    val reader = AwtClipboardImageReader { clipboard }
    clipboard.setContents(StringSelection("copied text"), null)
    assertNull(reader.read())

    clipboard.setContents(ImageTransferable(BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB), text = "copied text"), null)
    assertNull(reader.read())
    assertEquals("copied text", clipboard.getData(DataFlavor.stringFlavor))
  }

  @Test
  fun `a wide image is scaled to the 1568 px edge limit and encoded as PNG`() {
    val image = normalizedChatImage(BufferedImage(4000, 1000, BufferedImage.TYPE_INT_ARGB))

    assertEquals("image/png", image.mediaType)
    val decoded = assertNotNull(ImageIO.read(image.bytes.inputStream()))
    assertEquals(1568, decoded.width)
    assertEquals(392, decoded.height)
  }
}

private class ImageTransferable(private val image: Image, private val text: String? = null) : Transferable {
  override fun getTransferDataFlavors(): Array<DataFlavor> =
    if (text == null) arrayOf(DataFlavor.imageFlavor) else arrayOf(DataFlavor.imageFlavor, DataFlavor.stringFlavor)

  override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in transferDataFlavors

  override fun getTransferData(flavor: DataFlavor): Any = when {
    flavor == DataFlavor.imageFlavor -> image
    flavor == DataFlavor.stringFlavor && text != null -> text
    else -> throw UnsupportedFlavorException(flavor)
  }
}
