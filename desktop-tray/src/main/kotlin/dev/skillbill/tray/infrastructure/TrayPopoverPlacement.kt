package dev.skillbill.tray.infrastructure

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import dev.nucleusframework.composenativetray.utils.TrayClickTracker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.double
import kotlinx.serialization.json.boolean
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val KDE_LAYOUT_QUERY_TIMEOUT_MILLIS = 1_000L
private const val KDE_LAYOUT_OUTPUT_TIMEOUT_MILLIS = 250L

fun trayIconAnchor(): Point {
  val pointer = MouseInfo.getPointerInfo()
  val click = TrayClickTracker.lastClick()
  if (click != null) return Point(click.x, click.y)
  RuntimeDiagnostics.record("tray_icon_anchor_unavailable")
  return pointer.location
}

class TrayPopoverPlacement {
  fun position(size: DpSize, nativeAnchor: Point, gap: Int): WindowPosition {
    val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration }
    val anchor = awtTrayAnchor(nativeAnchor, screens.map { it.bounds }, System.getenv())
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
}

private fun awtTrayAnchor(raw: Point, screens: List<Rectangle>, environment: Map<String, String>): Point {
  val desktop = listOfNotNull(environment["XDG_CURRENT_DESKTOP"], environment["DESKTOP_SESSION"])
    .joinToString(" ").lowercase()
  if (!environment["XDG_SESSION_TYPE"].equals("wayland", ignoreCase = true) ||
    ("kde" !in desktop && "plasma" !in desktop)) return raw
  val layout = queryKdeDisplayLayout() ?: return raw
  val configHome = environment["XDG_CONFIG_HOME"]?.takeIf(String::isNotBlank)?.let(Path::of)
    ?: Paths.get(System.getProperty("user.home"), ".config")
  val xwaylandScale = readKwinXwaylandScale(configHome.resolve("kwinrc"))
  if (xwaylandScale == null) {
    RuntimeDiagnostics.record("tray_kde_xwayland_scale_unavailable")
    return raw
  }
  return try {
    mapKdeTrayAnchor(raw, layout, screens, xwaylandScale)
  } catch (_: Exception) {
    RuntimeDiagnostics.record("tray_kde_anchor_mapping_failed")
    raw
  }
}

private fun queryKdeDisplayLayout(): String? {
  val process = try {
    ProcessBuilder("kscreen-doctor", "-j").redirectError(ProcessBuilder.Redirect.DISCARD).start()
  } catch (_: Exception) {
    RuntimeDiagnostics.record("tray_kde_layout_query_failed")
    return null
  }
  val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
  return try {
    if (!process.waitFor(KDE_LAYOUT_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
      RuntimeDiagnostics.record("tray_kde_layout_query_timeout")
      null
    } else if (process.exitValue() != 0) {
      RuntimeDiagnostics.record("tray_kde_layout_query_failed")
      null
    } else {
      output.get(KDE_LAYOUT_OUTPUT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).takeIf(String::isNotBlank)
        ?: run { RuntimeDiagnostics.record("tray_kde_layout_query_failed"); null }
    }
  } catch (failure: InterruptedException) {
    Thread.currentThread().interrupt()
    RuntimeDiagnostics.record("tray_kde_layout_query_interrupted")
    null
  } catch (_: Exception) {
    RuntimeDiagnostics.record("tray_kde_layout_query_failed")
    null
  } finally {
    process.destroyForcibly()
    output.cancel(true)
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
