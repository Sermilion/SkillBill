package dev.skillbill.tray.core.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class ExpandableReasonTest {
  @Test
  fun `clicking show more on a truncated reason opens the full text in a dialog`() = runBlocking {
    val reason = "The full audit detail must remain readable. ".repeat(40)
    ImageComposeScene(800, 600, coroutineContext = coroutineContext) {
      ExpandableReason(reason, "Audit", Modifier.width(300.dp))
    }.use { scene ->
      // The first frame measures the text; the button appears on the next one, once the overflow is known.
      scene.render().close()
      scene.render().close()
      assertFalse(scene.hasText("Audit"))
      val showMore = assertNotNull(scene.nodeWithText("Show more")).boundsInRoot.center
      scene.sendPointerEvent(PointerEventType.Press, showMore)
      scene.sendPointerEvent(PointerEventType.Release, showMore)
      scene.render().close()
      assertTrue(scene.hasText("Audit"))
      assertTrue(scene.hasText(reason))
    }
  }

  @Test
  fun `fully visible text has no show more button`() = runBlocking {
    ImageComposeScene(800, 600, coroutineContext = coroutineContext) {
      ExpandableReason("Short reason", "Audit", Modifier.width(300.dp))
    }.use { scene ->
      scene.render().close()
      scene.render().close()
      assertFalse(scene.hasText("Show more"))
    }
  }

  private fun ImageComposeScene.nodeWithText(text: String): SemanticsNode? =
    semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }.firstOrNull {
      it.config.getOrNull(SemanticsProperties.Text)?.any { found -> found.text == text } == true
    }

  private fun ImageComposeScene.hasText(text: String): Boolean = nodeWithText(text) != null
}
