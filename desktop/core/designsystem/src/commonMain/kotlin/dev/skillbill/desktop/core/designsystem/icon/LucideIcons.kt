package dev.skillbill.desktop.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Lucide-derived paths are licensed under the ISC terms in composeResources/files/LICENSE-lucide.txt. */
object LucideIcons {
  val search: ImageVector by lazy {
    imageVector("search") {
      circle(11.0f, 11.0f, 8.0f)
      path("m21 21-4.3-4.3")
    }
  }
  val inbox: ImageVector by lazy {
    imageVector("inbox") {
      poly("22 12 16 12 14 15 10 15 8 12 2 12", false)
      path("M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z")
    }
  }
  val target: ImageVector by lazy {
    imageVector("target") {
      circle(12.0f, 12.0f, 10.0f)
      circle(12.0f, 12.0f, 6.0f)
      circle(12.0f, 12.0f, 2.0f)
    }
  }
  val workflow: ImageVector by lazy {
    imageVector("workflow") {
      rect(3.0f, 3.0f, 8.0f, 8.0f, 2.0f)
      path("M7 11v4a2 2 0 0 0 2 2h4")
      rect(13.0f, 13.0f, 8.0f, 8.0f, 2.0f)
    }
  }
  val file: ImageVector by lazy {
    imageVector("file") {
      path("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z")
      path("M14 2v4a2 2 0 0 0 2 2h4")
    }
  }
  val fileText: ImageVector by lazy {
    imageVector("fileText") {
      path("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z")
      path("M14 2v4a2 2 0 0 0 2 2h4")
      path("M10 9H8")
      path("M16 13H8")
      path("M16 17H8")
    }
  }
  val folder: ImageVector by lazy {
    imageVector("folder") {
      path("M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z")
    }
  }
  val history: ImageVector by lazy {
    imageVector("history") {
      path("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8")
      path("M3 3v5h5")
      path("M12 7v5l4 2")
    }
  }
  val sliders: ImageVector by lazy {
    imageVector("sliders") {
      path("M20 7h-9")
      path("M14 17H5")
      circle(17.0f, 17.0f, 3.0f)
      circle(7.0f, 7.0f, 3.0f)
    }
  }
  val plus: ImageVector by lazy {
    imageVector("plus") {
      path("M5 12h14")
      path("M12 5v14")
    }
  }
  val compose: ImageVector by lazy {
    imageVector("compose") {
      path("M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7")
      path("M18.375 2.625a2.121 2.121 0 1 1 3 3L12 15l-4 1 1-4Z")
    }
  }
  val chevronDown: ImageVector by lazy {
    imageVector("chevronDown") {
      path("m6 9 6 6 6-6")
    }
  }
  val chevronRight: ImageVector by lazy {
    imageVector("chevronRight") {
      path("m9 18 6-6-6-6")
    }
  }
  val chevronLeft: ImageVector by lazy {
    imageVector("chevronLeft") {
      path("m15 18-6-6 6-6")
    }
  }
  val chevronUp: ImageVector by lazy {
    imageVector("chevronUp") {
      path("m18 15-6-6-6 6")
    }
  }
  val filter: ImageVector by lazy {
    imageVector("filter") {
      path("M3 6h18")
      path("M7 12h10")
      path("M10 18h4")
    }
  }
  val display: ImageVector by lazy {
    imageVector("display") {
      line(21.0f, 4.0f, 14.0f, 4.0f)
      line(10.0f, 4.0f, 3.0f, 4.0f)
      line(21.0f, 12.0f, 12.0f, 12.0f)
      line(8.0f, 12.0f, 3.0f, 12.0f)
      line(21.0f, 20.0f, 16.0f, 20.0f)
      line(12.0f, 20.0f, 3.0f, 20.0f)
      line(14.0f, 2.0f, 14.0f, 6.0f)
      line(8.0f, 10.0f, 8.0f, 14.0f)
      line(16.0f, 18.0f, 16.0f, 22.0f)
    }
  }
  val more: ImageVector by lazy {
    imageVector("more") {
      circle(12.0f, 12.0f, 1.0f)
      circle(19.0f, 12.0f, 1.0f)
      circle(5.0f, 12.0f, 1.0f)
    }
  }
  val x: ImageVector by lazy {
    imageVector("x") {
      path("M18 6 6 18")
      path("m6 6 12 12")
    }
  }
  val check: ImageVector by lazy {
    imageVector("check") {
      path("M20 6 9 17l-5-5")
    }
  }
  val copy: ImageVector by lazy {
    imageVector("copy") {
      rect(8.0f, 8.0f, 14.0f, 14.0f, 2.0f)
      path("M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")
    }
  }
  val terminal: ImageVector by lazy {
    imageVector("terminal") {
      poly("4 17 10 11 4 5", false)
      line(12.0f, 19.0f, 20.0f, 19.0f)
    }
  }
  val pause: ImageVector by lazy {
    imageVector("pause") {
      rect(14.0f, 4.0f, 4.0f, 16.0f, 1.0f)
      rect(6.0f, 4.0f, 4.0f, 16.0f, 1.0f)
    }
  }
  val stop: ImageVector by lazy {
    imageVector("stop") {
      rect(4.0f, 4.0f, 16.0f, 16.0f, 2.0f)
    }
  }
  val play: ImageVector by lazy {
    imageVector("play") {
      poly("6 3 20 12 6 21 6 3", true)
    }
  }
  val rerun: ImageVector by lazy {
    imageVector("rerun") {
      path("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8")
      path("M3 3v5h5")
    }
  }
  val trash: ImageVector by lazy {
    imageVector("trash") {
      path("M3 6h18")
      path("M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6")
      path("M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2")
    }
  }
  val branch: ImageVector by lazy {
    imageVector("branch") {
      line(6.0f, 3.0f, 6.0f, 15.0f)
      circle(18.0f, 6.0f, 3.0f)
      circle(6.0f, 18.0f, 3.0f)
      path("M18 9a9 9 0 0 1-9 9")
    }
  }
  val commit: ImageVector by lazy {
    imageVector("commit") {
      circle(12.0f, 12.0f, 3.0f)
      line(3.0f, 12.0f, 9.0f, 12.0f)
      line(15.0f, 12.0f, 21.0f, 12.0f)
    }
  }
  val cpu: ImageVector by lazy {
    imageVector("cpu") {
      rect(4.0f, 4.0f, 16.0f, 16.0f, 2.0f)
      rect(9.0f, 9.0f, 6.0f, 6.0f, 1.0f)
      path("M15 2v2M15 20v2M2 15h2M2 9h2M20 15h2M20 9h2M9 2v2M9 20v2")
    }
  }
  val clock: ImageVector by lazy {
    imageVector("clock") {
      circle(12.0f, 12.0f, 10.0f)
      poly("12 6 12 12 16 14", false)
    }
  }
  val bell: ImageVector by lazy {
    imageVector("bell") {
      path("M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9")
      path("M10.3 21a1.94 1.94 0 0 0 3.4 0")
    }
  }
  val external: ImageVector by lazy {
    imageVector("external") {
      path("M15 3h6v6")
      path("M10 14 21 3")
      path("M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6")
    }
  }
  val warn: ImageVector by lazy {
    imageVector("warn") {
      path("m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3")
      path("M12 9v4")
      path("M12 17h.01")
    }
  }
  val alert: ImageVector by lazy {
    imageVector("alert") {
      circle(12.0f, 12.0f, 10.0f)
      line(12.0f, 8.0f, 12.0f, 12.0f)
      line(12.0f, 16.0f, 12.01f, 16.0f)
    }
  }
  val info: ImageVector by lazy {
    imageVector("info") {
      circle(12.0f, 12.0f, 10.0f)
      path("M12 16v-4")
      path("M12 8h.01")
    }
  }
  val checkCircle: ImageVector by lazy {
    imageVector("checkCircle") {
      circle(12.0f, 12.0f, 10.0f)
      path("m9 12 2 2 4-4")
    }
  }
  val sun: ImageVector by lazy {
    imageVector("sun") {
      circle(12.0f, 12.0f, 4.0f)
      path("M12 2v2M12 20v2m-7.07-14.07 1.41 1.41m11.32 11.32 1.41 1.41M2 12h2m16 0h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41")
    }
  }
  val moon: ImageVector by lazy {
    imageVector("moon") {
      path("M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z")
    }
  }
  val command: ImageVector by lazy {
    imageVector("command") {
      path("M15 6v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3")
    }
  }
  val arrowUp: ImageVector by lazy {
    imageVector("arrowUp") {
      path("m5 12 7-7 7 7")
      path("M12 19V5")
    }
  }
  val arrowDown: ImageVector by lazy {
    imageVector("arrowDown") {
      path("M12 5v14")
      path("m19 12-7 7-7-7")
    }
  }
  val arrowLeft: ImageVector by lazy {
    imageVector("arrowLeft") {
      path("m12 19-7-7 7-7")
      path("M19 12H5")
    }
  }
  val panelLeft: ImageVector by lazy {
    imageVector("panelLeft") {
      rect(3.0f, 3.0f, 18.0f, 18.0f, 2.0f)
      path("M9 3v18")
    }
  }
  val panelRight: ImageVector by lazy {
    imageVector("panelRight") {
      rect(3.0f, 3.0f, 18.0f, 18.0f, 2.0f)
      path("M15 3v18")
    }
  }
  val refresh: ImageVector by lazy {
    imageVector("refresh") {
      path("M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8")
      path("M21 3v5h-5")
      path("M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16")
      path("M8 16H3v5")
    }
  }
  val zap: ImageVector by lazy {
    imageVector("zap") {
      path("M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z")
    }
  }
  val link: ImageVector by lazy {
    imageVector("link") {
      path("M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71")
      path("M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71")
    }
  }
  val repo: ImageVector by lazy {
    imageVector("repo") {
      path("M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z")
      path("m3.3 7 8.7 5 8.7-5")
      path("M12 22V12")
    }
  }
  val gauge: ImageVector by lazy {
    imageVector("gauge") {
      path("m12 14 4-4")
      path("M3.34 19a10 10 0 1 1 17.32 0")
    }
  }
  val monitor: ImageVector by lazy {
    imageVector("monitor") {
      rect(2.0f, 3.0f, 20.0f, 14.0f, 2.0f)
      line(8.0f, 21.0f, 16.0f, 21.0f)
      line(12.0f, 17.0f, 12.0f, 21.0f)
    }
  }
  val user: ImageVector by lazy {
    imageVector("user") {
      circle(12.0f, 8.0f, 5.0f)
      path("M20 21a8 8 0 0 0-16 0")
    }
  }
  val eye: ImageVector by lazy {
    imageVector("eye") {
      path("M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0")
      circle(12.0f, 12.0f, 3.0f)
    }
  }
  val pencil: ImageVector by lazy {
    imageVector("pencil") {
      path("M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z")
    }
  }
  val diff: ImageVector by lazy {
    imageVector("diff") {
      path("M12 3v14")
      path("M5 10h14")
      path("M5 21h14")
    }
  }
  val bot: ImageVector by lazy {
    imageVector("bot") {
      path("M12 8V4H8")
      rect(4.0f, 8.0f, 16.0f, 12.0f, 2.0f)
      path("M2 14h2")
      path("M20 14h2")
      path("M15 13v2")
      path("M9 13v2")
    }
  }
  val keyboard: ImageVector by lazy {
    imageVector("keyboard") {
      path("M10 8h.01")
      path("M12 12h.01")
      path("M14 8h.01")
      path("M16 12h.01")
      path("M18 8h.01")
      path("M6 8h.01")
      path("M7 16h10")
      path("M8 12h.01")
      rect(2.0f, 4.0f, 20.0f, 16.0f, 2.0f)
    }
  }
  val layers: ImageVector by lazy {
    imageVector("layers") {
      path("m12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z")
      path("m22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65")
      path("m22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65")
    }
  }
  val shield: ImageVector by lazy {
    imageVector("shield") {
      path("M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z")
    }
  }
  private fun imageVector(name: String, paths: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(paths).build()

  private fun ImageVector.Builder.path(data: String) {
    addPath(
      pathData = addPathNodes(data),
      fill = null,
      stroke = SolidColor(Color.Black),
      strokeLineWidth = 1.75f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
    )
  }

  private fun ImageVector.Builder.circle(cx: Float, cy: Float, radius: Float) =
    path("M ${cx - radius} $cy A $radius $radius 0 1 0 ${cx + radius} $cy A $radius $radius 0 1 0 ${cx - radius} $cy")

  private fun ImageVector.Builder.rect(x: Float, y: Float, width: Float, height: Float, radius: Float) {
    val r = radius.coerceAtMost(minOf(width, height) / 2f)
    if (r == 0f) {
      path("M $x $y H ${x + width} V ${y + height} H $x Z")
    } else {
      path("M ${x + r} $y H ${x + width - r} A $r $r 0 0 1 ${x + width} ${y + r} V ${y + height - r} A $r $r 0 0 1 ${x + width - r} ${y + height} H ${x + r} A $r $r 0 0 1 $x ${y + height - r} V ${y + r} A $r $r 0 0 1 ${x + r} $y Z")
    }
  }

  private fun ImageVector.Builder.line(x1: Float, y1: Float, x2: Float, y2: Float) = path("M $x1 $y1 L $x2 $y2")

  private fun ImageVector.Builder.poly(points: String, close: Boolean) {
    val coordinates = points.trim().split(Regex("\\s+")).map(String::toFloat).chunked(2)
    require(coordinates.all { it.size == 2 })
    val data = buildString {
      coordinates.forEachIndexed { index, point ->
        append(if (index == 0) "M " else " L ")
        append(point[0]).append(' ').append(point[1])
      }
      if (close) append(" Z")
    }
    path(data)
  }
}
