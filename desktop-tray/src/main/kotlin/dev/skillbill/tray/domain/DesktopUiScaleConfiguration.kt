package dev.skillbill.tray.domain

import kotlin.math.roundToInt

data class DesktopUiScaleConfiguration(val windowScale: Float = 1f, val contentDensity: Float? = null)

fun DesktopUiScaleConfiguration.scaledWindowPixels(logicalPixels: Int): Int =
  (logicalPixels * windowScale).roundToInt().coerceAtLeast(1)
