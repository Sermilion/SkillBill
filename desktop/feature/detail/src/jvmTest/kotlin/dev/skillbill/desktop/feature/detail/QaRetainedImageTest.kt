package dev.skillbill.desktop.feature.detail

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QaRetainedImageTest {
  @Test
  fun `retained screenshots decode to a bounded preview and a missing file stays unavailable`() = runTest {
    val file = Files.createTempFile("qa-retained-", ".png")
    try {
      ImageIO.write(BufferedImage(2560, 1440, BufferedImage.TYPE_INT_RGB), "png", file.toFile())
      val image = assertIs<RetainedImage.Loaded>(loadRetainedImage(file.toString()))
      assertEquals(1280, image.bitmap.width)
      assertEquals(720, image.bitmap.height)
    } finally {
      Files.deleteIfExists(file)
    }
    assertEquals(RetainedImage.Unavailable, loadRetainedImage(file.toString()))
  }
}
