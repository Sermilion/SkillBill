package dev.skillbill.desktop.core.data.qa

import dev.skillbill.desktop.core.domain.model.QaPreviewState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class XwdFrameDecoderTest {
  @Test
  fun `decodes both pixel byte orders after color table and padded rows`() {
    val bytes = ByteArray(140)
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    val fields = intArrayOf(
      104, 7, 2, 24, 2, 2, 0, 0, 32, 0, 32, 32, 12, 4,
      0x00ff0000, 0x0000ff00, 0x000000ff, 8, 256, 1, 2, 2, 0, 0, 0,
    )
    fields.forEach { header.putInt(it) }
    bytes[100] = 't'.code.toByte()
    bytes[101] = 'e'.code.toByte()
    bytes[102] = 's'.code.toByte()
    bytes[103] = 't'.code.toByte()
    bytes[116] = 0x33
    bytes[117] = 0x22
    bytes[118] = 0x11
    bytes[120] = 0x66
    bytes[121] = 0x55
    bytes[122] = 0x44
    bytes[124] = 0x7f
    bytes[128] = 0x99.toByte()
    bytes[129] = 0x88.toByte()
    bytes[130] = 0x77
    bytes[132] = 0xcc.toByte()
    bytes[133] = 0xbb.toByte()
    bytes[134] = 0xaa.toByte()

    val frame = assertIs<XwdDecodeResult.Frame>(XwdFrameDecoder.decode(bytes)).value

    assertEquals(2, frame.width)
    assertEquals(2, frame.height)
    val expected = intArrayOf(0xff112233.toInt(), 0xff445566.toInt(), 0xff778899.toInt(), 0xffaabbcc.toInt())
    assertContentEquals(expected, frame.argb)

    val bigEndian = bytes.copyOf()
    ByteBuffer.wrap(bigEndian).order(ByteOrder.BIG_ENDIAN).putInt(28, 1)
    bigEndian[116] = 0
    bigEndian[117] = 0x11
    bigEndian[118] = 0x22
    bigEndian[119] = 0x33
    bigEndian[120] = 0
    bigEndian[121] = 0x44
    bigEndian[122] = 0x55
    bigEndian[123] = 0x66
    bigEndian[128] = 0
    bigEndian[129] = 0x77
    bigEndian[130] = 0x88.toByte()
    bigEndian[131] = 0x99.toByte()
    bigEndian[132] = 0
    bigEndian[133] = 0xaa.toByte()
    bigEndian[134] = 0xbb.toByte()
    bigEndian[135] = 0xcc.toByte()

    val bigEndianFrame = assertIs<XwdDecodeResult.Frame>(XwdFrameDecoder.decode(bigEndian)).value
    assertContentEquals(expected, bigEndianFrame.argb)
  }

  @Test
  fun `rejects dimensions above pixel bound before allocating`() {
    val bytes = ByteArray(100)
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    val fields = intArrayOf(
      100, 7, 2, 24, 4096, 4096, 0, 0, 32, 0, 32, 32, 16384, 4,
      0x00ff0000, 0x0000ff00, 0x000000ff, 8, 256, 0, 4096, 4096, 0, 0, 0,
    )
    fields.forEach { header.putInt(it) }

    val rejected = assertIs<XwdDecodeResult.Rejected>(XwdFrameDecoder.decode(bytes))

    assertEquals(QaPreviewState.Reason.LimitsExceeded, rejected.reason)
  }
}
