package dev.skillbill.desktop.app.onboarding

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

class TourFrameLayoutTest {
  @Test
  fun `minimum window reserves the compact caption below the staged frame`() {
    val layout = tourFrameLayout(560.dp, 420.dp)

    assertTrue(layout.compact)
    assertTrue(TOP_BAR_HEIGHT + FRAME_HEIGHT * layout.scale + layout.captionReserve <= 420.dp)
  }
}
