package dev.skillbill.desktop.feature.chat

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.skillbill.desktop.core.domain.model.ChatImage
import java.awt.Color
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val MAX_IMAGE_EDGE = 1568
internal const val MAX_PNG_BYTES = 3_750_000
internal const val JPEG_QUALITY = 0.85f

internal val pasteUsesMeta: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

/** Cmd+V on macOS, Ctrl+V elsewhere. */
internal fun KeyEvent.isPasteShortcut(usesMeta: Boolean = pasteUsesMeta): Boolean =
  type == KeyEventType.KeyDown && key == Key.V && (if (usesMeta) isMetaPressed else isCtrlPressed)

/** Reads an image off the clipboard; null when the clipboard holds no image or cannot be read. */
internal fun interface ClipboardImageReader {
  fun read(): BufferedImage?
}

/** The system clipboard. Text copied from a page often carries an image flavor too, so an image counts only when no text does. */
internal object SystemClipboardImageReader : ClipboardImageReader by AwtClipboardImageReader()

internal class AwtClipboardImageReader(
  private val clipboard: () -> Clipboard = { Toolkit.getDefaultToolkit().systemClipboard },
) : ClipboardImageReader {
  override fun read(): BufferedImage? = try {
    val clipboard = clipboard()
    if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) || clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
      null
    } else {
      (clipboard.getData(DataFlavor.imageFlavor) as? Image)?.let(::toBufferedImage)
    }
  } catch (_: IllegalStateException) {
    null
  } catch (_: IOException) {
    null
  } catch (_: UnsupportedFlavorException) {
    null
  }
}

private fun toBufferedImage(image: Image): BufferedImage? {
  if (image is BufferedImage) return image
  val source = if (image is MultiResolutionImage) {
    image.resolutionVariants.maxByOrNull { it.getWidth(null) * it.getHeight(null) } ?: image
  } else {
    image
  }
  val width = source.getWidth(null)
  val height = source.getHeight(null)
  if (width <= 0 || height <= 0) return null
  return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { target ->
    val graphics = target.createGraphics()
    try {
      graphics.drawImage(source, 0, 0, null)
    } finally {
      graphics.dispose()
    }
  }
}

/**
 * Scales [image] so its longest edge is at most [MAX_IMAGE_EDGE] and encodes it as PNG, or as JPEG on white when the PNG is
 * over [MAX_PNG_BYTES]. Throws [IOException] when no encoder accepts it.
 */
internal fun normalizedChatImage(image: BufferedImage): ChatImage {
  val scale = min(1.0, MAX_IMAGE_EDGE.toDouble() / max(image.width, image.height))
  val width = max(1, (image.width * scale).roundToInt())
  val height = max(1, (image.height * scale).roundToInt())
  val png = ByteArrayOutputStream()
  if (!ImageIO.write(redrawn(image, width, height, BufferedImage.TYPE_INT_ARGB, white = false), "png", png)) {
    throw IOException("No PNG encoder for the pasted image")
  }
  if (png.size() <= MAX_PNG_BYTES) return ChatImage(png.toByteArray(), "image/png")
  return ChatImage(jpegBytes(redrawn(image, width, height, BufferedImage.TYPE_INT_RGB, white = true)), "image/jpeg")
}

private fun redrawn(image: BufferedImage, width: Int, height: Int, type: Int, white: Boolean): BufferedImage =
  BufferedImage(width, height, type).also { target ->
    val graphics = target.createGraphics()
    try {
      if (white) {
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
      }
      if (width != image.width || height != image.height) {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      }
      graphics.drawImage(image, 0, 0, width, height, null)
    } finally {
      graphics.dispose()
    }
  }

private fun jpegBytes(image: BufferedImage): ByteArray {
  val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: throw IOException("No JPEG encoder")
  try {
    val params = writer.defaultWriteParam.apply {
      compressionMode = ImageWriteParam.MODE_EXPLICIT
      compressionQuality = JPEG_QUALITY
    }
    val out = ByteArrayOutputStream()
    ImageIO.createImageOutputStream(out).use { stream ->
      writer.output = stream
      writer.write(null, IIOImage(image, null, null), params)
    }
    return out.toByteArray()
  } finally {
    writer.dispose()
  }
}
