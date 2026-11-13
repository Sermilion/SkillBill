package dev.skillbill.desktop.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ShadowBackdropTest {
  @Test
  fun `the backdrop preserves the outer shadow without painting the covered interior`() = runComposeUiTest {
    val outer = mutableStateOf(true)
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        val shape = RoundedCornerShape(12.dp)
        val shadows = listOf(Shadow(radius = 12.dp, color = Color.Black, offset = DpOffset(0.dp, 4.dp)))
        Box(Modifier.size(160.dp).background(Color.White).testTag("canvas"), contentAlignment = Alignment.Center) {
          if (outer.value) ShadowBackdrop(shadows, shape, Modifier.size(80.dp))
          else Box(Modifier.size(80.dp).layeredShadow(shadows, shape))
        }
      }
    }
    val clipped = onNodeWithTag("canvas").captureToImage().toPixelMap()
    runOnIdle { outer.value = false }
    val full = onNodeWithTag("canvas").captureToImage().toPixelMap()
    assertEquals(Color.White, clipped[80, 80])
    assertTrue(full[80, 80].red < 0.1f)
    assertTrue(clipped[80, 124].red < 1f)
    listOf(35 to 80, 124 to 80, 80 to 35, 80 to 124, 38 to 38).forEach { (x, y) ->
      assertEquals(full[x, y], clipped[x, y], "Shadow at $x,$y")
    }
  }
}
