package dev.skillbill.desktop.app.tray

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class TrayPopoverPlacementTest {
  @Test
  fun `short tray card stays next to the panel inside a taller transparent window`() {
    val area = Rectangle(0, 2160, 7680, 2160)
    val anchor = Point(4601, 4286)
    val frame = IntSize(570, 885)
    val card = IntSize(540, 332)
    val position = anchoredTrayPosition(anchor, area, frame.width, frame.height, 24)
    val offset = anchoredTrayAlignment(anchor, area).align(card, frame, LayoutDirection.Ltr)
    assertEquals(anchor.y - 24, position.y + offset.y + card.height)
    assertEquals(anchor.x, position.x + offset.x + card.width / 2)
  }

  @Test
  fun `short tray card follows top and side panels`() {
    val area = Rectangle(0, 0, 1920, 1080)
    val frame = IntSize(380, 590)
    val card = IntSize(360, 220)
    listOf(Point(960, 0), Point(0, 540), Point(1920, 540)).forEach { anchor ->
      val position = anchoredTrayPosition(anchor, area, frame.width, frame.height, 16)
      val offset = anchoredTrayAlignment(anchor, area).align(card, frame, LayoutDirection.Ltr)
      when (anchor.x) {
        0 -> assertEquals(16, position.x + offset.x)
        1920 -> assertEquals(1904, position.x + offset.x + card.width)
        else -> assertEquals(16, position.y + offset.y)
      }
    }
  }

  @Test
  fun `KDE mixed scale monitor origins map native activation to the Xwayland icon position`() {
    val layout = """{"outputs":[{"enabled":true,"connected":true,"pos":{"x":0,"y":0},"size":{"width":5120,"height":1440},"scale":1},{"enabled":true,"connected":true,"pos":{"x":0,"y":1440},"size":{"width":7680,"height":2160},"scale":1.5}]}"""
    val screens = listOf(Rectangle(0, 2160, 7680, 2160), Rectangle(0, 0, 7680, 2160))
    assertEquals(Point(4488, 4284), mapKdeTrayAnchor(Point(4488, 3564), layout, screens, 1.5f))
    assertEquals(Point(4533, 4310), mapKdeTrayAnchor(Point(4533, 3590), layout, screens, 1.5f))
    assertEquals(Point(1500, 2100), mapKdeTrayAnchor(Point(1000, 1400), layout, screens, 1.5f))
    assertEquals(Point(4203, 3375), anchoredTrayPosition(Point(4488, 4284), screens.first(), 570, 885, 24))
    assertEquals(Point(4248, 3401), anchoredTrayPosition(Point(4533, 4310), screens.first(), 570, 885, 24))
  }

  @Test
  fun `bottom tray on fractional scaled second display anchors above icon and stays within right edge`() {
    val area = Rectangle(0, 2160, 7680, 2160)
    assertEquals(Point(7110, 3371), anchoredTrayPosition(Point(7550, 4280), area, 570, 885, 24))
  }

  @Test
  fun `top and side panels anchor beside icon on a display with negative coordinates`() {
    val area = Rectangle(-1920, 0, 1920, 1080)
    assertEquals(Point(-1140, 40), anchoredTrayPosition(Point(-950, 24), area, 380, 590, 16))
    assertEquals(Point(-1880, 205), anchoredTrayPosition(Point(-1896, 500), area, 380, 590, 16))
    assertEquals(Point(-420, 205), anchoredTrayPosition(Point(-24, 500), area, 380, 590, 16))
  }
}
