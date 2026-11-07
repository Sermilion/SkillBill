package dev.skillbill.desktop.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import dev.skillbill.desktop.core.domain.model.ChatImage
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun Modifier.pasteChatImages(
  enabled: Boolean,
  clipboard: ClipboardImageReader = SystemClipboardImageReader,
  encodeContext: CoroutineContext = Dispatchers.Default,
  onImage: (ChatImage) -> Unit,
): Modifier {
  val scope = rememberCoroutineScope()
  return onPreviewKeyEvent { event ->
    val pasted = if (enabled && event.isPasteShortcut()) clipboard.read() else null
    if (pasted == null) false else {
      scope.launch {
        val image = try {
          withContext(encodeContext) { normalizedChatImage(pasted) }
        } catch (error: IOException) {
          System.getLogger("skillbill.chat.clipboard").log(System.Logger.Level.WARNING, "Could not encode a pasted image", error)
          null
        }
        if (image != null) onImage(image)
      }
      true
    }
  }
}
