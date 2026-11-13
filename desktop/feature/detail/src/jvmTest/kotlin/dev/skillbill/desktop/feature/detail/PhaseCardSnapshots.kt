package dev.skillbill.desktop.feature.detail

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal fun assertPhaseSnapshot(name: String, actual: BufferedImage) {
  val baseline = File("src/jvmTest/resources/phase-cards/$name.png")
  val reports = File("build/reports/phase-cards").apply { mkdirs() }
  if (System.getenv("SONA_UPDATE_PHASE_SNAPSHOTS") == "1") {
    baseline.parentFile.mkdirs()
    ImageIO.write(actual, "png", baseline)
    return
  }
  ImageIO.write(actual, "png", File(reports, "$name-actual.png"))
  assertTrue(baseline.exists(), "Missing $baseline. Review the actual image before updating baselines.")
  val expected = ImageIO.read(baseline)
  assertEquals(expected.width, actual.width, "$name width")
  assertEquals(expected.height, actual.height, "$name height")
  var changed = 0
  val diff = BufferedImage(actual.width, actual.height, BufferedImage.TYPE_INT_ARGB)
  for (y in 0 until actual.height) for (x in 0 until actual.width) {
    val a = actual.getRGB(x, y)
    val b = expected.getRGB(x, y)
    val differs = listOf(0, 8, 16, 24).any { shift -> abs((a ushr shift and 255) - (b ushr shift and 255)) > 2 }
    if (differs) changed++
    diff.setRGB(x, y, if (differs) 0xffff3399.toInt() else (a and 0x00ffffff) or 0x40000000)
  }
  if (changed > 0) ImageIO.write(diff, "png", File(reports, "$name-diff.png"))
  assertTrue(changed.toDouble() / (actual.width * actual.height) <= .002, "$name changed $changed pixels. Review build/reports/phase-cards/$name-diff.png")
}
