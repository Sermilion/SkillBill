package dev.skillbill.tray.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class ReasonTooltipTest {
  @Test
  fun `hovering a truncated reason renders the full scrollable popup without an infinite size`() = runBlocking {
    val reason = "The full failure reason must remain readable. ".repeat(40)
    ImageComposeScene(800, 600, coroutineContext = coroutineContext) {
      ReasonTooltip(reason, Modifier.width(300.dp))
    }.use { scene ->
      scene.render().close()
      scene.sendPointerEvent(PointerEventType.Move, Offset(10f, 10f))
      delay(1000)
      scene.render().close()
      assertFalse(scene.hasFullReasonPopup())
      delay(1100)
      scene.render().close()
      assertTrue(scene.hasFullReasonPopup())
      assertTrue(scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }.any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == reason } == true
      })
    }
  }

  @Test
  fun `hovering fully visible text does not open a popup`() = runBlocking {
    ImageComposeScene(800, 600, coroutineContext = coroutineContext) {
      ReasonTooltip("Short reason", Modifier.width(300.dp))
    }.use { scene ->
      scene.render().close()
      scene.sendPointerEvent(PointerEventType.Move, Offset(10f, 10f))
      delay(2100)
      scene.render().close()
      assertFalse(scene.hasFullReasonPopup())
    }
  }

  private fun ImageComposeScene.hasFullReasonPopup(): Boolean =
    semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }.any {
      it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Full reason" } == true
    }
}
