package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.detail.PeekOutput
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons

internal sealed interface RetainedImage {
  data object Loading : RetainedImage
  data class Loaded(val bitmap: ImageBitmap) : RetainedImage
  data object Unavailable : RetainedImage
}

internal expect suspend fun loadRetainedImage(path: String): RetainedImage

@Composable
internal fun QaRetainedImage(path: String, label: String, onOpen: () -> Unit) {
  val image by key(path) {
    produceState<RetainedImage>(RetainedImage.Loading, path) { value = loadRetainedImage(path) }
  }
  when (val current = image) {
    RetainedImage.Loading -> HistoryText("Loading image...")
    RetainedImage.Unavailable -> HistoryText("This image is unavailable. Open the retained file to inspect it.")
    is RetainedImage.Loaded -> Image(
      current.bitmap, label, Modifier.fillMaxWidth().heightIn(max = 240.dp), contentScale = ContentScale.Fit,
    )
  }
  PeekOutput(LucideIcons.external, label, onClick = onOpen, external = true)
}
