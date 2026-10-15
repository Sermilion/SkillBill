package dev.skillbill.tray.core.domain.model

import kotlin.math.roundToInt

data class DesktopUiScaleConfiguration(val windowScale: Float = 1f, val contentDensity: Float? = null)

fun DesktopUiScaleConfiguration.scaledWindowPixels(logicalPixels: Int): Int =
  (logicalPixels * windowScale).roundToInt().coerceAtLeast(1)
