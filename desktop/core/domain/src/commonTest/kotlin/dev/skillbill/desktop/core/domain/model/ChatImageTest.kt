package dev.skillbill.desktop.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ChatImageTest {
  // Queued messages are compared by value; array identity equality would make two equal pastes differ.
  @Test
  fun `images with equal bytes are equal and a differing byte is not`() {
    val image = ChatImage(byteArrayOf(1, 2, 3), "image/png")

    assertEquals(image, ChatImage(byteArrayOf(1, 2, 3), "image/png"))
    assertEquals(image.hashCode(), ChatImage(byteArrayOf(1, 2, 3), "image/png").hashCode())
    assertFalse(image == ChatImage(byteArrayOf(1, 2, 4), "image/png"))
    assertFalse(image == ChatImage(byteArrayOf(1, 2, 3), "image/jpeg"))
    assertEquals("ChatImage(image/png, 3 bytes)", image.toString())
  }
}
