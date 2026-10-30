package dev.skillbill.desktop.app.tray

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import dev.skillbill.desktop.app.tray.macos.MacStatusItemBridge

internal data class MacPopoverPlacement(val position: WindowPosition, val size: DpSize)

internal fun MacStatusItemBridge.StatusItemAnchor.placement(density: Double): MacPopoverPlacement {
  val scale = backingScale / density
  val left = screenX * scale
  val top = screenY * scale
  val right = (screenX + screenWidth) * scale
  val bottom = (screenY + screenHeight) * scale
  val width = minOf(380.0, (right - left).coerceAtLeast(0.0))
  val height = minOf(590.0, (bottom - top).coerceAtLeast(0.0))
  val x = ((iconX + iconWidth / 2) * scale - width / 2).coerceIn(left, (right - width).coerceAtLeast(left))
  val y = ((iconY + iconHeight) * scale).coerceIn(top, (bottom - height).coerceAtLeast(top))
  return MacPopoverPlacement(WindowPosition(x.dp, y.dp), DpSize(width.dp, height.dp))
}
