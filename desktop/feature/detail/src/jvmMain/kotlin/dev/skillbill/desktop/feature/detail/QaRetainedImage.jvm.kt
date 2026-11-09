package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max

internal actual suspend fun loadRetainedImage(path: String): RetainedImage = withContext(Dispatchers.IO) {
  try {
    val file = Path.of(path)
    require(Files.size(file) <= 32L * 1024 * 1024) { "Image exceeds size limit" }
    ImageIO.createImageInputStream(file.toFile()).use { input ->
      val readers = ImageIO.getImageReaders(input)
      require(readers.hasNext()) { "Unsupported image format" }
      val reader = readers.next()
      try {
        reader.input = input
        val width = reader.getWidth(0)
        val height = reader.getHeight(0)
        require(width > 0 && height > 0 && width.toLong() * height <= 100_000_000) { "Image exceeds pixel limit" }
        val sample = ceil(max(width, height) / 1280.0).toInt().coerceAtLeast(1)
        val parameters = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
        RetainedImage.Loaded(reader.read(0, parameters).toComposeImageBitmap())
      } finally {
        reader.dispose()
      }
    }
  } catch (failure: IOException) {
    unavailableImage(failure)
  } catch (failure: IllegalArgumentException) {
    unavailableImage(failure)
  } catch (failure: SecurityException) {
    unavailableImage(failure)
  }
}

private fun unavailableImage(failure: Exception): RetainedImage {
  Logger.getLogger("desktop_qa_retained_image_unavailable").warning(failure.javaClass.simpleName)
  return RetainedImage.Unavailable
}
