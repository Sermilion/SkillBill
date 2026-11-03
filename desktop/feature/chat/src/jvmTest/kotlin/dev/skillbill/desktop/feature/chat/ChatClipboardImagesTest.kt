package dev.skillbill.desktop.feature.chat

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ChatClipboardImagesTest {
  @Test
  fun `a wide image is scaled to the 1568 px edge limit and encoded as PNG`() {
    val image = normalizedChatImage(BufferedImage(4000, 1000, BufferedImage.TYPE_INT_ARGB))

    assertEquals("image/png", image.mediaType)
    val decoded = assertNotNull(ImageIO.read(image.bytes.inputStream()))
    assertEquals(1568, decoded.width)
    assertEquals(392, decoded.height)
  }
}
