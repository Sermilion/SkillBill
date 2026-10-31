package dev.skillbill.desktop.app.display

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import kotlin.math.roundToInt

internal const val SKILL_BILL_DESKTOP_UI_SCALE_PROPERTY = "skillbill.desktop.uiScale"
internal const val SKILL_BILL_DESKTOP_UI_SCALE_ENV = "SKILL_BILL_DESKTOP_UI_SCALE"

private const val DEFAULT_DENSITY = 1f
private const val SCALE_EPSILON = 0.05f
private const val MIN_REASONABLE_SCALE = 0.5f
private const val MAX_REASONABLE_SCALE = 4f

/**
 * How the app's windows scale. [contentDensity] replaces the platform density for Compose content; null keeps the platform's.
 * [windowScale] is the ratio of content density to AWT density: AWT does not see the override, so window sizes in AWT units
 * are multiplied by it to fit content laid out at [contentDensity]. Both stay at their defaults unless an override applies.
 */
data class DesktopUiScale(val windowScale: Float = DEFAULT_DENSITY, val contentDensity: Float? = null) {
  fun windowPixels(logicalPixels: Int): Int = (logicalPixels * windowScale).roundToInt().coerceAtLeast(1)

  fun windowSize(content: DpSize): DpSize = DpSize((content.width.value * windowScale).dp, (content.height.value * windowScale).dp)

  fun windowDimension(width: Int, height: Int): Dimension = Dimension(windowPixels(width), windowPixels(height))
}

/**
 * Resolves the UI scale once at startup. An explicit `-Dskillbill.desktop.uiScale` or `SKILL_BILL_DESKTOP_UI_SCALE` wins on
 * every OS. Otherwise macOS, Windows, and Linux JVMs that already report a scale above 1 keep the platform density. A Linux JVM
 * reporting 1 is often wrong on HiDPI screens, so the scale is read from `QT_SCALE_FACTOR`, `GDK_SCALE`, then KWin's config.
 */
internal fun resolveDesktopUiScale(
  environment: Map<String, String> = System.getenv(),
  systemProperties: Properties = System.getProperties(),
  configHome: Path = defaultConfigHome(environment, systemProperties),
  osName: String = System.getProperty("os.name").orEmpty(),
  awtDensity: Float = currentAwtDensity(),
): DesktopUiScale {
  val platformDensity = awtDensity.sanitizedScale() ?: DEFAULT_DENSITY
  val explicit = parseUiScale(systemProperties.getProperty(SKILL_BILL_DESKTOP_UI_SCALE_PROPERTY))
    ?: parseUiScale(environment[SKILL_BILL_DESKTOP_UI_SCALE_ENV])
  if (explicit != null) return scaleFor(explicit, platformDensity)
  val keepPlatform = !osName.contains("linux", ignoreCase = true) || platformDensity > DEFAULT_DENSITY + SCALE_EPSILON
  if (keepPlatform) return DesktopUiScale()
  val detected = sequenceOf(
    { parseUiScale(environment["QT_SCALE_FACTOR"]) },
    { parseUiScale(environment["GDK_SCALE"]) },
    { detectKdeScale(environment, configHome) },
  ).mapNotNull { it() }.firstOrNull { it > DEFAULT_DENSITY + SCALE_EPSILON }
  return detected?.let { scaleFor(it, platformDensity) } ?: DesktopUiScale()
}

/** Renders [content] at [scale]'s content density, keeping the platform's font scale. */
@Composable
internal fun DesktopUiScaleProvider(scale: DesktopUiScale, content: @Composable () -> Unit) {
  val platform = LocalDensity.current
  val density = remember(platform, scale.contentDensity) {
    scale.contentDensity?.let { Density(it, platform.fontScale) } ?: platform
  }
  CompositionLocalProvider(LocalDensity provides density, content = content)
}

private fun scaleFor(target: Float, platformDensity: Float): DesktopUiScale =
  DesktopUiScale(windowScale = (target / platformDensity).coerceIn(MIN_REASONABLE_SCALE, MAX_REASONABLE_SCALE), contentDensity = target)

private fun detectKdeScale(environment: Map<String, String>, configHome: Path): Float? {
  val desktop = listOfNotNull(environment["XDG_CURRENT_DESKTOP"], environment["DESKTOP_SESSION"]).joinToString(" ").lowercase()
  if ("kde" !in desktop && "plasma" !in desktop) return null
  return readKwinXwaylandScale(configHome.resolve("kwinrc")) ?: readKdeOutputScale(configHome.resolve("kwinoutputconfig.json"))
}

/** The `Scale` entry of `kwinrc`'s `[Xwayland]` section, or null when the file, the section or a sane value is missing. */
internal fun readKwinXwaylandScale(path: Path): Float? {
  if (!Files.exists(path)) return null
  var inSection = false
  return try {
    Files.readAllLines(path).firstNotNullOfOrNull { line ->
      val value = line.trim()
      when {
        value.startsWith("[") && value.endsWith("]") -> { inSection = value == "[Xwayland]"; null }
        inSection && value.substringBefore("=", "").trim() == "Scale" -> parseUiScale(value.substringAfter("=", "").trim())
        else -> null
      }
    }
  } catch (_: Exception) {
    null
  }
}

/** The scale of KDE's primary enabled output in `kwinoutputconfig.json`, or the largest output scale when no primary is set. */
internal fun readKdeOutputScale(path: Path): Float? {
  if (!Files.exists(path)) return null
  val root = try {
    Json.parseToJsonElement(Files.readString(path)) as? JsonArray
  } catch (_: Exception) {
    null
  } ?: return null
  val scales = root.section("outputs").mapIndexedNotNull { index, output ->
    (output as? JsonObject)?.number("scale")?.sanitizedScale()?.let { index to it }
  }
  if (scales.isEmpty()) return null
  val primary = root.section("setups").firstNotNullOfOrNull { setup ->
    (setup as? JsonObject)?.array("outputs")?.mapNotNull(JsonElement::enabledOutputPriority)?.minByOrNull { it.first }?.second
  }
  return primary?.let { index -> scales.firstOrNull { it.first == index }?.second } ?: scales.maxOf { it.second }
}

private fun JsonArray.section(name: String): JsonArray =
  firstNotNullOfOrNull { section -> (section as? JsonObject)?.takeIf { it.string("name") == name }?.array("data") } ?: JsonArray(emptyList())

/** An enabled setup output as (priority, output index); a missing priority sorts last. */
private fun JsonElement.enabledOutputPriority(): Pair<Int, Int>? {
  val output = this as? JsonObject ?: return null
  val index = output.int("outputIndex") ?: return null
  if (output.string("enabled")?.toBooleanStrictOrNull() == false) return null
  return (output.int("priority") ?: Int.MAX_VALUE) to index
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

private fun JsonObject.number(key: String): Float? = string(key)?.toFloatOrNull()

private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()

private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/** Accepts `1.5`, `1.5x`, `150%` and `150`; a bare number above 10 is a percentage. */
private fun parseUiScale(value: String?): Float? {
  val raw = value?.trim().orEmpty()
  if (raw.isEmpty()) return null
  val percent = raw.endsWith("%")
  val number = raw.removeSuffix("%").removeSuffix("x").removeSuffix("X").trim().toFloatOrNull() ?: return null
  return (if (percent || number > 10f) number / 100f else number).sanitizedScale()
}

private fun Float.sanitizedScale(): Float? = takeIf { it.isFinite() && it in MIN_REASONABLE_SCALE..MAX_REASONABLE_SCALE }

private fun defaultConfigHome(environment: Map<String, String>, systemProperties: Properties): Path =
  environment["XDG_CONFIG_HOME"]?.takeIf(String::isNotBlank)?.let(Paths::get)
    ?: Paths.get(systemProperties.getProperty("user.home").orEmpty(), ".config")

private fun currentAwtDensity(): Float = try {
  if (GraphicsEnvironment.isHeadless()) DEFAULT_DENSITY
  else GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX.toFloat()
} catch (_: Exception) {
  DEFAULT_DENSITY
}
