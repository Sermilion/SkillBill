package dev.skillbill.tray.infrastructure

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import dev.nucleusframework.composenativetray.utils.TrayClickTracker
import dev.skillbill.tray.infrastructure.cli.RuntimeDiagnostics
import dev.skillbill.tray.domain.DesktopUiScaleConfiguration
import dev.skillbill.tray.infrastructure.cli.ProcessRunner
import dev.skillbill.tray.infrastructure.cli.ProcessResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.double
import kotlinx.serialization.json.boolean
import java.time.Duration
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.roundToInt

fun trayIconAnchor(): Point {
  val pointer = MouseInfo.getPointerInfo()
  val click = TrayClickTracker.lastClick()
  if (click != null) return Point(click.x, click.y)
  RuntimeDiagnostics.record("tray_icon_anchor_unavailable")
  return pointer.location
}

class TrayPopoverPlacement(
  private val layoutRunner: ProcessRunner = ProcessRunner(maxStdoutBytes = 256 * 1024, timeout = Duration.ofSeconds(2)),
) {
  suspend fun position(size: DpSize, nativeAnchor: Point, scale: DesktopUiScaleConfiguration, gap: Int): WindowPosition {
    val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration }
    val anchor = if (System.getProperty("os.name").contains("linux", true) &&
      !System.getenv("WAYLAND_DISPLAY").isNullOrBlank() && System.getenv("XDG_CURRENT_DESKTOP").orEmpty().contains("KDE", true)) {
      kdeAnchor(nativeAnchor, screens.map { it.bounds }, scale)
    } else nativeAnchor
    val screen = screens.firstOrNull { it.bounds.contains(anchor) }
      ?: MouseInfo.getPointerInfo().device.defaultConfiguration.also {
        RuntimeDiagnostics.record("tray_icon_screen_unavailable")
      }
    val bounds = screen.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(screen)
    val workArea = Rectangle(bounds.x + insets.left, bounds.y + insets.top,
      bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom)
    val position = anchoredTrayPosition(anchor, workArea, size.width.value.roundToInt(), size.height.value.roundToInt(), gap)
    System.getLogger("SkillBillTrayPlacement").log(System.Logger.Level.INFO,
      "tray_window_anchored native=$nativeAnchor anchor=$anchor work_area=$workArea position=$position")
    return WindowPosition(position.x.dp, position.y.dp)
  }

  private suspend fun kdeAnchor(raw: Point, screens: List<Rectangle>, scale: DesktopUiScaleConfiguration): Point {
    val result = layoutRunner.run(listOf("kscreen-doctor", "--json"))
    if (result !is ProcessResult.Success) {
      RuntimeDiagnostics.record("tray_kde_layout_unavailable")
      return raw
    }
    return runCatching { mapKdeTrayAnchor(raw, result.output, screens, scale.contentDensity ?: 1f) }
      .onFailure { RuntimeDiagnostics.record("tray_kde_layout_invalid") }.getOrDefault(raw)
  }
}

internal fun mapKdeTrayAnchor(raw: Point, layout: String, screens: List<Rectangle>, xwaylandScale: Float): Point {
  val output = Json.parseToJsonElement(layout).jsonObject.getValue("outputs").jsonArray
    .map { it.jsonObject }.first { output ->
      output.getValue("enabled").jsonPrimitive.boolean && output.getValue("connected").jsonPrimitive.boolean &&
        nativeKdeBounds(output).contains(raw)
    }
  val nativeBounds = nativeKdeBounds(output)
  val outputScale = output.getValue("scale").jsonPrimitive.double
  val expected = Rectangle((nativeBounds.x * xwaylandScale).roundToInt(), (nativeBounds.y * xwaylandScale).roundToInt(),
    (nativeBounds.width / outputScale * xwaylandScale).roundToInt(), (nativeBounds.height / outputScale * xwaylandScale).roundToInt())
  val screen = screens.minBy { candidate ->
    kotlin.math.abs(candidate.x - expected.x) + kotlin.math.abs(candidate.y - expected.y) +
      kotlin.math.abs(candidate.width - expected.width) + kotlin.math.abs(candidate.height - expected.height)
  }
  return mapNativeTrayPoint(raw, nativeBounds, screen)
}

private fun nativeKdeBounds(output: JsonObject): Rectangle {
  val origin = output.getValue("pos").jsonObject
  val size = output.getValue("size").jsonObject
  return Rectangle(origin.getValue("x").jsonPrimitive.int, origin.getValue("y").jsonPrimitive.int,
    size.getValue("width").jsonPrimitive.int, size.getValue("height").jsonPrimitive.int)
}

internal fun mapNativeTrayPoint(raw: Point, nativeBounds: Rectangle, awtBounds: Rectangle): Point = Point(
  awtBounds.x + ((raw.x - nativeBounds.x) * awtBounds.width.toDouble() / nativeBounds.width).roundToInt(),
  awtBounds.y + ((raw.y - nativeBounds.y) * awtBounds.height.toDouble() / nativeBounds.height).roundToInt(),
)

internal fun anchoredTrayPosition(anchor: Point, area: Rectangle, width: Int, height: Int, gap: Int): Point {
  val right = area.x + area.width
  val bottom = area.y + area.height
  val edge = listOf(anchor.y - area.y, bottom - anchor.y, anchor.x - area.x, right - anchor.x)
    .withIndex().minBy { it.value }.index
  val x = when (edge) {
    2 -> maxOf(anchor.x, area.x) + gap
    3 -> minOf(anchor.x, right) - width - gap
    else -> anchor.x - width / 2
  }
  val y = when (edge) {
    0 -> maxOf(anchor.y, area.y) + gap
    1 -> minOf(anchor.y, bottom) - height - gap
    else -> anchor.y - height / 2
  }
  return Point(x.coerceIn(area.x, maxOf(area.x, right - width)),
    y.coerceIn(area.y, maxOf(area.y, bottom - height)))
}
