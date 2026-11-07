package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.skillbill.desktop.core.domain.model.QaPreviewFrame
import java.awt.image.BufferedImage

internal actual fun QaPreviewFrame.toImageBitmap(): ImageBitmap {
  val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
  image.setRGB(0, 0, width, height, argb, 0, width)
  return image.toComposeImageBitmap()
}
